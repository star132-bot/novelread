package com.mkread.app.feature.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mkread.app.R
import com.mkread.app.core.files.InlineImage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Returns the illustration when [range] is a page made of one image line (see AndroidPaginationEngine). */
fun illustrationOnPage(text: String, range: PageRange): InlineImage? {
    if (!text.startsWith("![", range.start)) return null
    return InlineImage.parse(text.substring(range.start, range.endExclusive).trimEnd('\n'))
}

/** One illustration scaled to fit the page, with its caption underneath. */
@Composable
fun IllustrationPage(
    bookId: String,
    image: InlineImage,
    horizontalMarginPx: Int,
    onPageTap: (ReaderPageTap) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val margin = with(density) { horizontalMarginPx.toDp() }
    BoxWithConstraints(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onPageTap(
                        when {
                            offset.x < size.width * PREVIOUS_TAP_FRACTION -> ReaderPageTap.Previous
                            offset.x > size.width * NEXT_TAP_FRACTION -> ReaderPageTap.Next
                            else -> ReaderPageTap.Center
                        },
                    )
                }
            }
            .padding(horizontal = margin, vertical = 16.dp),
    ) {
        val maxWidthPx = constraints.maxWidth
        val maxHeightPx = constraints.maxHeight
        val bitmap by produceState<Bitmap?>(null, bookId, image.path, maxWidthPx, maxHeightPx) {
            value = withContext(Dispatchers.IO) {
                decodeSampled(File(context.filesDir, "books/$bookId/${image.path}"), maxWidthPx, maxHeightPx)
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val loaded = bitmap
                if (loaded != null) {
                    Image(
                        bitmap = loaded.asImageBitmap(),
                        contentDescription = image.caption.ifBlank { stringResource(R.string.reader_illustration) },
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
            if (image.caption.isNotBlank()) {
                Text(
                    text = image.caption,
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Decodes at the smallest power-of-two sample size that still covers the page, or null if unreadable. */
private fun decodeSampled(file: File, maxWidth: Int, maxHeight: Int): Bitmap? {
    if (!file.isFile || maxWidth <= 0 || maxHeight <= 0) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxWidth || bounds.outHeight / (sample * 2) >= maxHeight) {
        sample *= 2
    }
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

private const val PREVIOUS_TAP_FRACTION = 0.28f
private const val NEXT_TAP_FRACTION = 0.72f
