package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.audio.AudioCodecHelper
import com.example.model.AudioFileInfo
import com.example.model.ConversionState
import com.example.model.OnnxModelInfo
import com.example.model.SeparatedStemType
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

enum class StudioTab {
    VOICE_CONVERT,
    AUDIO_SEPARATION
}

@Composable
fun VoiceMorphScreen(
    viewModel: VoiceMorphViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableStateOf(StudioTab.VOICE_CONVERT) }

    BackHandler(enabled = selectedTab == StudioTab.AUDIO_SEPARATION) {
        selectedTab = StudioTab.VOICE_CONVERT
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.onAudioPicked(it) }
    }

    val modelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.onModelPicked(it) }
    }

    val saveWavLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/wav")
    ) { uri ->
        uri?.let { targetUri ->
            viewModel.exportConvertedAudio(targetUri) { _, message ->
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(message)
                }
            }
        }
    }

    val separationAudioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.onSeparationAudioPicked(it) }
    }

    val saveVocalsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/wav")
    ) { uri ->
        uri?.let { targetUri ->
            viewModel.exportSeparatedStem(SeparatedStemType.VOCALS, targetUri) { _, message ->
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(message)
                }
            }
        }
    }

    val saveMusicLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/wav")
    ) { uri ->
        uri?.let { targetUri ->
            viewModel.exportSeparatedStem(SeparatedStemType.MUSIC, targetUri) { _, message ->
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(message)
                }
            }
        }
    }

    val colorScheme = MaterialTheme.colorScheme
    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(
            colorScheme.background,
            colorScheme.surfaceVariant.copy(alpha = 0.28f),
            colorScheme.background
        )
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
    ) {
        val isWideLayout = maxWidth >= 800.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            containerColor = Color.Transparent,
            bottomBar = {
                if (!isWideLayout) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
                    ) {
                        NavigationBarItem(
                            selected = selectedTab == StudioTab.VOICE_CONVERT,
                            onClick = { selectedTab = StudioTab.VOICE_CONVERT },
                            icon = {
                                Icon(
                                    imageVector = if (selectedTab == StudioTab.VOICE_CONVERT) {
                                        Icons.Filled.AutoAwesome
                                    } else {
                                        Icons.Outlined.AutoAwesome
                                    },
                                    contentDescription = null
                                )
                            },
                            label = { Text(stringResource(R.string.tab_voice_convert)) },
                            modifier = Modifier.testTag("tab_voice_convert")
                        )
                        NavigationBarItem(
                            selected = selectedTab == StudioTab.AUDIO_SEPARATION,
                            onClick = { selectedTab = StudioTab.AUDIO_SEPARATION },
                            icon = {
                                Icon(
                                    imageVector = if (selectedTab == StudioTab.AUDIO_SEPARATION) {
                                        Icons.Filled.GraphicEq
                                    } else {
                                        Icons.Outlined.GraphicEq
                                    },
                                    contentDescription = null
                                )
                            },
                            label = { Text(stringResource(R.string.tab_audio_separation)) },
                            modifier = Modifier.testTag("tab_audio_separation")
                        )
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isWideLayout) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                    ) {
                        Spacer(modifier = Modifier.height(16.dp))
                        NavigationRailItem(
                            selected = selectedTab == StudioTab.VOICE_CONVERT,
                            onClick = { selectedTab = StudioTab.VOICE_CONVERT },
                            icon = {
                                Icon(
                                    imageVector = if (selectedTab == StudioTab.VOICE_CONVERT) {
                                        Icons.Filled.AutoAwesome
                                    } else {
                                        Icons.Outlined.AutoAwesome
                                    },
                                    contentDescription = null
                                )
                            },
                            label = { Text(stringResource(R.string.tab_voice_convert)) },
                            modifier = Modifier.testTag("tab_voice_convert")
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        NavigationRailItem(
                            selected = selectedTab == StudioTab.AUDIO_SEPARATION,
                            onClick = { selectedTab = StudioTab.AUDIO_SEPARATION },
                            icon = {
                                Icon(
                                    imageVector = if (selectedTab == StudioTab.AUDIO_SEPARATION) {
                                        Icons.Filled.GraphicEq
                                    } else {
                                        Icons.Outlined.GraphicEq
                                    },
                                    contentDescription = null
                                )
                            },
                            label = { Text(stringResource(R.string.tab_audio_separation)) },
                            modifier = Modifier.testTag("tab_audio_separation")
                        )
                    }
                }

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 560.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        StudioHeader()

                        Crossfade(targetState = selectedTab, label = "tab_content") { tab ->
                            when (tab) {
                                StudioTab.VOICE_CONVERT -> {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(14.dp)
                                    ) {
                                        AudioPickerCard(
                                            audioInfo = uiState.selectedAudio,
                                            isLoading = uiState.isInspectingAudio,
                                            errorMessage = uiState.audioError,
                                            enabled = uiState.conversionState !is ConversionState.Processing,
                                            onPickClick = { audioPickerLauncher.launch(arrayOf("audio/*")) },
                                            onClearClick = { viewModel.clearAudio() }
                                        )

                                        ModelPickerCard(
                                            modelInfo = uiState.selectedModel,
                                            isLoading = uiState.isInspectingModel,
                                            errorMessage = uiState.modelError,
                                            enabled = uiState.conversionState !is ConversionState.Processing,
                                            onPickClick = { modelPickerLauncher.launch(arrayOf("*/*")) },
                                            onClearClick = { viewModel.clearModel() }
                                        )

                                        ConvertActionButton(
                                            enabled = uiState.canConvert,
                                            conversionState = uiState.conversionState,
                                            onClick = { viewModel.convert() }
                                        )

                                        AnimatedVisibility(
                                            visible = uiState.conversionState is ConversionState.Success ||
                                                uiState.conversionState is ConversionState.Error,
                                            enter = fadeIn(),
                                            exit = fadeOut()
                                        ) {
                                            when (val state = uiState.conversionState) {
                                                is ConversionState.Success -> {
                                                    ConversionSuccessCard(
                                                        result = state.result,
                                                        isPlaying = uiState.isPlayingPreview,
                                                        progress = uiState.playbackProgress,
                                                        onTogglePlay = { viewModel.togglePreviewPlayback() },
                                                        onSaveClick = { saveWavLauncher.launch(state.result.outputFileName) }
                                                    )
                                                }
                                                is ConversionState.Error -> {
                                                    ConversionErrorCard(message = state.message)
                                                }
                                                else -> Unit
                                            }
                                        }
                                    }
                                }

                                StudioTab.AUDIO_SEPARATION -> {
                                    VocalSeparationSection(
                                        selectedAudio = uiState.separationAudio,
                                        isInspecting = uiState.isInspectingSeparationAudio,
                                        audioError = uiState.separationAudioError,
                                        separationState = uiState.separationState,
                                        canSeparate = uiState.canSeparate,
                                        playingStem = uiState.playingStem,
                                        stemPlaybackProgress = uiState.stemPlaybackProgress,
                                        onPickTrackClick = { separationAudioPickerLauncher.launch(arrayOf("audio/*")) },
                                        onClearTrackClick = { viewModel.clearSeparationAudio() },
                                        onSeparateClick = { viewModel.separateVocalsAndMusic() },
                                        onToggleStemPlay = { stem -> viewModel.toggleStemPlayback(stem) },
                                        onDownloadVocalsClick = { res -> saveVocalsLauncher.launch(res.vocalFileName) },
                                        onDownloadMusicClick = { res -> saveMusicLauncher.launch(res.musicFileName) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioHeader() {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            modifier = Modifier.size(52.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            border = BorderStroke(1.dp, primary.copy(alpha = 0.3f))
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp)
            ) {
                val midY = size.height / 2f
                val amp = size.height * 0.34f
                val path1 = Path()
                val path2 = Path()
                val steps = 36
                for (i in 0..steps) {
                    val t = i.toFloat() / steps.toFloat()
                    val x = t * size.width
                    val envelope = sin(t * PI).toFloat()
                    val y1 = midY - sin(t * 2.5f * PI).toFloat() * amp * envelope
                    val y2 = midY + sin(t * 2.5f * PI + 0.8f).toFloat() * (amp * 0.72f) * envelope
                    if (i == 0) {
                        path1.moveTo(x, y1)
                        path2.moveTo(x, y2)
                    } else {
                        path1.lineTo(x, y1)
                        path2.lineTo(x, y2)
                    }
                }
                drawPath(
                    path = path2,
                    color = secondary,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                )
                drawPath(
                    path = path1,
                    color = primary,
                    style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AudioPickerCard(
    audioInfo: AudioFileInfo?,
    isLoading: Boolean,
    errorMessage: String?,
    enabled: Boolean,
    onPickClick: () -> Unit,
    onClearClick: () -> Unit
) {
    PickerStepCard(
        stepLabel = stringResource(R.string.section_audio_label),
        icon = Icons.Filled.AudioFile,
        accentColor = MaterialTheme.colorScheme.primary,
        isSelected = audioInfo != null,
        isLoading = isLoading,
        errorMessage = errorMessage,
        buttonLabel = if (audioInfo == null) {
            stringResource(R.string.pick_audio_button)
        } else {
            stringResource(R.string.change_audio_button)
        },
        buttonTestTag = "pick_audio_button",
        clearTestTag = "clear_audio_button",
        enabled = enabled,
        onPickClick = onPickClick,
        onClearClick = onClearClick
    ) {
        if (audioInfo != null) {
            Text(
                text = audioInfo.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val metaLine = buildString {
                append(audioInfo.formatLabel)
                append(" · ")
                append(AudioCodecHelper.formatDurationMs(audioInfo.durationMs))
                append(" · ")
                append("${audioInfo.sampleRateHz / 1000.0} kHz")
                if (audioInfo.sizeBytes > 0) {
                    append(" · ")
                    append(AudioCodecHelper.formatFileSize(audioInfo.sizeBytes))
                }
            }
            Text(
                text = metaLine,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Text(
                text = stringResource(R.string.audio_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ModelPickerCard(
    modelInfo: OnnxModelInfo?,
    isLoading: Boolean,
    errorMessage: String?,
    enabled: Boolean,
    onPickClick: () -> Unit,
    onClearClick: () -> Unit
) {
    PickerStepCard(
        stepLabel = stringResource(R.string.section_model_label),
        icon = Icons.Filled.Memory,
        accentColor = MaterialTheme.colorScheme.secondary,
        isSelected = modelInfo != null,
        isLoading = isLoading,
        errorMessage = errorMessage,
        buttonLabel = if (modelInfo == null) {
            stringResource(R.string.pick_model_button)
        } else {
            stringResource(R.string.change_model_button)
        },
        buttonTestTag = "pick_model_button",
        clearTestTag = "clear_model_button",
        enabled = enabled,
        onPickClick = onPickClick,
        onClearClick = onClearClick
    ) {
        if (modelInfo != null) {
            Text(
                text = modelInfo.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val inputPreview = modelInfo.inputs.firstOrNull()?.formattedShape() ?: "unknown"
            val outputPreview = modelInfo.outputs.firstOrNull()?.formattedShape() ?: "unknown"
            Text(
                text = "${AudioCodecHelper.formatFileSize(modelInfo.sizeBytes)} · $inputPreview → $outputPreview",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            Text(
                text = stringResource(R.string.model_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PickerStepCard(
    stepLabel: String,
    icon: ImageVector,
    accentColor: Color,
    isSelected: Boolean,
    isLoading: Boolean,
    errorMessage: String?,
    buttonLabel: String,
    buttonTestTag: String,
    clearTestTag: String,
    enabled: Boolean,
    onPickClick: () -> Unit,
    onClearClick: () -> Unit,
    bodyContent: @Composable () -> Unit
) {
    val borderColor = when {
        errorMessage != null -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
        isSelected -> accentColor.copy(alpha = 0.55f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.2.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                color = accentColor.copy(alpha = 0.14f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        text = stepLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (isSelected && enabled) {
                    IconButton(
                        onClick = onClearClick,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag(clearTestTag)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.clear_selection_cd),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                bodyContent()
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            FilledTonalButton(
                onClick = onPickClick,
                enabled = enabled && !isLoading,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp)
                    .testTag(buttonTestTag)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = buttonLabel,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun ConvertActionButton(
    enabled: Boolean,
    conversionState: ConversionState,
    onClick: () -> Unit
) {
    val isProcessing = conversionState is ConversionState.Processing
    val stageText = (conversionState as? ConversionState.Processing)?.stage

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("convert_button")
        ) {
            if (isProcessing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.converting_button),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.convert_button),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (stageText != null) {
            Text(
                text = stageText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun ConversionSuccessCard(
    result: com.example.model.ConversionResult,
    isPlaying: Boolean,
    progress: Float,
    onTogglePlay: () -> Unit,
    onSaveClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column {
                    Text(
                        text = stringResource(R.string.output_ready_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${AudioCodecHelper.formatDurationMs(result.durationMs)} · ${result.sampleRateHz / 1000} kHz WAV · ${result.inferenceTimeMs} ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilledTonalButton(
                    onClick = onTogglePlay,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("play_output_button")
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isPlaying) {
                            stringResource(R.string.pause_preview)
                        } else {
                            stringResource(R.string.play_preview)
                        }
                    )
                }

                Button(
                    onClick = onSaveClick,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("save_output_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.save_wav))
                }
            }
        }
    }
}

@Composable
private fun ConversionErrorCard(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}
