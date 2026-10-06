package com.mkread.app.feature.reader

internal class NarrationStartupBuffer<T>(
    private val targetSize: Int,
) {
    private val items = ArrayList<T>(targetSize)

    init {
        require(targetSize > 0) { "Startup buffer size must be positive" }
    }

    fun add(item: T): List<T>? {
        items += item
        return if (items.size >= targetSize) drain() else null
    }

    fun drain(): List<T> {
        if (items.isEmpty()) return emptyList()
        return items.toList().also { items.clear() }
    }
}
