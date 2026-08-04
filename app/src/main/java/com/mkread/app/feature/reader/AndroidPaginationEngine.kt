package com.mkread.app.feature.reader

import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.min
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class AndroidPaginationEngine(
    private val cache: PaginationCache? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(2),
    private val typefaceResolver: (String) -> Typeface = { family ->
        Typeface.create(family, Typeface.NORMAL)
    },
) : PaginationEngine {
    override fun paginate(text: String, spec: PaginationSpec): Flow<PaginationBatch> =
        paginateInternal(key = null, text = text, spec = spec)

    override fun paginate(
        key: PaginationKey,
        text: String,
        spec: PaginationSpec,
    ): Flow<PaginationBatch> {
        require(key.spec == spec) { "Pagination key spec must match the requested spec" }
        return paginateInternal(key, text, spec)
    }

    private fun paginateInternal(
        key: PaginationKey?,
        text: String,
        spec: PaginationSpec,
    ): Flow<PaginationBatch> = flow {
        val contentWidth = validateSpec(spec)
        key?.let { cache?.load(it, text) }?.let { cached ->
            emit(PaginationBatch(cached, complete = true))
            return@flow
        }
        if (text.isEmpty()) {
            emit(PaginationBatch(emptyList(), complete = true))
            return@flow
        }

        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = typefaceResolver(spec.fontFamilyId)
            textSize = spec.fontSizeSp * (spec.densityDpi / BASE_DENSITY_DPI)
        }
        val ranges = ArrayList<PageRange>()
        var pageStart = 0
        while (pageStart < text.length) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val proposedEnd = findPageEnd(text, pageStart, contentWidth, spec, paint)
            val end = PageBoundary.safeEnd(text, pageStart, proposedEnd)
            val safeEnd = if (end <= pageStart) {
                PageBoundary.safeEnd(text, pageStart, pageStart + 1)
            } else {
                end
            }
            ranges += PageRange(ranges.size, pageStart, safeEnd)
            pageStart = safeEnd
            if (ranges.size == 1 || ranges.size % BATCH_PAGE_COUNT == 0) {
                emit(PaginationBatch(ranges.toList(), complete = false))
            }
        }

        val completed = ranges.toList()
        if (!validPageRanges(text, completed)) {
            throw IllegalStateException("Pagination produced invalid page boundaries")
        }
        key?.let { cache?.store(it, text, completed) }
        emit(PaginationBatch(completed, complete = true))
    }.flowOn(dispatcher)

    private fun validateSpec(spec: PaginationSpec): Int {
        require(spec.widthPx > 0 && spec.heightPx > 0) {
            "Pagination viewport must have positive dimensions"
        }
        require(spec.fontSizeSp.isFinite() && spec.fontSizeSp > 0f) {
            "Pagination font size must be positive"
        }
        require(spec.lineSpacingMultiplier.isFinite() && spec.lineSpacingMultiplier > 0f) {
            "Pagination line spacing must be positive"
        }
        val width = spec.widthPx - spec.horizontalMarginPx * 2
        require(width > 0) { "Pagination content width must be positive" }
        return width
    }

    private fun findPageEnd(
        text: String,
        pageStart: Int,
        contentWidth: Int,
        spec: PaginationSpec,
        paint: TextPaint,
    ): Int {
        val remaining = text.length - pageStart
        var lookahead = min(INITIAL_LOOKAHEAD, remaining)
        while (true) {
            val windowEnd = min(text.length, pageStart + lookahead)
            val layout = StaticLayout.Builder
                .obtain(text, pageStart, windowEnd, paint, contentWidth)
                .setIncludePad(true)
                .setLineSpacing(0f, spec.lineSpacingMultiplier)
                .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                .build()
            val visibleEnd = visibleLineEnd(layout, pageStart, windowEnd, spec.heightPx)
            if (
                visibleEnd >= windowEnd &&
                windowEnd < text.length &&
                lookahead < MAX_LOOKAHEAD
            ) {
                lookahead = min(MAX_LOOKAHEAD, lookahead * 2)
                continue
            }
            return visibleEnd
        }
    }

    private fun visibleLineEnd(
        layout: StaticLayout,
        pageStart: Int,
        windowEnd: Int,
        heightPx: Int,
    ): Int {
        var end = pageStart
        for (line in 0 until layout.lineCount) {
            if (layout.getLineBottom(line) > heightPx) break
            end = maxOf(end, layout.getLineEnd(line).coerceIn(pageStart, windowEnd))
        }
        return end
    }

    private companion object {
        const val INITIAL_LOOKAHEAD = 16_384
        const val MAX_LOOKAHEAD = 65_536
        const val BATCH_PAGE_COUNT = 8
        const val BASE_DENSITY_DPI = 160f
    }
}
