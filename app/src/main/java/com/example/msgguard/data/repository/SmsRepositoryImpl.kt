package com.example.msgguard.data.repository

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorBuilder
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.example.msgguard.domain.repository.SmsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.LongBuffer
import java.util.*
import java.io.File

/**
 * Implementation of SmsRepository.
 */
class SmsRepositoryImpl : SmsRepository {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession? = OrtSession.createForFile(File("app/src/main/assets/sms_model.onnx"), env)

    override suspend fun isSpam(content: String): Boolean = withContext(Dispatchers.IO) {
        if (session == null) return@withContext false

        val inputName = session.inputNames[0]
        val inputData = arrayOf(content)
        val shape = longArrayOf(1, 1)

        val inputTensor = OnnxTensor.createTensor(env, inputName, shape, inputData)
        val results = session.run(mapOf(inputName to inputTensor))
        val output = results[0].value as FloatArray

        inputTensor.close()
        results.close()

        return@withContext output.any { it > 0.5f }
    }
}
