package com.mkread.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderDisplaySettingsTest {
    @Test
    fun normalized_clampsOutOfRangeValues() {
        val settings = ReaderDisplaySettings(
            fontSizeSp = 99f,
            lineSpacingMultiplier = 0.2f,
            horizontalMarginDp = -5f,
        ).normalized()

        assertEquals(ReaderDisplaySettings.MAX_FONT_SIZE_SP, settings.fontSizeSp)
        assertEquals(ReaderDisplaySettings.MIN_LINE_SPACING, settings.lineSpacingMultiplier)
        assertEquals(ReaderDisplaySettings.MIN_MARGIN_DP, settings.horizontalMarginDp)
    }

    @Test
    fun withDisplaySettings_appliesTypographyAndKeepsViewport() {
        val spec = PaginationSpec(
            widthPx = 1080,
            heightPx = 1800,
            densityDpi = 480,
            fontFamilyId = "sans-serif",
            fontSizeSp = 18f,
            lineSpacingMultiplier = 1.4f,
            horizontalMarginPx = 48,
            fontScale = 1.1f,
        )

        val updated = spec.withDisplaySettings(
            ReaderDisplaySettings(fontSizeSp = 24f, lineSpacingMultiplier = 1.8f, horizontalMarginDp = 20f),
            density = 3f,
        )

        assertEquals(spec.copy(fontSizeSp = 24f, lineSpacingMultiplier = 1.8f, horizontalMarginPx = 60), updated)
    }
}
