package com.stb6.spdf.ui.reader

import android.util.LruCache
import com.stb6.spdf.pdf.PageText
import com.stb6.spdf.pdf.PdfSession
import com.stb6.spdf.pdf.TextQuad
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class SelectionStore(private val session: () -> PdfSession?) {

    private val pageTexts = object : LruCache<Int, PageText>(TEXT_CACHE_BYTES) {
        override fun sizeOf(key: Int, value: PageText): Int = value.cacheBytes
    }

    private val _selection = MutableStateFlow<TextSelection?>(null)
    val selection: StateFlow<TextSelection?> = _selection.asStateFlow()

    private val _extractionWarnings = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val extractionWarnings = _extractionWarnings.asSharedFlow()
    var extractionWarningIssued = false
        private set

    private fun beginTextOperation() {
        extractionWarningIssued = false
        // Retry unavailable pages on a new user operation, not on every drag frame.
        pageTexts.snapshot().filterValues { it === UNAVAILABLE_TEXT }.keys.forEach(pageTexts::remove)
    }

    private fun warnUnavailable() {
        if (!extractionWarningIssued) {
            extractionWarningIssued = true
            _extractionWarnings.tryEmit(Unit)
        }
    }

    // Invalidate suspended selection requests when selection is cleared or replaced.
    private var selectionToken = 0L

    internal fun primePageText(pageIndex: Int, text: PageText) {
        pageTexts.put(pageIndex, text)
    }

    suspend fun pageText(pageIndex: Int, includeEmpty: Boolean = false): PageText? {
        fun result(text: PageText): PageText? {
            if (text === UNAVAILABLE_TEXT) warnUnavailable()
            return text.takeIf { includeEmpty || it.hasText }
        }
        pageTexts.get(pageIndex)?.let { return result(it) }
        val current = session() ?: return null
        val operation = selectionToken
        val loaded = failSoftCatching { current.pageText(pageIndex) }
        if (session() !== current || selectionToken != operation) return null
        val cached = loaded.fold({ it ?: NO_TEXT }, { UNAVAILABLE_TEXT })
        pageTexts.put(pageIndex, cached)
        return result(cached)
    }

    suspend fun selectWordAt(
        pageIndex: Int,
        pageX: Float,
        pageY: Float,
        maxDistancePt: Float,
    ): Boolean = failSoft(false) {
        beginTextOperation()
        val born = ++selectionToken
        val text = pageText(pageIndex) ?: return@failSoft false
        val hit = text.hitTest(pageX, pageY, maxDistancePt) ?: return@failSoft false
        val range = text.wordRangeAt(hit)
        if (born != selectionToken) return@failSoft false
        _selection.value = TextSelection(DocPosition(pageIndex, range.first), DocPosition(pageIndex, range.last))
        true
    }

    fun selectionAnchor(movingStart: Boolean): DocPosition? =
        _selection.value?.let { if (movingStart) it.end else it.start }

    // Freeze the drag anchor; recomputing it after crossing would make it follow the finger.
    suspend fun extendSelection(
        pageIndex: Int,
        pageX: Float,
        pageY: Float,
        anchorStart: DocPosition,
        anchorEnd: DocPosition,
    ): Boolean = failSoft(false) {
        val born = selectionToken
        val current = _selection.value ?: return@failSoft false
        val text = pageText(pageIndex) ?: return@failSoft false
        val hit = text.hitTest(pageX, pageY, Float.POSITIVE_INFINITY) ?: return@failSoft false
        if (born != selectionToken) return@failSoft false
        val at = DocPosition(pageIndex, hit)
        val next = TextSelection(minOf(at, anchorStart), maxOf(at, anchorEnd))
        if (next == current) return@failSoft false
        _selection.value = next
        true
    }

    suspend fun selectionQuads(pageIndex: Int): List<TextQuad> = failSoft(emptyList()) {
        val current = _selection.value ?: return@failSoft emptyList()
        val text = pageText(pageIndex) ?: return@failSoft emptyList()
        val range = current.rangeOn(pageIndex, text.charCount) ?: return@failSoft emptyList()
        text.quadsFor(range)
    }

    suspend fun selectionText(): String? {
        val current = _selection.value ?: return null
        beginTextOperation()
        val born = selectionToken
        val sb = SelectionTextBuffer()
        for (page in current.start.page..current.end.page) {
            val text = pageText(page) ?: continue
            if (born != selectionToken || _selection.value !== current) return null
            val range = current.rangeOn(page, text.charCount) ?: continue
            withContext(Dispatchers.Default) { sb.append(text.textOf(range)) }
        }
        if (born != selectionToken || _selection.value !== current) return null
        return withContext(Dispatchers.Default) { sb.toString() }
    }

    suspend fun selectWholePage(pageIndex: Int) = failSoft(Unit) {
        beginTextOperation()
        val born = ++selectionToken
        val text = pageText(pageIndex) ?: return@failSoft
        if (born != selectionToken) return@failSoft
        _selection.value = TextSelection(DocPosition(pageIndex, 0), DocPosition(pageIndex, text.charCount - 1))
    }

    fun clearSelection() {
        ++selectionToken
        _selection.value = null
    }

    fun clear() {
        clearSelection()
        pageTexts.evictAll()
        extractionWarningIssued = false
    }

    private companion object {
        const val TEXT_CACHE_BYTES = 4 * 1024 * 1024
        val NO_TEXT = PageText(IntArray(0), emptyList())
        val UNAVAILABLE_TEXT = PageText(IntArray(0), emptyList())
    }
}

private val PageText.cacheBytes: Int
    get() = (codepoints.size * 4 + runs.sumOf { 96 + (it.starts.size + it.ends.size) * 4 }).coerceAtLeast(1)
