package com.example.msgguard.data.repository

import ai.onnxruntime.OnnxTensor
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

    // Initialize the session lazily to avoid blocking the main thread during application startup
    private val session: OrtSession? by lazy {
        try {
            OrtSession.createForFile(File("app/src/main/assets/sms_model.onnx"), env)
        } catch (e: Exception) {
            println("Failed to initialize ONNX session: ${e.message}")
            null
        }
    }

    override suspend fun isSpam(content: String): Boolean = withContext(Dispatchers.IO) {
        val currentSession = session
        if (currentSession == null) return@withContext false

        val inputNames = currentSession.inputNames
        if (inputNames.isEmpty()) return@withContext false

        val inputName = inputNames[0]
        val shape = longArrayOf(1, 1)

        // Create the input tensor for String data
        val inputTensor = OnnxTensor.createTensor(env, inputName, shape, arrayOf(content))

        return@withContext try {
            val results = currentSession.run(mapOf(inputName to inputTensor))
            val output = results[0]
            val outputData = output.value as FloatArray

            // Close the resources
            inputTensor.close()
            results.close()

            // Return true if any probability in the output exceeds the 0.5 threshold
            outputData.any { it > 0.5f }
        } catch (e: Exception) {
            println("Error during inference: ${e.message}")
            false
        }
    }

    override suspend fun markAsSpam(messageId: String) = withContext(Dispatchers.IO) {
        println("Marking message $messageId as spam")
    }
}