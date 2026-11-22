package com.example.audio

import android.content.Context
import android.net.Uri
import com.example.model.SeparationResult
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object SpectralAudioSeparator {

    private const val FFT_SIZE = 2048
    private const val HOP_SIZE = 512

    fun separateVocalsAndMusic(
        context: Context,
        audioUri: Uri,
        onStageChanged: (String) -> Unit
    ): SeparationResult {
        val startNs = System.nanoTime()
        onStageChanged("Decoding audio track…")
        val decoded = AudioCodecHelper.decodeToStereoFloat(context, audioUri)
        val left = decoded.leftSamples
        val right = decoded.rightSamples
        val sampleRate = decoded.sampleRateHz
        val length = left.size

        require(length > 0) { "Decoded audio contains no samples." }

        val isTrueStereo = decoded.channelCount >= 2 && hasStereoDifference(left, right)
        onStageChanged(
            if (isTrueStereo) {
                "Separating center vocals & stereo music…"
            } else {
                "Separating vocal & instrumental bands…"
            }
        )

        val stems = separateStems(
            left = left,
            right = right,
            sampleRate = sampleRate,
            isTrueStereo = isTrueStereo
        )

        onStageChanged("Saving separated WAV stems…")
        val outputDir = File(context.cacheDir, "separated_audio").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        val stamp = System.currentTimeMillis()
        val vocalFileName = "vocals_$stamp.wav"
        val musicFileName = "music_$stamp.wav"
        val vocalFile = File(outputDir, vocalFileName)
        val musicFile = File(outputDir, musicFileName)

        AudioCodecHelper.writeWav16Bit(stems.vocals, sampleRate, vocalFile)
        AudioCodecHelper.writeStereoWav16Bit(stems.musicLeft, stems.musicRight, sampleRate, musicFile)

        val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
        val durationMs = ((length.toDouble() / sampleRate.toDouble()) * 1000.0).toLong()

        return SeparationResult(
            vocalFilePath = vocalFile.absolutePath,
            vocalFileName = vocalFileName,
            musicFilePath = musicFile.absolutePath,
            musicFileName = musicFileName,
            sampleRateHz = sampleRate,
            durationMs = durationMs,
            processingTimeMs = elapsedMs,
            usedStereoSeparation = isTrueStereo
        )
    }

    data class SeparatedStems(
        val vocals: FloatArray,
        val musicLeft: FloatArray,
        val musicRight: FloatArray
    )

    fun separateStems(
        left: FloatArray,
        right: FloatArray,
        sampleRate: Int,
        isTrueStereo: Boolean = hasStereoDifference(left, right)
    ): SeparatedStems {
        val length = minOf(left.size, right.size)
        val window = createHannWindow(FFT_SIZE)
        val vocalOut = FloatArray(length)
        val musicLeftOut = FloatArray(length)
        val musicRightOut = FloatArray(length)
        val windowNorm = FloatArray(length)

        val halfBins = FFT_SIZE / 2
        val lReal = FloatArray(FFT_SIZE)
        val lImag = FloatArray(FFT_SIZE)
        val rReal = FloatArray(FFT_SIZE)
        val rImag = FloatArray(FFT_SIZE)

        val vReal = FloatArray(FFT_SIZE)
        val vImag = FloatArray(FFT_SIZE)
        val mlReal = FloatArray(FFT_SIZE)
        val mlImag = FloatArray(FFT_SIZE)
        val mrReal = FloatArray(FFT_SIZE)
        val mrImag = FloatArray(FFT_SIZE)

        val magBuffer = FloatArray(halfBins + 1)

        var frameStart = 0
        while (frameStart < length) {
            for (i in 0 until FFT_SIZE) {
                val idx = frameStart + i
                val w = window[i]
                if (idx < length) {
                    lReal[i] = left[idx] * w
                    rReal[i] = right[idx] * w
                } else {
                    lReal[i] = 0f
                    rReal[i] = 0f
                }
                lImag[i] = 0f
                rImag[i] = 0f
            }

            fft(lReal, lImag, inverse = false)
            if (isTrueStereo) {
                fft(rReal, rImag, inverse = false)
            } else {
                lReal.copyInto(rReal)
                lImag.copyInto(rImag)
            }

            if (!isTrueStereo) {
                for (k in 0..halfBins) {
                    magBuffer[k] = sqrt(lReal[k] * lReal[k] + lImag[k] * lImag[k])
                }
            }

            for (k in 0..halfBins) {
                val freqHz = (k.toFloat() * sampleRate.toFloat()) / FFT_SIZE.toFloat()
                val freqWeight = computeVocalFreqWeight(freqHz)

                val lr = lReal[k]
                val li = lImag[k]
                val rr = rReal[k]
                val ri = rImag[k]

                val mr = 0.5f * (lr + rr)
                val mi = 0.5f * (li + ri)

                val vocalMask = if (isTrueStereo) {
                    val sr = 0.5f * (lr - rr)
                    val si = 0.5f * (li - ri)
                    val midPower = mr * mr + mi * mi
                    val sidePower = sr * sr + si * si
                    val centerDominance = midPower / (midPower + 1.8f * sidePower + 1e-12f)
                    (freqWeight * centerDominance.pow(1.5f)).coerceIn(0f, 1f)
                } else {
                    val mag = magBuffer[k]
                    val localAvg = computeLocalAverage(magBuffer, k, radius = 8)
                    val harmonicRatio = mag / (mag + localAvg * 1.15f + 1e-9f)
                    (freqWeight * (0.35f + 0.65f * harmonicRatio)).coerceIn(0f, 1f)
                }

                val vr = mr * vocalMask
                val vi = mi * vocalMask

                vReal[k] = vr
                vImag[k] = vi
                mlReal[k] = lr - vr
                mlImag[k] = li - vi
                mrReal[k] = rr - vr
                mrImag[k] = ri - vi

                if (k in 1 until halfBins) {
                    val mirror = FFT_SIZE - k
                    vReal[mirror] = vr
                    vImag[mirror] = -vi
                    mlReal[mirror] = lr - vr
                    mlImag[mirror] = -(li - vi)
                    mrReal[mirror] = rr - vr
                    mrImag[mirror] = -(ri - vi)
                }
            }

            fft(vReal, vImag, inverse = true)
            fft(mlReal, mlImag, inverse = true)
            fft(mrReal, mrImag, inverse = true)

            for (i in 0 until FFT_SIZE) {
                val idx = frameStart + i
                if (idx >= length) break
                val w = window[i]
                vocalOut[idx] += vReal[i] * w
                musicLeftOut[idx] += mlReal[i] * w
                musicRightOut[idx] += mrReal[i] * w
                windowNorm[idx] += w * w
            }

            frameStart += HOP_SIZE
        }

        for (i in 0 until length) {
            val norm = max(windowNorm[i], 1e-6f)
            vocalOut[i] = (vocalOut[i] / norm).coerceIn(-1f, 1f)
            musicLeftOut[i] = (musicLeftOut[i] / norm).coerceIn(-1f, 1f)
            musicRightOut[i] = (musicRightOut[i] / norm).coerceIn(-1f, 1f)
        }

        return SeparatedStems(
            vocals = vocalOut,
            musicLeft = musicLeftOut,
            musicRight = musicRightOut
        )
    }

    private fun hasStereoDifference(left: FloatArray, right: FloatArray): Boolean {
        val sampleStep = max(1, left.size / 4096)
        var diffSqSum = 0.0
        var count = 0
        var i = 0
        while (i < left.size) {
            val d = (left[i] - right[i]).toDouble()
            diffSqSum += d * d
            count++
            i += sampleStep
        }
        if (count == 0) return false
        val rmsDiff = sqrt(diffSqSum / count)
        return rmsDiff > 1e-4
    }

    private fun computeVocalFreqWeight(freqHz: Float): Float {
        return when {
            freqHz < 110f -> 0f
            freqHz < 230f -> {
                val t = (freqHz - 110f) / 120f
                0.5f * (1f - cos(PI.toFloat() * t))
            }
            freqHz <= 5500f -> 1f
            freqHz < 9000f -> {
                val t = (freqHz - 5500f) / 3500f
                0.5f * (1f + cos(PI.toFloat() * t))
            }
            else -> 0f
        }
    }

    private fun computeLocalAverage(magnitudes: FloatArray, index: Int, radius: Int): Float {
        val start = (index - radius).coerceAtLeast(0)
        val end = (index + radius).coerceAtMost(magnitudes.lastIndex)
        var sum = 0f
        var count = 0
        for (i in start..end) {
            if (i != index) {
                sum += magnitudes[i]
                count++
            }
        }
        return if (count > 0) sum / count else magnitudes[index]
    }

    private fun createHannWindow(size: Int): FloatArray {
        return FloatArray(size) { n ->
            (0.5 * (1.0 - cos((2.0 * PI * n) / size))).toFloat()
        }
    }

    private fun fft(real: FloatArray, imag: FloatArray, inverse: Boolean) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]
                val ti = imag[i]
                real[i] = real[j]
                imag[i] = imag[j]
                real[j] = tr
                imag[j] = ti
            }
        }

        val angleSign = if (inverse) 1.0 else -1.0
        var len = 2
        while (len <= n) {
            val halfLen = len shr 1
            val theta = angleSign * (2.0 * PI / len)
            val wStepReal = cos(theta).toFloat()
            val wStepImag = sin(theta).toFloat()

            var start = 0
            while (start < n) {
                var wReal = 1f
                var wImag = 0f
                for (k in 0 until halfLen) {
                    val even = start + k
                    val odd = even + halfLen
                    val oddR = real[odd] * wReal - imag[odd] * wImag
                    val oddI = real[odd] * wImag + imag[odd] * wReal

                    real[odd] = real[even] - oddR
                    imag[odd] = imag[even] - oddI
                    real[even] += oddR
                    imag[even] += oddI

                    val nextWReal = wReal * wStepReal - wImag * wStepImag
                    val nextWImag = wReal * wStepImag + wImag * wStepReal
                    wReal = nextWReal
                    wImag = nextWImag
                }
                start += len
            }
            len = len shl 1
        }

        if (inverse) {
            val invN = 1f / n.toFloat()
            for (i in 0 until n) {
                real[i] *= invN
                imag[i] *= invN
            }
        }
    }
}
