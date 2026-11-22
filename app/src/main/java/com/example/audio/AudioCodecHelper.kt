package com.example.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import com.example.model.AudioFileInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.roundToInt

data class DecodedAudio(
    val monoSamples: FloatArray,
    val sampleRateHz: Int,
    val channelCount: Int,
    val durationMs: Long
)

data class DecodedStereoAudio(
    val leftSamples: FloatArray,
    val rightSamples: FloatArray,
    val sampleRateHz: Int,
    val channelCount: Int,
    val durationMs: Long
)

data class UriMetadata(
    val displayName: String,
    val sizeBytes: Long
)

object AudioCodecHelper {

    fun queryUriMetadata(context: Context, uri: Uri, fallbackName: String): UriMetadata {
        var displayName = fallbackName
        var sizeBytes = -1L

        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    displayName = cursor.getString(nameIndex)
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    sizeBytes = cursor.getLong(sizeIndex)
                }
            }
        }

        return UriMetadata(displayName = displayName, sizeBytes = sizeBytes)
    }

    fun inspectAudioFile(context: Context, uri: Uri): AudioFileInfo {
        val meta = queryUriMetadata(context, uri, "audio_input")
        val extension = meta.displayName.substringAfterLast('.', "").uppercase(Locale.US)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val audioTrackIndex = findAudioTrack(extractor)
            require(audioTrackIndex >= 0) { "No audio track found in file." }
            val format = extractor.getTrackFormat(audioTrackIndex)
            val sampleRate = format.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44100)
            val channels = format.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            val formatLabel = if (extension.isNotBlank()) extension else mime.substringAfter('/').uppercase(Locale.US)

            return AudioFileInfo(
                uri = uri,
                displayName = meta.displayName,
                sizeBytes = meta.sizeBytes,
                sampleRateHz = sampleRate,
                channelCount = channels,
                durationMs = (durationUs / 1000L).coerceAtLeast(0L),
                formatLabel = formatLabel.ifBlank { "AUDIO" }
            )
        } finally {
            extractor.release()
        }
    }

    fun decodeToMonoFloat(context: Context, uri: Uri): DecodedAudio {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = findAudioTrack(extractor)
            require(trackIndex >= 0) { "No audio track found in selected file." }
            extractor.selectTrack(trackIndex)

            val trackFormat = extractor.getTrackFormat(trackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalArgumentException("Unknown audio MIME type.")

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(trackFormat, null, null, 0)
            codec.start()

            var outputSampleRate = trackFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44100)
            var outputChannels = trackFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            val pcmBytes = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    sampleSize,
                                    extractor.sampleTime,
                                    0
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        outputSampleRate = newFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, outputSampleRate)
                        outputChannels = newFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, outputChannels)
                        pcmEncoding = newFormat.getIntegerOrDefault(
                            MediaFormat.KEY_PCM_ENCODING,
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                    }
                    outputIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val chunk = ByteArray(bufferInfo.size)
                            outputBuffer.get(chunk)
                            pcmBytes.write(chunk)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            val raw = pcmBytes.toByteArray()
            val mono = pcmBytesToMonoFloat(raw, outputChannels.coerceAtLeast(1), pcmEncoding)
            require(mono.isNotEmpty()) { "Decoded audio contains no samples." }
            val durationMs = ((mono.size.toDouble() / outputSampleRate.toDouble()) * 1000.0).toLong()

            return DecodedAudio(
                monoSamples = mono,
                sampleRateHz = outputSampleRate,
                channelCount = outputChannels,
                durationMs = durationMs
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    fun decodeToStereoFloat(context: Context, uri: Uri): DecodedStereoAudio {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = findAudioTrack(extractor)
            require(trackIndex >= 0) { "No audio track found in selected file." }
            extractor.selectTrack(trackIndex)

            val trackFormat = extractor.getTrackFormat(trackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalArgumentException("Unknown audio MIME type.")

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(trackFormat, null, null, 0)
            codec.start()

            var outputSampleRate = trackFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44100)
            var outputChannels = trackFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            val pcmBytes = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    sampleSize,
                                    extractor.sampleTime,
                                    0
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        outputSampleRate = newFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, outputSampleRate)
                        outputChannels = newFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, outputChannels)
                        pcmEncoding = newFormat.getIntegerOrDefault(
                            MediaFormat.KEY_PCM_ENCODING,
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                    }
                    outputIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val chunk = ByteArray(bufferInfo.size)
                            outputBuffer.get(chunk)
                            pcmBytes.write(chunk)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            val raw = pcmBytes.toByteArray()
            val channels = outputChannels.coerceAtLeast(1)
            val (left, right) = pcmBytesToStereoFloat(raw, channels, pcmEncoding)
            require(left.isNotEmpty()) { "Decoded audio contains no samples." }
            val durationMs = ((left.size.toDouble() / outputSampleRate.toDouble()) * 1000.0).toLong()

            return DecodedStereoAudio(
                leftSamples = left,
                rightSamples = right,
                sampleRateHz = outputSampleRate,
                channelCount = outputChannels,
                durationMs = durationMs
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    fun resampleLinear(samples: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate <= 0 || toRate <= 0 || fromRate == toRate || samples.isEmpty()) {
            return samples
        }
        val ratio = fromRate.toDouble() / toRate.toDouble()
        val outLength = (samples.size / ratio).roundToInt().coerceAtLeast(1)
        val out = FloatArray(outLength)
        val lastIdx = samples.lastIndex
        for (i in 0 until outLength) {
            val srcPos = i * ratio
            val idx0 = srcPos.toInt().coerceIn(0, lastIdx)
            val idx1 = (idx0 + 1).coerceAtMost(lastIdx)
            val frac = (srcPos - idx0).toFloat()
            out[i] = samples[idx0] + (samples[idx1] - samples[idx0]) * frac
        }
        return out
    }

    fun writeWav16Bit(samples: FloatArray, sampleRateHz: Int, outputFile: File) {
        outputFile.parentFile?.mkdirs()
        val numChannels = 1
        val bitsPerSample = 16
        val byteRate = sampleRateHz * numChannels * (bitsPerSample / 8)
        val blockAlign = numChannels * (bitsPerSample / 8)
        val dataSize = samples.size * 2
        val chunkSize = 36 + dataSize

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(chunkSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // PCM subchunk size
            putShort(1.toShort()) // AudioFormat = PCM
            putShort(numChannels.toShort())
            putInt(sampleRateHz)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }.array()

        val pcmBuffer = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            val clamped = sample.coerceIn(-1f, 1f)
            val int16 = (clamped * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
            pcmBuffer.putShort(int16)
        }

        FileOutputStream(outputFile).use { fos ->
            fos.write(header)
            fos.write(pcmBuffer.array())
        }
    }

    fun writeStereoWav16Bit(
        leftSamples: FloatArray,
        rightSamples: FloatArray,
        sampleRateHz: Int,
        outputFile: File
    ) {
        outputFile.parentFile?.mkdirs()
        val sampleCount = minOf(leftSamples.size, rightSamples.size)
        val numChannels = 2
        val bitsPerSample = 16
        val byteRate = sampleRateHz * numChannels * (bitsPerSample / 8)
        val blockAlign = numChannels * (bitsPerSample / 8)
        val dataSize = sampleCount * numChannels * 2
        val chunkSize = 36 + dataSize

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(chunkSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1.toShort())
            putShort(numChannels.toShort())
            putInt(sampleRateHz)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }.array()

        val pcmBuffer = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            val lInt = (leftSamples[i].coerceIn(-1f, 1f) * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
            val rInt = (rightSamples[i].coerceIn(-1f, 1f) * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
            pcmBuffer.putShort(lInt)
            pcmBuffer.putShort(rInt)
        }

        FileOutputStream(outputFile).use { fos ->
            fos.write(header)
            fos.write(pcmBuffer.array())
        }
    }

    fun copyFileToUri(context: Context, sourceFile: File, targetUri: Uri) {
        context.contentResolver.openOutputStream(targetUri)?.use { out ->
            sourceFile.inputStream().use { input ->
                input.copyTo(out)
            }
        } ?: throw IllegalStateException("Unable to open destination file for writing.")
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes < 0L) return "Unknown size"
        return when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    fun formatDurationMs(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                return i
            }
        }
        return -1
    }

    private fun pcmBytesToMonoFloat(raw: ByteArray, channels: Int, encoding: Int): FloatArray {
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        return when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val totalFloats = raw.size / 4
                val frameCount = totalFloats / channels
                FloatArray(frameCount) {
                    var sum = 0f
                    for (c in 0 until channels) {
                        sum += bb.float
                    }
                    (sum / channels).coerceIn(-1f, 1f)
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                val frameCount = raw.size / channels
                FloatArray(frameCount) { frame ->
                    var sum = 0f
                    val base = frame * channels
                    for (c in 0 until channels) {
                        val unsigned = raw[base + c].toInt() and 0xFF
                        sum += (unsigned - 128) / 128f
                    }
                    (sum / channels).coerceIn(-1f, 1f)
                }
            }
            else -> {
                val totalShorts = raw.size / 2
                val frameCount = totalShorts / channels
                FloatArray(frameCount) {
                    var sum = 0f
                    for (c in 0 until channels) {
                        sum += bb.short / 32768f
                    }
                    (sum / channels).coerceIn(-1f, 1f)
                }
            }
        }
    }

    private fun pcmBytesToStereoFloat(
        raw: ByteArray,
        channels: Int,
        encoding: Int
    ): Pair<FloatArray, FloatArray> {
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        return when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val frameCount = (raw.size / 4) / channels
                val left = FloatArray(frameCount)
                val right = FloatArray(frameCount)
                for (f in 0 until frameCount) {
                    val l = bb.float.coerceIn(-1f, 1f)
                    var r = l
                    for (c in 1 until channels) {
                        val s = bb.float.coerceIn(-1f, 1f)
                        if (c == 1) r = s
                    }
                    left[f] = l
                    right[f] = r
                }
                left to right
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                val frameCount = raw.size / channels
                val left = FloatArray(frameCount)
                val right = FloatArray(frameCount)
                for (f in 0 until frameCount) {
                    val base = f * channels
                    val l = (((raw[base].toInt() and 0xFF) - 128) / 128f).coerceIn(-1f, 1f)
                    val r = if (channels >= 2) {
                        (((raw[base + 1].toInt() and 0xFF) - 128) / 128f).coerceIn(-1f, 1f)
                    } else {
                        l
                    }
                    left[f] = l
                    right[f] = r
                }
                left to right
            }
            else -> {
                val frameCount = (raw.size / 2) / channels
                val left = FloatArray(frameCount)
                val right = FloatArray(frameCount)
                for (f in 0 until frameCount) {
                    val l = (bb.short / 32768f).coerceIn(-1f, 1f)
                    var r = l
                    for (c in 1 until channels) {
                        val s = (bb.short / 32768f).coerceIn(-1f, 1f)
                        if (c == 1) r = s
                    }
                    left[f] = l
                    right[f] = r
                }
                left to right
            }
        }
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int {
        return if (containsKey(key)) getInteger(key) else default
    }
}
