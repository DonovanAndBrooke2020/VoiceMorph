package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.audio.AudioCodecHelper
import com.example.model.AudioFileInfo
import com.example.model.SeparatedStemType
import com.example.model.SeparationResult
import com.example.model.SeparationState

@Composable
fun VocalSeparationSection(
    selectedAudio: AudioFileInfo?,
    isInspecting: Boolean,
    audioError: String?,
    separationState: SeparationState,
    canSeparate: Boolean,
    playingStem: SeparatedStemType?,
    stemPlaybackProgress: Float,
    onPickTrackClick: () -> Unit,
    onClearTrackClick: () -> Unit,
    onSeparateClick: () -> Unit,
    onToggleStemPlay: (SeparatedStemType) -> Unit,
    onDownloadVocalsClick: (SeparationResult) -> Unit,
    onDownloadMusicClick: (SeparationResult) -> Unit,
    modifier: Modifier = Modifier
) {
    val isProcessing = separationState is SeparationState.Processing
    val stageText = (separationState as? SeparationState.Processing)?.stage
    val accent = MaterialTheme.colorScheme.tertiary

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.section_separation_heading),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = stringResource(R.string.section_separation_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        val borderColor = when {
            audioError != null -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
            selectedAudio != null -> accent.copy(alpha = 0.55f)
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
                                    color = accent.copy(alpha = 0.14f),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.GraphicEq,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            text = stringResource(R.string.section_audio_label),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (selectedAudio != null && !isProcessing) {
                        IconButton(
                            onClick = onClearTrackClick,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("clear_separation_audio_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.clear_selection_cd),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (selectedAudio != null) {
                    Text(
                        text = selectedAudio.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val channelLabel = if (selectedAudio.channelCount >= 2) "Stereo" else "Mono"
                    val metaLine = buildString {
                        append(selectedAudio.formatLabel)
                        append(" · ")
                        append(channelLabel)
                        append(" · ")
                        append(AudioCodecHelper.formatDurationMs(selectedAudio.durationMs))
                        append(" · ")
                        append("${selectedAudio.sampleRateHz / 1000.0} kHz")
                    }
                    Text(
                        text = metaLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = accent
                    )
                } else {
                    Text(
                        text = stringResource(R.string.separation_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (audioError != null) {
                    Text(
                        text = audioError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                FilledTonalButton(
                    onClick = onPickTrackClick,
                    enabled = !isProcessing && !isInspecting,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp)
                        .testTag("pick_separation_audio_button")
                ) {
                    if (isInspecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Filled.AudioFile,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = if (selectedAudio == null) {
                            stringResource(R.string.pick_separation_audio_button)
                        } else {
                            stringResource(R.string.change_separation_audio_button)
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        Button(
            onClick = onSeparateClick,
            enabled = canSeparate,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("separate_button")
        ) {
            if (isProcessing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.onSecondary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.separating_button),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.GraphicEq,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.separate_button),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (stageText != null) {
            Text(
                text = stageText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }

        AnimatedVisibility(
            visible = separationState is SeparationState.Success ||
                separationState is SeparationState.Error,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            when (separationState) {
                is SeparationState.Success -> {
                    SeparatedStemsCard(
                        result = separationState.result,
                        playingStem = playingStem,
                        progress = stemPlaybackProgress,
                        onTogglePlay = onToggleStemPlay,
                        onDownloadVocals = { onDownloadVocalsClick(separationState.result) },
                        onDownloadMusic = { onDownloadMusicClick(separationState.result) }
                    )
                }
                is SeparationState.Error -> {
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
                                text = separationState.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun SeparatedStemsCard(
    result: SeparationResult,
    playingStem: SeparatedStemType?,
    progress: Float,
    onTogglePlay: (SeparatedStemType) -> Unit,
    onDownloadVocals: () -> Unit,
    onDownloadMusic: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.32f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary
                )
                Column {
                    Text(
                        text = stringResource(R.string.separation_ready_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val modeText = if (result.usedStereoSeparation) "Stereo Mid-Side" else "Spectral"
                    Text(
                        text = "${AudioCodecHelper.formatDurationMs(result.durationMs)} · ${result.sampleRateHz / 1000} kHz WAV · $modeText · ${result.processingTimeMs} ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (playingStem != null) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp),
                    color = MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            StemActionRow(
                label = stringResource(R.string.vocals_stem_label),
                icon = Icons.Filled.RecordVoiceOver,
                accentColor = MaterialTheme.colorScheme.primary,
                isPlaying = playingStem == SeparatedStemType.VOCALS,
                playTag = "play_vocals_button",
                downloadLabel = stringResource(R.string.download_vocals),
                downloadTag = "download_vocals_button",
                onTogglePlay = { onTogglePlay(SeparatedStemType.VOCALS) },
                onDownloadClick = onDownloadVocals
            )

            StemActionRow(
                label = stringResource(R.string.music_stem_label),
                icon = Icons.Filled.MusicNote,
                accentColor = MaterialTheme.colorScheme.secondary,
                isPlaying = playingStem == SeparatedStemType.MUSIC,
                playTag = "play_music_button",
                downloadLabel = stringResource(R.string.download_music),
                downloadTag = "download_music_button",
                onTogglePlay = { onTogglePlay(SeparatedStemType.MUSIC) },
                onDownloadClick = onDownloadMusic
            )
        }
    }
}

@Composable
private fun StemActionRow(
    label: String,
    icon: ImageVector,
    accentColor: Color,
    isPlaying: Boolean,
    playTag: String,
    downloadLabel: String,
    downloadTag: String,
    onTogglePlay: () -> Unit,
    onDownloadClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onTogglePlay,
                modifier = Modifier
                    .size(48.dp)
                    .testTag(playTag)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) {
                        stringResource(R.string.pause_preview)
                    } else {
                        stringResource(R.string.play_preview)
                    },
                    tint = accentColor
                )
            }
            FilledTonalButton(
                onClick = onDownloadClick,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(downloadTag)
            ) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = downloadLabel,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}
