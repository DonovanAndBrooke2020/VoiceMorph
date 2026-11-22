package com.example.ui

import android.app.Application
import android.media.MediaPlayer
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.audio.AudioCodecHelper
import com.example.audio.SpectralAudioSeparator
import com.example.model.ConversionState
import com.example.model.SeparatedStemType
import com.example.model.SeparationState
import com.example.model.VoiceMorphUiState
import com.example.onnx.OnnxVoiceConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class VoiceMorphViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(VoiceMorphUiState())
    val uiState: StateFlow<VoiceMorphUiState> = _uiState.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null
    private var activePlayerKey: String? = null

    fun onAudioPicked(uri: Uri) {
        stopPlayback()
        _uiState.update {
            it.copy(
                isInspectingAudio = true,
                audioError = null,
                conversionState = ConversionState.Idle
            )
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AudioCodecHelper.inspectAudioFile(getApplication(), uri) }
            }
            result.fold(
                onSuccess = { info ->
                    _uiState.update {
                        it.copy(
                            selectedAudio = info,
                            isInspectingAudio = false,
                            audioError = null
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            selectedAudio = null,
                            isInspectingAudio = false,
                            audioError = error.localizedMessage
                                ?: getApplication<Application>().getString(R.string.error_audio_decode)
                        )
                    }
                }
            )
        }
    }

    fun onModelPicked(uri: Uri) {
        stopPlayback()
        _uiState.update {
            it.copy(
                isInspectingModel = true,
                modelError = null,
                conversionState = ConversionState.Idle
            )
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { OnnxVoiceConverter.inspectAndCacheModel(getApplication(), uri) }
            }
            result.fold(
                onSuccess = { modelInfo ->
                    _uiState.update {
                        it.copy(
                            selectedModel = modelInfo,
                            isInspectingModel = false,
                            modelError = null
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            selectedModel = null,
                            isInspectingModel = false,
                            modelError = error.localizedMessage
                                ?: getApplication<Application>().getString(R.string.error_invalid_onnx)
                        )
                    }
                }
            )
        }
    }

    fun clearAudio() {
        stopPlayback()
        _uiState.update {
            it.copy(
                selectedAudio = null,
                audioError = null,
                conversionState = ConversionState.Idle
            )
        }
    }

    fun clearModel() {
        stopPlayback()
        _uiState.update {
            it.copy(
                selectedModel = null,
                modelError = null,
                conversionState = ConversionState.Idle
            )
        }
    }

    fun convert() {
        val current = _uiState.value
        val audio = current.selectedAudio ?: return
        val model = current.selectedModel ?: return
        if (!current.canConvert) return

        stopPlayback()
        _uiState.update {
            it.copy(conversionState = ConversionState.Processing("Starting conversion…"))
        }

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    OnnxVoiceConverter.convertAudio(
                        context = getApplication(),
                        modelInfo = model,
                        audioUri = audio.uri,
                        onStageChanged = { stage ->
                            _uiState.update { state ->
                                state.copy(conversionState = ConversionState.Processing(stage))
                            }
                        }
                    )
                }
            }
            result.fold(
                onSuccess = { conversionResult ->
                    _uiState.update {
                        it.copy(conversionState = ConversionState.Success(conversionResult))
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            conversionState = ConversionState.Error(
                                error.localizedMessage ?: "ONNX voice conversion failed."
                            )
                        )
                    }
                }
            )
        }
    }

    fun onSeparationAudioPicked(uri: Uri) {
        stopPlayback()
        _uiState.update {
            it.copy(
                isInspectingSeparationAudio = true,
                separationAudioError = null,
                separationState = SeparationState.Idle
            )
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AudioCodecHelper.inspectAudioFile(getApplication(), uri) }
            }
            result.fold(
                onSuccess = { info ->
                    _uiState.update {
                        it.copy(
                            separationAudio = info,
                            isInspectingSeparationAudio = false,
                            separationAudioError = null
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            separationAudio = null,
                            isInspectingSeparationAudio = false,
                            separationAudioError = error.localizedMessage
                                ?: getApplication<Application>().getString(R.string.error_audio_decode)
                        )
                    }
                }
            )
        }
    }

    fun clearSeparationAudio() {
        stopPlayback()
        _uiState.update {
            it.copy(
                separationAudio = null,
                separationAudioError = null,
                separationState = SeparationState.Idle
            )
        }
    }

    fun separateVocalsAndMusic() {
        val current = _uiState.value
        val audio = current.separationAudio ?: return
        if (!current.canSeparate) return

        stopPlayback()
        _uiState.update {
            it.copy(separationState = SeparationState.Processing("Starting separation…"))
        }

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    SpectralAudioSeparator.separateVocalsAndMusic(
                        context = getApplication(),
                        audioUri = audio.uri,
                        onStageChanged = { stage ->
                            _uiState.update { state ->
                                state.copy(separationState = SeparationState.Processing(stage))
                            }
                        }
                    )
                }
            }
            result.fold(
                onSuccess = { sepResult ->
                    _uiState.update {
                        it.copy(separationState = SeparationState.Success(sepResult))
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            separationState = SeparationState.Error(
                                error.localizedMessage ?: "Vocal & music separation failed."
                            )
                        )
                    }
                }
            )
        }
    }

    fun togglePreviewPlayback() {
        val successState = _uiState.value.conversionState as? ConversionState.Success ?: return
        toggleAudioPlayback(
            playerKey = "CONVERTED",
            filePath = successState.result.outputFilePath,
            stemType = null
        )
    }

    fun toggleStemPlayback(stemType: SeparatedStemType) {
        val successState = _uiState.value.separationState as? SeparationState.Success ?: return
        val filePath = when (stemType) {
            SeparatedStemType.VOCALS -> successState.result.vocalFilePath
            SeparatedStemType.MUSIC -> successState.result.musicFilePath
        }
        toggleAudioPlayback(
            playerKey = "STEM_${stemType.name}",
            filePath = filePath,
            stemType = stemType
        )
    }

    private fun toggleAudioPlayback(
        playerKey: String,
        filePath: String,
        stemType: SeparatedStemType?
    ) {
        val existingPlayer = mediaPlayer
        if (existingPlayer != null && activePlayerKey == playerKey) {
            if (existingPlayer.isPlaying) {
                existingPlayer.pause()
                progressJob?.cancel()
                _uiState.update {
                    if (stemType == null) {
                        it.copy(isPlayingPreview = false)
                    } else {
                        it.copy(playingStem = null)
                    }
                }
            } else {
                existingPlayer.start()
                _uiState.update {
                    if (stemType == null) {
                        it.copy(isPlayingPreview = true, playingStem = null)
                    } else {
                        it.copy(isPlayingPreview = false, playingStem = stemType)
                    }
                }
                startProgressTicker(isStem = stemType != null)
            }
            return
        }

        stopPlayback()
        val file = File(filePath)
        if (!file.exists()) return

        runCatching {
            val player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    progressJob?.cancel()
                    _uiState.update {
                        it.copy(
                            isPlayingPreview = false,
                            playbackProgress = 0f,
                            playingStem = null,
                            stemPlaybackProgress = 0f
                        )
                    }
                    runCatching { seekTo(0) }
                }
                prepare()
                start()
            }
            mediaPlayer = player
            activePlayerKey = playerKey
            _uiState.update {
                if (stemType == null) {
                    it.copy(isPlayingPreview = true, playbackProgress = 0f, playingStem = null)
                } else {
                    it.copy(isPlayingPreview = false, playingStem = stemType, stemPlaybackProgress = 0f)
                }
            }
            startProgressTicker(isStem = stemType != null)
        }.onFailure {
            stopPlayback()
        }
    }

    fun exportConvertedAudio(targetUri: Uri, onComplete: (Boolean, String) -> Unit) {
        val successState = _uiState.value.conversionState as? ConversionState.Success ?: return
        exportFileToUri(
            sourceFile = File(successState.result.outputFilePath),
            targetUri = targetUri,
            successMessage = getApplication<Application>().getString(R.string.saved_wav_success),
            onComplete = onComplete
        )
    }

    fun exportSeparatedStem(
        stemType: SeparatedStemType,
        targetUri: Uri,
        onComplete: (Boolean, String) -> Unit
    ) {
        val successState = _uiState.value.separationState as? SeparationState.Success ?: return
        val sourcePath = when (stemType) {
            SeparatedStemType.VOCALS -> successState.result.vocalFilePath
            SeparatedStemType.MUSIC -> successState.result.musicFilePath
        }
        val successMsg = when (stemType) {
            SeparatedStemType.VOCALS -> getApplication<Application>().getString(R.string.saved_vocals_success)
            SeparatedStemType.MUSIC -> getApplication<Application>().getString(R.string.saved_music_success)
        }
        exportFileToUri(
            sourceFile = File(sourcePath),
            targetUri = targetUri,
            successMessage = successMsg,
            onComplete = onComplete
        )
    }

    private fun exportFileToUri(
        sourceFile: File,
        targetUri: Uri,
        successMessage: String,
        onComplete: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    AudioCodecHelper.copyFileToUri(getApplication(), sourceFile, targetUri)
                }
            }
            result.fold(
                onSuccess = { onComplete(true, successMessage) },
                onFailure = { err ->
                    onComplete(
                        false,
                        err.localizedMessage
                            ?: getApplication<Application>().getString(R.string.error_save_failed)
                    )
                }
            )
        }
    }

    private fun startProgressTicker(isStem: Boolean) {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (isActive) {
                val player = mediaPlayer ?: break
                val duration = player.duration.coerceAtLeast(1)
                val pos = player.currentPosition.coerceIn(0, duration)
                val progress = pos.toFloat() / duration.toFloat()
                _uiState.update {
                    if (isStem) {
                        it.copy(stemPlaybackProgress = progress)
                    } else {
                        it.copy(playbackProgress = progress)
                    }
                }
                if (!player.isPlaying) break
                delay(60L)
            }
        }
    }

    private fun stopPlayback() {
        progressJob?.cancel()
        progressJob = null
        activePlayerKey = null
        mediaPlayer?.let { player ->
            runCatching {
                if (player.isPlaying) player.stop()
                player.release()
            }
        }
        mediaPlayer = null
        _uiState.update {
            it.copy(
                isPlayingPreview = false,
                playbackProgress = 0f,
                playingStem = null,
                stemPlaybackProgress = 0f
            )
        }
    }

    override fun onCleared() {
        stopPlayback()
        super.onCleared()
    }
}
