package com.example.msgguard.data.repository

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorImpl
import ai.onnxruntime.OnnxRuntime
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.example.msgguard.domain.repository.SmsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Implementation of SmsRepository using ONNX Runtime.
 */
class SmsRepositoryImpl : SmsRepository {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession? = OrtSession.createForFile(File("app/src/main/assets/sms_model.onnx"), env)

    override suspend fun isSpam(content: String): Boolean = withContext(Dispatchers.IO) {
        if (session == null) return@withContext false

        val inputNames = session.inputNames
        if (inputNames.isEmpty()) return@withContext false

        val inputName = inputNames[0]
        val shape = longArrayOf(1, 1)

        // Create input tensor for string data
        val inputTensor = OnnxTensor.createTensor(env, inputName, shape, arrayOf(content))

        val results = session.run(mapOf(inputName to inputTensor))

        // Retrieve results
        val output = results[0]
        val outputData = output.value as FloatArray

        // Clean up
        inputTensor.close()
        results.close()

        // Check if any value in the output array exceeds the 0.5 spam threshold
        return@withContext outputData.any { it > 0.5f }
    }

    override suspend fun markAsSpam(messageId: String) = withContext(Dispatchers.IO) {
        println("Marking message $messageId as spam")
    }
}