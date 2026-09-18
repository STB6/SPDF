package com.stb6.spdf.ui.reader

data class DocPosition(val page: Int, val index: Int) : Comparable<DocPosition> {
    override fun compareTo(other: DocPosition): Int =
        if (page != other.page) page.compareTo(other.page) else index.compareTo(other.index)
}

/** Inclusive character range with start <= end. */
data class TextSelection(val start: DocPosition, val end: DocPosition) {
    init {
        require(start <= end) { "selection start after end: $start > $end" }
    }

    fun rangeOn(page: Int, charCount: Int): IntRange? {
        if (page < start.page || page > end.page || charCount <= 0) return null
        val lo = if (page == start.page) start.index else 0
        val hi = if (page == end.page) end.index else charCount - 1
        val clampedHi = hi.coerceAtMost(charCount - 1)
        return if (lo > clampedHi) null else lo..clampedHi
    }

}
