package com.stb6.spdf.pdf

data class PdfOutlineEntry(
    val title: String,
    val pageIndex: Int,
    val children: List<PdfOutlineEntry>,
)

private const val MAX_OUTLINE_DEPTH = 16

private const val MAX_OUTLINE_NODES = 20_000

internal class OutlineReader(private val doc: Long, private val pageCount: Int) {
    private val visited = HashSet<Long>()

    fun read(): List<PdfOutlineEntry> = siblings(PdfiumNative.bookmarkFirstChild(doc, 0L), 0)

    private fun siblings(first: Long, depth: Int): List<PdfOutlineEntry> {
        val out = ArrayList<PdfOutlineEntry>()
        var ptr = first

        while (ptr != 0L && visited.size < MAX_OUTLINE_NODES && visited.add(ptr)) {
            val child = if (depth < MAX_OUTLINE_DEPTH) PdfiumNative.bookmarkFirstChild(doc, ptr) else 0L
            out += PdfOutlineEntry(
                title = PdfiumNative.bookmarkTitle(ptr).orEmpty().trim(),
                pageIndex = PdfiumNative.bookmarkPage(doc, ptr).takeIf { it in 0 until pageCount } ?: -1,
                children = if (child != 0L) siblings(child, depth + 1) else emptyList(),
            )
            ptr = PdfiumNative.bookmarkNextSibling(doc, ptr)
        }
        return out
    }
}
