package com.stb6.spdf.pdf

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import android.os.Looper
import android.os.ParcelFileDescriptor
import io.legere.pdfiumandroid.suspend.PdfDocumentKt
import io.legere.pdfiumandroid.suspend.PdfPageKt
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlinx.coroutines.CancellationException
import com.stb6.spdf.Perf
import com.stb6.spdf.R
import com.stb6.spdf.ui.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.coroutines.coroutineContext

private const val PDF_POINT_DPI = 72

private const val OPEN_PAGE_CACHE = 6

private const val RENDER_FLAGS = PdfiumNative.FLAG_ANNOT
private const val LUMINANCE_SAMPLE_SIZE = 128

private const val MAX_TEXT_CHARS = 100_000

class PdfiumSession internal constructor(
    override val pageCount: Int,
    private val document: PdfDocumentKt,
    private val fileDescriptor: ParcelFileDescriptor,
    initialPageInfo: List<PageInfo>,
) : PdfSession {
    private val closed = AtomicBoolean(false)

    private val confirmed = AtomicReferenceArray<PageInfo>(pageCount)

    private val openPages = object : LinkedHashMap<Int, PdfPageKt>(OPEN_PAGE_CACHE * 2, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PdfPageKt>): Boolean {
            if (size <= OPEN_PAGE_CACHE) return false
            runCatching { eldest.value.close() }
            return true
        }
    }

    // Must run on PdfiumThread.
    private suspend fun pageFor(index: Int): PdfPageKt {
        openPages[index]?.let { return it }
        val page = Perf.time("page.open") {
            document.openPage(index) ?: throw IOException("Can't open PDF page ${index + 1}")
        }

        openPages[index] = page
        return page
    }
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        check(initialPageInfo.isNotEmpty())
        initialPageInfo.forEachIndexed { index, pageInfo ->
            confirmed.set(index, pageInfo)
        }

        backgroundScope.launch {
            for (index in initialPageInfo.size until pageCount) {
                coroutineContext.ensureActive()

                if (confirmed.get(index) != null) {
                    yield()
                    continue
                }
                try {
                    val pageInfo = loadPageInfo(index)
                    if (!closed.get()) {
                        confirmed.set(index, pageInfo)
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                }
                yield()
            }
        }
    }

    override suspend fun exactPageInfo(index: Int): PageInfo {
        checkOpen()
        checkPageIndex(index)
        confirmed.get(index)?.let { return it }

        val info = loadPageInfo(index)
        confirmed.set(index, info)
        return info
    }

    override suspend fun renderRegion(
        target: Bitmap,
        pageIndex: Int,
        offsetX: Int,
        offsetY: Int,
        scaledWidth: Int,
        scaledHeight: Int,
    ) {
        checkPageIndex(pageIndex)
        require(!target.isRecycled) { "target bitmap is recycled" }
        require(scaledWidth > 0 && scaledHeight > 0) { "render size must be positive" }

        // Coroutine cancellation cannot interrupt native rendering; the watchdog signals the progressive renderer.

        val cancel = IntArray(1)
        withCancelWatchdog({ cancel[0] = 1 }) {
            withNativeDocument {
                val page = pageFor(pageIndex)
                val result = if (PdfiumNative.available) {
                    PdfiumNative.render(
                        page.nativePointer(), target, offsetX, offsetY, scaledWidth, scaledHeight,
                        RENDER_FLAGS, cancel,
                    )
                } else {
                    PdfiumNative.RENDER_UNAVAILABLE
                }
                when (result) {
                    PdfiumNative.RENDER_DONE -> Unit
                    PdfiumNative.RENDER_CANCELLED -> throw CancellationException("Render cancelled")
                    PdfiumNative.RENDER_FAILED -> throw IOException("Failed to render PDF page ${pageIndex + 1}")

                    else -> page.renderPageBitmap(
                        bitmap = target,
                        startX = offsetX,
                        startY = offsetY,
                        drawSizeX = scaledWidth,
                        drawSizeY = scaledHeight,
                        renderAnnot = true,
                    )
                }
            }
        }
    }

    override suspend fun backgroundLuminance(index: Int): Float {
        checkPageIndex(index)
        val size = LUMINANCE_SAMPLE_SIZE

        val sample = createBitmap(size, size, Bitmap.Config.RGB_565)
        // PDFs need not paint a background; zero-filled pixels would misclassify white pages as dark.

        sample.eraseColor(Color.WHITE)
        try {
            renderRegion(
                target = sample,
                pageIndex = index,
                offsetX = 0,
                offsetY = 0,
                scaledWidth = size,
                scaledHeight = size,
            )

            val pixels = IntArray(size * size)
            sample.getPixels(pixels, 0, size, 0, 0, size, size)
            val values = IntArray(pixels.size) { i ->
                val color = pixels[i]
                val red = (color ushr 16) and 0xFF
                val green = (color ushr 8) and 0xFF
                val blue = color and 0xFF
                (0.2126 * red + 0.7152 * green + 0.0722 * blue + 0.5).toInt()
            }
            // The median estimates paper color without treating dense ink as a dark background.

            values.sort()
            return (values[values.size / 2] / 255f).coerceIn(0f, 1f)
        } finally {
            sample.recycle()
        }
    }

    override suspend fun closeSuspending() {
        if (!closed.compareAndSet(false, true)) return
        backgroundScope.cancel()

        if (PdfiumThread.isCurrent) {
            // Native blocks never suspend; waiting on this thread would deadlock.

            closeNativeResources()
        } else {
            // Cleanup must finish even after cancellation or interruption, without blocking the main thread.

            withContext(NonCancellable) {
                withContext(Dispatchers.IO) {
                    inFlightLock.withLock {
                        while (inFlight > 0) drained.awaitUninterruptibly()
                    }
                }
                withContext(PdfiumThread.dispatcher) { closeNativeResources() }
            }
        }
    }

    override fun close() {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "PdfSession.close() blocks; call closeSuspending() or close off the main thread"
        }
        runBlocking { closeSuspending() }
    }

    override suspend fun outline(): List<PdfOutlineEntry> {
        checkOpen()
        if (!PdfiumNative.available) return emptyList()
        return withNativeDocument {
            try {
                OutlineReader(document.nativePointer(), pageCount).read()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    override suspend fun links(pageIndex: Int): List<PdfLink> {
        checkPageIndex(pageIndex)

        // Geometry requires confirmed dimensions; caching estimates would permanently offset hit targets.

        val info = exactPageInfo(pageIndex)
        return withNativeDocument {
            val opened = pageFor(pageIndex)
            val space = opened.pageSpace(info)
            opened.getPageLinks().map { link ->
                PdfLink(
                    bounds = space.toDisplayRect(link.bounds),
                    uri = link.uri,
                    destPage = link.destPageIdx,
                )
            }
        }
    }

    override suspend fun pageText(pageIndex: Int): PageText? {
        checkPageIndex(pageIndex)
        if (!PdfiumNative.available) return null

        val info = exactPageInfo(pageIndex)
        val read = withNativeDocument {
            val opened = pageFor(pageIndex)
            val space = opened.pageSpace(info)

            val textPage = opened.openTextPage()
            val raw = try {
                PdfiumNative.readChars(textPage.nativePointer(), MAX_TEXT_CHARS)
            } finally {
                textPage.close()
            }
            if (raw == null || raw.isEmpty()) null else raw to space
        } ?: return null

        return withContext(Dispatchers.Default) {
            val (raw, space) = read
            buildPageText(raw, space).takeIf { it.hasText }
        }
    }

    private suspend fun loadPageInfo(index: Int): PageInfo {
        checkPageIndex(index)
        return withNativeDocument { confirmed.get(index) ?: document.readPageInfo(index) }
    }

    /** block must not suspend or switch dispatchers: queued cleanup could otherwise free its native pointers. */
    internal suspend fun <T> withNativeDocument(block: suspend () -> T): T =
        withContext(PdfiumThread.dispatcher) {
            enter()
            try {
                block()
            } finally {
                leave()
            }
        }

    private val inFlightLock = ReentrantLock()
    private val drained = inFlightLock.newCondition()
    private var inFlight = 0

    private fun enter() = inFlightLock.withLock {
        checkOpen()
        inFlight++
    }

    private fun leave() = inFlightLock.withLock {
        inFlight--
        if (inFlight == 0) drained.signalAll()
    }

    // Must run on PdfiumThread; info already includes rotation.
    private suspend fun PdfPageKt.pageSpace(info: PageInfo): PageSpace {
        val rotation = getPageRotation()
        val quarterTurn = rotation == 1 || rotation == 3
        val width = (if (quarterTurn) info.heightPt else info.widthPt).toFloat()
        val height = (if (quarterTurn) info.widthPt else info.heightPt).toFloat()
        val bounds = if (PdfiumNative.available) PdfiumNative.pageBounds(nativePointer()) else null

        return PageSpace(rotation, width, height, bounds?.get(0) ?: 0f, bounds?.get(3) ?: 0f)
    }

    private fun closeNativeResources() {
        var failure: Throwable? = null

        for (page in openPages.values) {
            try {
                page.close()
            } catch (exception: Throwable) {
                if (failure == null) failure = exception else failure.addSuppressed(exception)
            }
        }
        openPages.clear()
        try {
            document.close()
        } catch (exception: Throwable) {
            if (failure == null) failure = exception else failure.addSuppressed(exception)
        }
        try {
            fileDescriptor.close()
        } catch (exception: Throwable) {
            if (failure == null) failure = exception else failure.addSuppressed(exception)
        }
        failure?.let { throw it }
    }

    private fun checkOpen() {
        check(!closed.get()) { "PdfSession is closed" }
    }

    private fun checkPageIndex(index: Int) {
        require(index in 0 until pageCount) { "page index $index out of range (page count $pageCount)" }
    }
}

private const val MAX_PAGE_DIMENSION_PT = 14_400

private fun checkPageDimensions(index: Int, width: Int, height: Int) {
    if (width !in 1..MAX_PAGE_DIMENSION_PT || height !in 1..MAX_PAGE_DIMENSION_PT) {
        throw PdfIoException(UiText.Res(R.string.reader_error_detail_page_too_large, index + 1, width, height))
    }
}

// Must run on PdfiumThread.
internal suspend fun PdfDocumentKt.readPageInfo(index: Int): PageInfo {
    val size = getPageSize(index, PDF_POINT_DPI)
    checkPageDimensions(index, size.width, size.height)
    // pdfiumandroid already includes /Rotate in getPageSize; do not swap dimensions again.
    return PageInfo(widthPt = size.width, heightPt = size.height)
}
