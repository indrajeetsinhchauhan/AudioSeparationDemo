package com.indrajeet.chauhan.spleeterdemo

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class AudioStemSeparation(
    private val context: Context, private val modelName: String,
    private val filePath: String
) {
    private lateinit var interpreter: Interpreter

    @Throws(IOException::class)
    private fun loadModelFile(modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd("$modelName.tflite")
        val fileChannel = FileInputStream(fileDescriptor.fileDescriptor).channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            fileDescriptor.startOffset,
            fileDescriptor.declaredLength
        )
    }

    @Throws(IOException::class)
    private suspend fun loadAudioFile(context: Context, assetFileName: String): Pair<IntArray, Array<FloatArray>>? {
        val tempFile = File.createTempFile("decoded_${System.currentTimeMillis()}", ".pcm", context.cacheDir)
        val assetInputStream = context.assets.open(assetFileName)
        val tempAssetFile = File.createTempFile("temp_asset_${System.currentTimeMillis()}", ".mp3", context.cacheDir)
        FileOutputStream(tempAssetFile).use { assetInputStream.copyTo(it) }

        val session = FFmpegKit.execute("-i $tempAssetFile -f f32le -acodec pcm_f32le -ac 2 -ar 44100 ${tempFile.absolutePath} -y")
        if (!ReturnCode.isSuccess(session.returnCode)) {
            Log.e("loadAudioFile", "FFmpeg command failed with return code ${session.returnCode}")
            return null
        }

//        val dataList = ArrayList<FloatArray>()
//        tempFile.inputStream().buffered().use { inputStream ->
//            val buffer = ByteArray(8)
//            while (inputStream.read(buffer) != -1) {
//                val leftChannel = ByteBuffer.wrap(buffer, 0, 4).order(ByteOrder.LITTLE_ENDIAN).float
//                val rightChannel = ByteBuffer.wrap(buffer, 4, 4).order(ByteOrder.LITTLE_ENDIAN).float
//                dataList.add(floatArrayOf(leftChannel, rightChannel))
//            }
//        }

        return withContext(Dispatchers.IO) {
            var dataList = ArrayList<FloatArray>()
            dataList = loadAudioData(tempFile)

            tempFile.delete()
            tempAssetFile.delete()

            val dims = intArrayOf(dataList.size, 2)
            Pair(dims, dataList.toTypedArray())
        }
    }

    suspend fun loadAudioData(tempFile: File): ArrayList<FloatArray> {
        val dataList = ArrayList<FloatArray>()
        tempFile.inputStream().buffered(1024).use { inputStream ->
            val buffer = ByteArray(1024)  // Large buffer size
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                processBuffer(buffer, bytesRead, dataList)
            }
        }
        return dataList
    }

    fun processBuffer(buffer: ByteArray, bytesRead: Int, dataList: MutableList<FloatArray>) {
        var i = 0
        while (i < bytesRead - 7) {  // Ensure there's enough bytes left for a full frame
            val leftChannel = ByteBuffer.wrap(buffer, i, 4).order(ByteOrder.LITTLE_ENDIAN).float
            val rightChannel = ByteBuffer.wrap(buffer, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).float
            dataList.add(floatArrayOf(leftChannel, rightChannel))
            i += 8  // Move to the next frame
        }
    }

    private fun saveAudioFile(context: Context, data: Array<FloatArray>, filePath: String) {
        val tempFile = File.createTempFile("encoded_${System.currentTimeMillis()}", ".pcm", context.cacheDir)
        tempFile.outputStream().buffered().use { outputStream ->
            val buffer = ByteBuffer.allocate(1024)
            for (frame in data) {
                buffer.order(ByteOrder.LITTLE_ENDIAN).putFloat(frame[0]).putFloat(frame[1])
                outputStream.write(buffer.array())
                buffer.clear()
            }
        }

        val session = FFmpegKit.execute("-y -f f32le -acodec pcm_f32le -ac 2 -ar 44100 -i ${tempFile.absolutePath} $filePath")
        if (!ReturnCode.isSuccess(session.returnCode)) {
            Log.e("saveAudioFile", "FFmpeg command failed with return code ${session.returnCode}")
        }

        tempFile.delete()
    }

    fun init() {
        try {
            val model = loadModelFile(modelName)
            interpreter = Interpreter(model)
        } catch (e: IOException) {
            Log.e("AudioStemSeparation", "Error: couldn't load tflite model.", e)
        }
    }

    suspend fun separate() {
        val waveform = loadAudioFile(context, filePath) ?: run {
            Log.e("AudioStemSeparation", "Failed to load audio file")
            return
        }

        interpreter.resizeInput(0, waveform.first)
        interpreter.allocateTensors()

        val outputMap = HashMap<Int, Any>()
        for (i in 0 until interpreter.outputTensorCount) {
            outputMap[i] = Array(waveform.first[0]) { FloatArray(waveform.first[1]) }
        }

        interpreter.runForMultipleInputsOutputs(arrayOf(waveform.second), outputMap)

        outputMap.forEach { (index, data) ->
            saveAudioFile(context, data as Array<FloatArray>, "${context.filesDir.path}/$index.mp3")
        }
    }

    fun unInit() {
        interpreter.close()
    }

}