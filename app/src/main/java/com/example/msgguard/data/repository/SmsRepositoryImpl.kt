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
    private val session: OrtSession by lazy {
        applicationContext.assets.open("sms_model.onnx").use { model ->
            env.createSession(model.readBytes())
        }
    }

    override suspend fun isSpam(content: String): Boolean = withContext(Dispatchers.IO) {
        val inputNames = session.inputNames
        check(inputNames.isNotEmpty()) { "The ONNX model has no input tensor." }

        val inputName = inputNames.first()
        val shape = longArrayOf(1, 1)

        // Create the input tensor for String data
        val inputTensor = OnnxTensor.createTensor(env, arrayOf(content), shape)

        return@withContext try {
            val results = session.run(mapOf(inputName to inputTensor))
            try {
                val outputLabels = results[0].value as LongArray

                // Training labels: 0 = advertisement, 1 = other.
                outputLabels.firstOrNull() == 0L
            } finally {
                results.close()
            }
        } finally {
            inputTensor.close()
        }
    }
}
