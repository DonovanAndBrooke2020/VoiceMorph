package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioCodecHelper
import com.example.audio.SpectralAudioSeparator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

import androidx.compose.ui.test.performScrollTo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appNameMatchesVoiceMorph() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("VoiceMorph", appName)
    }

    @Test
    fun mainScreenShowsThreePrimaryActionButtons() {
        composeRule.onNodeWithTag("pick_audio_button")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("pick_model_button")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("convert_button")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun separationSectionShowsPickerAndSeparateButton() {
        composeRule.onNodeWithTag("tab_audio_separation")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("pick_separation_audio_button")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("separate_button")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun wavWriterProducesValidRiffHeader() {
        val tempFile = File.createTempFile("test_out", ".wav")
        try {
            val samples = floatArrayOf(0f, 0.5f, -0.5f, 1.0f, -1.0f)
            AudioCodecHelper.writeWav16Bit(samples, 16_000, tempFile)
            val bytes = tempFile.readBytes()
            assertEquals(44 + samples.size * 2, bytes.size)
            assertEquals("RIFF", String(bytes.copyOfRange(0, 4), Charsets.US_ASCII))
            assertEquals("WAVE", String(bytes.copyOfRange(8, 12), Charsets.US_ASCII))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun resampleLinearScalesSampleCount() {
        val input = FloatArray(48_000) { 0.25f }
        val resampled = AudioCodecHelper.resampleLinear(input, 48_000, 16_000)
        assertTrue(resampled.size in 15_999..16_001)
    }

    @Test
    fun spectralSeparatorSplitsCenterVocalsFromStereoBassAndSide() {
        val sampleRate = 44_100
        val count = 8192
        val left = FloatArray(count)
        val right = FloatArray(count)
        for (i in 0 until count) {
            val t = i.toDouble() / sampleRate
            val centerVocal = (0.4 * sin(2.0 * PI * 1000.0 * t)).toFloat()
            val sideInstrument = (0.4 * sin(2.0 * PI * 600.0 * t)).toFloat()
            left[i] = centerVocal + sideInstrument
            right[i] = centerVocal - sideInstrument
        }

        val stems = SpectralAudioSeparator.separateStems(left, right, sampleRate, isTrueStereo = true)
        assertEquals(count, stems.vocals.size)
        assertEquals(count, stems.musicLeft.size)
        assertEquals(count, stems.musicRight.size)

        // Verify vocals stem retains energy and stereo music stem retains side energy
        val vocalAvgAbs = stems.vocals.drop(1024).take(4096).map { abs(it) }.average()
        val musicSideAvgAbs = (1024 until 5120).map { abs(stems.musicLeft[it] - stems.musicRight[it]) }.average()
        assertTrue("Expected vocal stem energy, got $vocalAvgAbs", vocalAvgAbs > 0.15)
        assertTrue("Expected stereo music side energy, got $musicSideAvgAbs", musicSideAvgAbs > 0.35)
    }
}
