package com.mkread.app.core.files

/**
 * Illustrations in MKBook chapter text (docs/formats/mkbook-v1.md §1.4): a whole line
 * `![caption](images/<name>)` marks where the image appears. The reader shows each one as its
 * own page and narration skips it.
 */
data class InlineImage(
    val caption: String,
    /** Path inside the book directory, always `images/<name>`. */
    val path: String,
) {
    companion object {
        const val DIRECTORY = "images"
        val PATH = Regex("^images/[A-Za-z0-9][A-Za-z0-9._-]{0,99}\\.(?:jpg|png|webp)$")
        private val LINE = Regex("^!\\[([^\\]\\n]{0,200})]\\((images/[^()\\s]+)\\)$")

        /** Parses one line (without its line break); null when it is not an image line. */
        fun parse(line: CharSequence): InlineImage? {
            if (line.length < MIN_LINE_LENGTH || line[0] != '!' || line[1] != '[') return null
            val match = LINE.matchEntire(line) ?: return null
            val path = match.groupValues[2]
            return if (PATH.matches(path)) InlineImage(match.groupValues[1], path) else null
        }

        /** Line ranges [start, end) of every image line, end excluding the line break. */
        fun lineRanges(text: String): List<IntRange> {
            val ranges = ArrayList<IntRange>()
            var start = 0
            while (start < text.length) {
                val newline = text.indexOf('\n', start).let { if (it < 0) text.length else it }
                if (text.startsWith("![", start) && parse(text.subSequence(start, newline)) != null) {
                    ranges += start until newline
                }
                start = newline + 1
            }
            return ranges
        }

        private const val MIN_LINE_LENGTH = 12
    }
}
