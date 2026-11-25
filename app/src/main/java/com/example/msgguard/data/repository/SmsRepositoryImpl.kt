package com.example.msgguard.data.repository

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.example.msgguard.domain.repository.SmsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Context

/**
 * Implementation of SmsRepository using ONNX Runtime.
 */
class SmsRepositoryImpl(context: Context) : SmsRepository {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val applicationContext = context.applicationContext

    // Initialize the session lazily to avoid blocking the main thread during application startup
    private val session: OrtSession? by lazy {
        try {
            applicationContext.assets.open("sms_model.onnx").use { model ->
                env.createSession(model.readBytes())
            }
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

        val inputName = inputNames.first()
        val shape = longArrayOf(1, 1)

        // Create the input tensor for String data
        val inputTensor = OnnxTensor.createTensor(env, arrayOf(content), shape)

        return@withContext try {
            val results = currentSession.run(mapOf(inputName to inputTensor))
            try {
                val outputLabels = results[0].value as LongArray

                // The training labels use 1 for spam.
                outputLabels.firstOrNull() == 1L
            } finally {
                results.close()
            }
        } catch (e: Exception) {
            println("Error during inference: ${e.message}")
            false
        } finally {
            inputTensor.close()
        }
    }
}
