package com.mkread.app.feature.reader

import androidx.compose.foundation.clickable
import com.mkread.app.speech.VoicePackStatus
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mkread.app.speech.VoiceCatalog
import com.mkread.app.speech.VoiceGroup
import com.mkread.app.speech.VoiceOption

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoicePickerSheet(
    state: ReaderPlaybackUiState,
    onAction: (ReaderPlaybackAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val selectedGroup = VoiceCatalog.find(state.voiceId).group
    val voices = remember(state.cloneVoices) { VoiceCatalog.presets + state.cloneVoices }
    val grouped = remember(voices) { voices.groupBy(VoiceOption::group) }
    // Large groups start collapsed unless they hold the current voice.
    var expanded by rememberSaveable {
        mutableStateOf(setOf(VoiceGroup.FAST, VoiceGroup.HIGH_QUALITY, VoiceGroup.CLONE, selectedGroup).map { it.name }.toSet())
    }
    LaunchedEffect(Unit) { onAction(ReaderPlaybackAction.RefreshVoicePacks) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                text = "朗读音色",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn(
                state = rememberLazyListState(),
                modifier = Modifier.fillMaxWidth().testTag("voice-picker-list"),
            ) {
                VoiceGroup.entries.forEach { group ->
                    val options = grouped[group].orEmpty()
                    if (options.isEmpty()) return@forEach
                    val isExpanded = group.name in expanded
                    item(key = "header-${group.name}") {
                        GroupHeader(
                            group = group,
                            count = options.size,
                            expanded = isExpanded,
                            onToggle = {
                                expanded = if (isExpanded) expanded - group.name else expanded + group.name
                            },
                        )
                    }
                    val packStatus = state.voicePacks[options.first().model.id]
                    if (packStatus != null && packStatus != VoicePackStatus.Installed) {
                        item(key = "pack-${group.name}") {
                            PackStatusRow(
                                status = packStatus,
                                onDownload = {
                                    onAction(ReaderPlaybackAction.DownloadVoicePack(options.first().model.id))
                                },
                            )
                        }
                    }
                    if (isExpanded) {
                        items(options, key = { it.id }) { option ->
                            VoiceRow(
                                option = option,
                                selected = option.id == state.voiceId,
                                onClick = { onAction(ReaderPlaybackAction.SetVoice(option.id)) },
                            )
                        }
                    }
                    if (group == VoiceGroup.CLONE && isExpanded) {
                        item(key = "emotion") {
                            EmotionRow(state = state, onAction = onAction)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(group: VoiceGroup, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Column {
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = group.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
            )
        }
    }
}

@Composable
private fun PackStatusRow(status: VoicePackStatus, onDownload: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (status) {
            is VoicePackStatus.Available -> {
                Text(
                    text = "需要下载音色包（${formatMegabytes(status.sizeBytes)}），下载后可离线使用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDownload, modifier = Modifier.testTag("voice-pack-download")) {
                    Text("下载")
                }
            }
            is VoicePackStatus.Downloading -> {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "正在下载 ${(status.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LinearProgressIndicator(
                        progress = { status.progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
            is VoicePackStatus.Unavailable -> {
                Text(
                    text = status.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDownload) { Text("重试") }
            }
            VoicePackStatus.Installed -> Unit
        }
    }
}

private fun formatMegabytes(bytes: Long): String = "${(bytes + 524_288L) / 1_048_576L} MB"

@Composable
private fun VoiceRow(option: VoiceOption, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .semantics { contentDescription = "voice-${option.id}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(option.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = option.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(Icons.Outlined.Check, contentDescription = "已选择", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun EmotionRow(state: ReaderPlaybackUiState, onAction: (ReaderPlaybackAction) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("情绪朗读", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "仅对声音克隆音色生效，按上下文切换情绪参考音",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = state.emotionEnabled,
            onCheckedChange = { onAction(ReaderPlaybackAction.SetEmotionEnabled(it)) },
            modifier = Modifier.semantics { contentDescription = "reader-emotion-toggle" },
        )
    }
}
