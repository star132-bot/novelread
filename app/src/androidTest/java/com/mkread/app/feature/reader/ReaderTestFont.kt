package com.mkread.app.feature.reader

import android.graphics.Typeface
import androidx.test.platform.app.InstrumentationRegistry

internal object ReaderTestFont {
    const val FAMILY_ID = "noto-sans-cjk-sc-reader-test"
    const val ASSET_PATH = "fonts/NotoSansCJKsc-ReaderTest.otf"

    val typeface: Typeface by lazy {
        Typeface.createFromAsset(
            InstrumentationRegistry.getInstrumentation().context.assets,
            ASSET_PATH,
        )
    }
}
