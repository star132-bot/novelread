package com.mkread.app.feature.reader

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Typography the reader paginates with; the app-wide system font scale is applied on top. */
data class ReaderDisplaySettings(
    val fontSizeSp: Float = DEFAULT_FONT_SIZE_SP,
    val lineSpacingMultiplier: Float = DEFAULT_LINE_SPACING,
    val horizontalMarginDp: Float = DEFAULT_MARGIN_DP,
) {
    fun normalized() = ReaderDisplaySettings(
        fontSizeSp = fontSizeSp.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP),
        lineSpacingMultiplier = lineSpacingMultiplier.coerceIn(MIN_LINE_SPACING, MAX_LINE_SPACING),
        horizontalMarginDp = horizontalMarginDp.coerceIn(MIN_MARGIN_DP, MAX_MARGIN_DP),
    )

    companion object {
        const val DEFAULT_FONT_SIZE_SP = 18f
        const val MIN_FONT_SIZE_SP = 12f
        const val MAX_FONT_SIZE_SP = 32f
        const val FONT_SIZE_STEP_SP = 1f
        const val DEFAULT_LINE_SPACING = 1.4f
        const val MIN_LINE_SPACING = 1.0f
        const val MAX_LINE_SPACING = 2.4f
        const val DEFAULT_MARGIN_DP = 16f
        const val MIN_MARGIN_DP = 4f
        const val MAX_MARGIN_DP = 48f
    }
}

class ReaderDisplaySettingsStore(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
) {
    val settings: StateFlow<ReaderDisplaySettings> = dataStore.data
        .map { preferences ->
            ReaderDisplaySettings(
                fontSizeSp = preferences[FONT_SIZE_KEY] ?: ReaderDisplaySettings.DEFAULT_FONT_SIZE_SP,
                lineSpacingMultiplier = preferences[LINE_SPACING_KEY]
                    ?: ReaderDisplaySettings.DEFAULT_LINE_SPACING,
                horizontalMarginDp = preferences[MARGIN_KEY] ?: ReaderDisplaySettings.DEFAULT_MARGIN_DP,
            ).normalized()
        }
        .stateIn(scope, SharingStarted.Eagerly, ReaderDisplaySettings())

    fun update(settings: ReaderDisplaySettings) {
        val normalized = settings.normalized()
        scope.launch {
            dataStore.edit { preferences ->
                preferences[FONT_SIZE_KEY] = normalized.fontSizeSp
                preferences[LINE_SPACING_KEY] = normalized.lineSpacingMultiplier
                preferences[MARGIN_KEY] = normalized.horizontalMarginDp
            }
        }
    }

    private companion object {
        val FONT_SIZE_KEY = floatPreferencesKey("reader_font_size_sp")
        val LINE_SPACING_KEY = floatPreferencesKey("reader_line_spacing")
        val MARGIN_KEY = floatPreferencesKey("reader_horizontal_margin_dp")
    }
}

/** Applies the user's typography to a layout spec measured for the current viewport. */
fun PaginationSpec.withDisplaySettings(
    settings: ReaderDisplaySettings,
    density: Float,
): PaginationSpec = copy(
    fontSizeSp = settings.fontSizeSp,
    lineSpacingMultiplier = settings.lineSpacingMultiplier,
    horizontalMarginPx = (settings.horizontalMarginDp * density).toInt().coerceAtLeast(0),
)
