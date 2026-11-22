package com.example.model

import android.net.Uri

data class AudioFileInfo(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val sampleRateHz: Int,
    val channelCount: Int,
    val durationMs: Long,
    val formatLabel: String
)

data class OnnxTensorSummary(
    val name: String,
    val dataType: String,
    val shape: List<Long>
) {
    fun formattedShape(): String {
        val dims = shape.joinToString("×") { if (it < 0) "?" else it.toString() }
        return "$name [$dims]"
    }
}

data class OnnxModelInfo(
    val uri: Uri,
    val cachedModelPath: String,
    val displayName: String,
    val sizeBytes: Long,
    val inputs: List<OnnxTensorSummary>,
    val outputs: List<OnnxTensorSummary>
)

data class ConversionResult(
    val outputFilePath: String,
    val outputFileName: String,
    val sampleRateHz: Int,
    val durationMs: Long,
    val inferenceTimeMs: Long
)

sealed interface ConversionState {
    data object Idle : ConversionState
    data class Processing(val stage: String) : ConversionState
    data class Success(val result: ConversionResult) : ConversionState
    data class Error(val message: String) : ConversionState
}

enum class SeparatedStemType {
    VOCALS,
    MUSIC
}

data class SeparationResult(
    val vocalFilePath: String,
    val vocalFileName: String,
    val musicFilePath: String,
    val musicFileName: String,
    val sampleRateHz: Int,
    val durationMs: Long,
    val processingTimeMs: Long,
    val usedStereoSeparation: Boolean
)

sealed interface SeparationState {
    data object Idle : SeparationState
    data class Processing(val stage: String) : SeparationState
    data class Success(val result: SeparationResult) : SeparationState
    data class Error(val message: String) : SeparationState
}

data class VoiceMorphUiState(
    val selectedAudio: AudioFileInfo? = null,
    val isInspectingAudio: Boolean = false,
    val audioError: String? = null,
    val selectedModel: OnnxModelInfo? = null,
    val isInspectingModel: Boolean = false,
    val modelError: String? = null,
    val conversionState: ConversionState = ConversionState.Idle,
    val isPlayingPreview: Boolean = false,
    val playbackProgress: Float = 0f,
    val separationAudio: AudioFileInfo? = null,
    val isInspectingSeparationAudio: Boolean = false,
    val separationAudioError: String? = null,
    val separationState: SeparationState = SeparationState.Idle,
    val playingStem: SeparatedStemType? = null,
    val stemPlaybackProgress: Float = 0f
) {
    val canConvert: Boolean
        get() = selectedAudio != null &&
            selectedModel != null &&
            !isInspectingAudio &&
            !isInspectingModel &&
            conversionState !is ConversionState.Processing

    val canSeparate: Boolean
        get() = separationAudio != null &&
            !isInspectingSeparationAudio &&
            separationState !is SeparationState.Processing
}
