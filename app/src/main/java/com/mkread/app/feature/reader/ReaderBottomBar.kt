package com.mkread.app.feature.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.FastRewind
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mkread.app.R

@Composable
fun ReaderBottomBar(
    chapterIndex: Int,
    chapterCount: Int,
    currentPage: Int,
    pageCount: Int,
    paginationComplete: Boolean,
    onPreviousChapter: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onNextChapter: () -> Unit,
    playbackState: ReaderPlaybackUiState,
    onPlaybackAction: (ReaderPlaybackAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasPreviousChapter = chapterIndex > 0
    val hasNextChapter = chapterIndex + 1 < chapterCount
    val hasPreviousPage = currentPage > 0 || hasPreviousChapter
    val hasNextPage = currentPage + 1 < pageCount || (paginationComplete && hasNextChapter)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("reader-bottom-bar"),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            HorizontalDivider()
            PlaybackControls(
                state = playbackState,
                onAction = onPlaybackAction,
            )
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReaderBottomIconButton(
                    label = stringResource(R.string.reader_previous_chapter),
                    enabled = hasPreviousChapter,
                    onClick = onPreviousChapter,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.SkipPrevious, contentDescription = null)
                }
                ReaderBottomIconButton(
                    label = stringResource(R.string.reader_previous_page),
                    enabled = hasPreviousPage,
                    onClick = onPreviousPage,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = null)
                }
                Box(
                    modifier = Modifier
                        .width(96.dp)
                        .heightIn(min = 48.dp)
                        .testTag("reader-page-count"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (pageCount > 0) {
                            stringResource(R.string.reader_page_count, currentPage + 1, pageCount)
                        } else {
                            stringResource(R.string.reader_page_count_empty)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                }
                ReaderBottomIconButton(
                    label = stringResource(R.string.reader_next_page),
                    enabled = hasNextPage,
                    onClick = onNextPage,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
                }
                ReaderBottomIconButton(
                    label = stringResource(R.string.reader_next_chapter),
                    enabled = hasNextChapter,
                    onClick = onNextChapter,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.SkipNext, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun PlaybackControls(
    state: ReaderPlaybackUiState,
    onAction: (ReaderPlaybackAction) -> Unit,
) {
    var speedMenuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag("reader-playback-controls"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1.3f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.reader_emotion),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
            Spacer(Modifier.width(4.dp))
            Switch(
                checked = state.emotionEnabled,
                onCheckedChange = { enabled ->
                    onAction(ReaderPlaybackAction.SetEmotionEnabled(enabled))
                },
                modifier = Modifier.semantics {
                    contentDescription = "reader-emotion-toggle"
                },
            )
        }
        PlaybackIconButton(
            label = stringResource(R.string.reader_previous_sentence),
            onClick = { onAction(ReaderPlaybackAction.Previous) },
        ) {
            Icon(Icons.Outlined.FastRewind, contentDescription = null)
        }
        PlaybackIconButton(
            label = stringResource(R.string.reader_replay_sentence),
            onClick = { onAction(ReaderPlaybackAction.Replay) },
        ) {
            Icon(Icons.Outlined.Replay, contentDescription = null)
        }
        PlaybackIconButton(
            label = if (state.isPlaying) {
                stringResource(R.string.reader_pause)
            } else {
                stringResource(R.string.reader_play)
            },
            enabled = !state.isPreparing,
            onClick = { onAction(ReaderPlaybackAction.Toggle) },
        ) {
            Icon(
                imageVector = if (state.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                contentDescription = null,
            )
        }
        PlaybackIconButton(
            label = stringResource(R.string.reader_next_sentence),
            onClick = { onAction(ReaderPlaybackAction.Next) },
        ) {
            Icon(Icons.Outlined.FastForward, contentDescription = null)
        }
        Box(modifier = Modifier.weight(0.8f), contentAlignment = Alignment.Center) {
            TextButton(
                onClick = { speedMenuExpanded = true },
                modifier = Modifier.testTag("reader-speed-button"),
            ) {
                Text("${formatSpeed(state.speed)}x", maxLines = 1)
            }
            DropdownMenu(
                expanded = speedMenuExpanded,
                onDismissRequest = { speedMenuExpanded = false },
            ) {
                PLAYBACK_SPEEDS.forEach { speed ->
                    DropdownMenuItem(
                        text = { Text("${formatSpeed(speed)}x") },
                        onClick = {
                            speedMenuExpanded = false
                            onAction(ReaderPlaybackAction.SetSpeed(speed))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaybackIconButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    icon: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = label },
    ) {
        icon()
    }
}

private fun formatSpeed(value: Float): String = if (value % 1f == 0f) {
    value.toInt().toString()
} else {
    value.toString()
}

private val PLAYBACK_SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

@Composable
private fun ReaderBottomIconButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    icon: @Composable () -> Unit,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = label },
        ) {
            icon()
        }
    }
}
