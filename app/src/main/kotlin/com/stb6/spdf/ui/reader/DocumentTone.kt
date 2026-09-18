package com.stb6.spdf.ui.reader

import com.stb6.spdf.pdf.PdfSession

internal suspend fun toneSamples(session: PdfSession, firstPage: Float): List<Float> =
    toneSamplePages(session.pageCount).mapNotNull { index ->
        if (index == 0) return@mapNotNull firstPage
        // Ignore individual failed samples rather than discarding the document-wide result.
        failSoft<Float?>(null) { session.backgroundLuminance(index) }
    }

internal fun toneSamplePages(count: Int): Set<Int> = buildSet {
    if (count <= 0) return@buildSet
    addAll(0 until minOf(3, count))
    if (count > 3) repeat(5) { add(it * (count - 1) / 4) }
}

internal fun isDarkByLuminance(luminance: List<Float>): Boolean {
    if (luminance.isEmpty()) return false
    val sorted = luminance.sorted()
    return sorted[sorted.size / 2] < DARK_DOCUMENT_THRESHOLD
}

internal const val DARK_DOCUMENT_THRESHOLD = 0.4f
