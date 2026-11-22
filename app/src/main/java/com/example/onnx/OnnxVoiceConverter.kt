package com.example.onnx

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.net.Uri
import com.example.audio.AudioCodecHelper
import com.example.model.ConversionResult
import com.example.model.OnnxModelInfo
import com.example.model.OnnxTensorSummary
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

object OnnxVoiceConverter {

    private const val DEFAULT_MODEL_SAMPLE_RATE_HZ = 16_000

    fun inspectAndCacheModel(context: Context, uri: Uri): OnnxModelInfo {
        val meta = AudioCodecHelper.queryUriMetadata(context, uri, "voice_model.onnx")
        require(meta.displayName.lowercase(Locale.US).endsWith(".onnx")) {
            "Selected file \"${meta.displayName}\" is not a .onnx model file."
        }

        val modelsDir = File(context.cacheDir, "onnx_models").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        val safeName = meta.displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val cachedFile = File(modelsDir, safeName)

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(cachedFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Could not open selected .onnx model file.")

        val actualSize = if (meta.sizeBytes > 0) meta.sizeBytes else cachedFile.length()
        require(actualSize > 0L) { "Selected .onnx model file is empty." }

        val env = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            env.createSession(cachedFile.absolutePath, options).use { session ->
                val inputs = session.inputInfo.entries.map { (name, nodeInfo) ->
                    nodeInfo.toSummary(name)
                }
                val outputs = session.outputInfo.entries.map { (name, nodeInfo) ->
                    nodeInfo.toSummary(name)
                }
                require(inputs.isNotEmpty() && outputs.isNotEmpty()) {
                    "ONNX model must declare at least one input and one output tensor."
                }
                return OnnxModelInfo(
                    uri = uri,
                    cachedModelPath = cachedFile.absolutePath,
                    displayName = meta.displayName,
                    sizeBytes = actualSize,
                    inputs = inputs,
                    outputs = outputs
                )
            }
        }
    }

    fun convertAudio(
        context: Context,
        modelInfo: OnnxModelInfo,
        audioUri: Uri,
        onStageChanged: (String) -> Unit
    ): ConversionResult {
        val startNs = System.nanoTime()
        onStageChanged("Decoding source audio…")
        val decoded = AudioCodecHelper.decodeToMonoFloat(context, audioUri)

        onStageChanged("Preparing 16 kHz waveform…")
        val targetSampleRate = DEFAULT_MODEL_SAMPLE_RATE_HZ
        val inputSamples = AudioCodecHelper.resampleLinear(
            samples = decoded.monoSamples,
            fromRate = decoded.sampleRateHz,
            toRate = targetSampleRate
        )

        onStageChanged("Running ONNX voice model…")
        val env = OrtEnvironment.getEnvironment()
        val outputSamples = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            env.createSession(modelInfo.cachedModelPath, options).use { session ->
                runInferenceOnWaveform(env, session, inputSamples)
            }
        }

        val normalizedOutput = normalizePeakIfNeeded(outputSamples)
        onStageChanged("Writing converted WAV…")
        val outputDir = File(context.cacheDir, "converted_audio").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        val baseModelName = modelInfo.displayName.substringBeforeLast('.').replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val outFileName = "converted_${baseModelName}_${System.currentTimeMillis()}.wav"
        val outFile = File(outputDir, outFileName)

        AudioCodecHelper.writeWav16Bit(
            samples = normalizedOutput,
            sampleRateHz = targetSampleRate,
            outputFile = outFile
        )

        val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
        val outDurationMs = ((normalizedOutput.size.toDouble() / targetSampleRate.toDouble()) * 1000.0).toLong()
        return ConversionResult(
            outputFilePath = outFile.absolutePath,
            outputFileName = outFileName,
            sampleRateHz = targetSampleRate,
            durationMs = outDurationMs,
            inferenceTimeMs = elapsedMs
        )
    }

    private fun runInferenceOnWaveform(
        env: OrtEnvironment,
        session: OrtSession,
        waveform: FloatArray
    ): FloatArray {
        val inputInfos = session.inputInfo
        val primaryEntry = findPrimaryWaveformInput(inputInfos)
            ?: throw IllegalArgumentException(
                "Incompatible ONNX model inputs: ${describeInputs(inputInfos)}. " +
                    "Expected a 1D/2D/3D float waveform input tensor (e.g. [1, N] or [1, 1, N])."
            )

        val primaryTensorInfo = primaryEntry.value.info as TensorInfo
        val staticSampleCount = extractFixedSampleCount(primaryTensorInfo.shape)

        return if (staticSampleCount != null && staticSampleCount > 0) {
            runChunkedInference(env, session, inputInfos, primaryEntry.key, primaryTensorInfo, waveform, staticSampleCount)
        } else {
            runSinglePassInference(env, session, inputInfos, primaryEntry.key, primaryTensorInfo, waveform)
        }
    }

    private fun runChunkedInference(
        env: OrtEnvironment,
        session: OrtSession,
        inputInfos: Map<String, NodeInfo>,
        primaryName: String,
        primaryInfo: TensorInfo,
        waveform: FloatArray,
        chunkSize: Int
    ): FloatArray {
        val outputChunks = mutableListOf<Float>()
        var offset = 0
        while (offset < waveform.size) {
            val end = (offset + chunkSize).coerceAtMost(waveform.size)
            val slice = FloatArray(chunkSize)
            waveform.copyInto(slice, destinationOffset = 0, startIndex = offset, endIndex = end)
            val chunkOut = runSinglePassInference(env, session, inputInfos, primaryName, primaryInfo, slice)
            val validRatio = (end - offset).toFloat() / chunkSize.toFloat()
            val takeCount = (chunkOut.size * validRatio).toInt().coerceIn(0, chunkOut.size)
            for (i in 0 until takeCount) {
                outputChunks.add(chunkOut[i])
            }
            offset += chunkSize
        }
        return outputChunks.toFloatArray()
    }

    private fun runSinglePassInference(
        env: OrtEnvironment,
        session: OrtSession,
        inputInfos: Map<String, NodeInfo>,
        primaryName: String,
        primaryInfo: TensorInfo,
        waveform: FloatArray
    ): FloatArray {
        val tensors = mutableMapOf<String, OnnxTensor>()
        try {
            for ((name, nodeInfo) in inputInfos) {
                val tensorInfo = nodeInfo.info as? TensorInfo
                    ?: throw IllegalArgumentException("Unsupported non-tensor input '$name'.")
                val tensor = if (name == primaryName) {
                    val shape = resolveWaveformShape(primaryInfo.shape, waveform.size.toLong())
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(waveform), shape)
                } else {
                    createAuxiliaryTensor(env, name, tensorInfo, waveform.size)
                }
                tensors[name] = tensor
            }

            session.run(tensors).use { results ->
                val outValue = results[0]
                val outTensor = outValue as? OnnxTensor
                    ?: throw IllegalStateException("ONNX model returned non-tensor output.")
                val floatBuffer = outTensor.floatBuffer
                    ?: throw IllegalStateException("ONNX model output tensor is not FLOAT32.")
                val outArray = FloatArray(floatBuffer.remaining())
                floatBuffer.get(outArray)
                return outArray
            }
        } finally {
            tensors.values.forEach { runCatching { it.close() } }
        }
    }

    private fun findPrimaryWaveformInput(inputInfos: Map<String, NodeInfo>): Map.Entry<String, NodeInfo>? {
        val floatEntries = inputInfos.entries.filter { (_, node) ->
            val info = node.info as? TensorInfo ?: return@filter false
            info.type == OnnxJavaType.FLOAT && info.shape.size in 1..3 &&
                info.shape.count { it > 1L } <= 1
        }
        if (floatEntries.isEmpty()) return null
        val preferredNames = listOf("audio", "wav", "waveform", "input", "x", "signal", "src")
        return floatEntries.firstOrNull { entry ->
            val lower = entry.key.lowercase(Locale.US)
            preferredNames.any { lower.contains(it) }
        } ?: floatEntries.first()
    }

    private fun extractFixedSampleCount(shape: LongArray): Int? {
        if (shape.isEmpty()) return null
        val last = shape.last()
        return if (last > 1L) last.toInt() else null
    }

    private fun resolveWaveformShape(template: LongArray, sampleCount: Long): LongArray {
        if (template.isEmpty()) return longArrayOf(1L, sampleCount)
        val resolved = template.copyOf()
        for (i in 0 until resolved.lastIndex) {
            if (resolved[i] <= 0L) resolved[i] = 1L
        }
        resolved[resolved.lastIndex] = if (resolved.last() > 1L) resolved.last() else sampleCount
        return resolved
    }

    private fun createAuxiliaryTensor(
        env: OrtEnvironment,
        name: String,
        info: TensorInfo,
        sampleCount: Int
    ): OnnxTensor {
        val lower = name.lowercase(Locale.US)
        val resolvedShape = LongArray(info.shape.size) { idx ->
            val dim = info.shape[idx]
            when {
                dim > 0L -> dim
                idx == info.shape.lastIndex && (lower.contains("rnd") || lower.contains("noise")) -> sampleCount.toLong()
                else -> 1L
            }
        }
        val elementCount = resolvedShape.fold(1L) { acc, d -> acc * d }.toInt().coerceAtLeast(1)

        return when (info.type) {
            OnnxJavaType.INT64 -> {
                val values = LongArray(elementCount) {
                    when {
                        lower.contains("len") -> sampleCount.toLong()
                        lower.contains("rate") || lower == "sr" -> DEFAULT_MODEL_SAMPLE_RATE_HZ.toLong()
                        else -> 0L
                    }
                }
                OnnxTensor.createTensor(env, LongBuffer.wrap(values), resolvedShape)
            }
            OnnxJavaType.INT32 -> {
                val values = IntArray(elementCount) {
                    when {
                        lower.contains("len") -> sampleCount
                        lower.contains("rate") || lower == "sr" -> DEFAULT_MODEL_SAMPLE_RATE_HZ
                        else -> 0
                    }
                }
                OnnxTensor.createTensor(env, IntBuffer.wrap(values), resolvedShape)
            }
            OnnxJavaType.FLOAT -> {
                val values = FloatArray(elementCount) {
                    when {
                        lower.contains("rnd") || lower.contains("noise") -> (Random.nextFloat() * 2f - 1f) * 0.1f
                        lower.contains("scale") -> 0.667f
                        else -> 0f
                    }
                }
                OnnxTensor.createTensor(env, FloatBuffer.wrap(values), resolvedShape)
            }
            else -> throw IllegalArgumentException(
                "Unsupported auxiliary input tensor '$name' (${info.type}, ${info.shape.toList()})."
            )
        }
    }

    private fun normalizePeakIfNeeded(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return samples
        var peak = 0f
        for (s in samples) {
            val a = abs(s)
            if (a > peak && a.isFinite()) peak = a
        }
        if (peak <= 1.0f) return samples
        val scale = 0.98f / max(peak, 1e-6f)
        return FloatArray(samples.size) { i ->
            val v = samples[i]
            if (v.isFinite()) (v * scale).coerceIn(-1f, 1f) else 0f
        }
    }

    private fun NodeInfo.toSummary(name: String): OnnxTensorSummary {
        val tensorInfo = info as? TensorInfo
        return OnnxTensorSummary(
            name = name,
            dataType = tensorInfo?.type?.name ?: "UNKNOWN",
            shape = tensorInfo?.shape?.toList() ?: emptyList()
        )
    }

    private fun describeInputs(inputInfos: Map<String, NodeInfo>): String {
        return inputInfos.entries.joinToString(", ") { (name, node) ->
            node.toSummary(name).formattedShape()
        }
    }
}
