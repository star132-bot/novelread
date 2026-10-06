package com.mkread.app.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mkread.app.ui.theme.ThemeMode
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderDisplaySheet(
    settings: ReaderDisplaySettings,
    onSettingsChange: (ReaderDisplaySettings) -> Unit,
    themeMode: ThemeMode?,
    onThemeModeChange: ((ThemeMode) -> Unit)?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("reader-display-sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("阅读设置", style = MaterialTheme.typography.titleLarge)

            SettingLabel("字号", "${settings.fontSizeSp.roundToInt()}")
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(
                    onClick = {
                        onSettingsChange(
                            settings.copy(fontSizeSp = settings.fontSizeSp - ReaderDisplaySettings.FONT_SIZE_STEP_SP),
                        )
                    },
                    enabled = settings.fontSizeSp > ReaderDisplaySettings.MIN_FONT_SIZE_SP,
                    modifier = Modifier.testTag("reader-font-smaller"),
                ) { Text("A-") }
                Slider(
                    value = settings.fontSizeSp,
                    onValueChange = { onSettingsChange(settings.copy(fontSizeSp = it.roundToInt().toFloat())) },
                    valueRange = ReaderDisplaySettings.MIN_FONT_SIZE_SP..ReaderDisplaySettings.MAX_FONT_SIZE_SP,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                )
                FilledTonalButton(
                    onClick = {
                        onSettingsChange(
                            settings.copy(fontSizeSp = settings.fontSizeSp + ReaderDisplaySettings.FONT_SIZE_STEP_SP),
                        )
                    },
                    enabled = settings.fontSizeSp < ReaderDisplaySettings.MAX_FONT_SIZE_SP,
                    modifier = Modifier.testTag("reader-font-larger"),
                ) { Text("A+") }
            }

            SettingLabel("行距", String.format("%.1f", settings.lineSpacingMultiplier))
            Slider(
                value = settings.lineSpacingMultiplier,
                onValueChange = {
                    onSettingsChange(settings.copy(lineSpacingMultiplier = (it * 10).roundToInt() / 10f))
                },
                valueRange = ReaderDisplaySettings.MIN_LINE_SPACING..ReaderDisplaySettings.MAX_LINE_SPACING,
            )

            SettingLabel("页边距", "${settings.horizontalMarginDp.roundToInt()}")
            Slider(
                value = settings.horizontalMarginDp,
                onValueChange = { onSettingsChange(settings.copy(horizontalMarginDp = it.roundToInt().toFloat())) },
                valueRange = ReaderDisplaySettings.MIN_MARGIN_DP..ReaderDisplaySettings.MAX_MARGIN_DP,
            )

            if (themeMode != null && onThemeModeChange != null) {
                Spacer(Modifier.height(4.dp))
                Text("主题", style = MaterialTheme.typography.titleSmall)
                val options = listOf(
                    ThemeMode.SYSTEM to "跟随系统",
                    ThemeMode.LIGHT to "日间",
                    ThemeMode.DARK to "夜间",
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    options.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = themeMode == mode,
                            onClick = { onThemeModeChange(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        ) { Text(label) }
                    }
                }
            }

            TextButton(
                onClick = { onSettingsChange(ReaderDisplaySettings()) },
                modifier = Modifier.align(Alignment.End),
            ) { Text("恢复默认") }
        }
    }
}

@Composable
private fun SettingLabel(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(48.dp),
        )
    }
}
