package com.stb6.spdf.ui.reader

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.core.content.getSystemService
import androidx.core.graphics.createBitmap
import com.stb6.spdf.Perf
import com.stb6.spdf.pdf.PdfSession
import com.stb6.spdf.pdf.PdfiumNative
import com.stb6.spdf.pdf.PdfiumThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import java.util.PriorityQueue
import kotlin.math.roundToInt

private data class PageKey(
    val index: Int,
    val widthPx: Int,
    // Height is part of identity when page metrics change.
    val heightPx: Int,
)

private data class TileKey(
    val index: Int,
    val offsetX: Int,
    val offsetY: Int,
    val scaledWidth: Int,
    // Full-page height affects patch contents even when patch dimensions are unchanged.
    val scaledHeight: Int,
    val tileWidth: Int,
    val tileHeight: Int,
)

class TilePool(
    private val scope: CoroutineScope,
    context: Context,
    private val session: () -> PdfSession?,
) {

    private val tileCacheBytes: Int
    private val baseCacheBytes: Int

    init {
        val megabytes = context.getSystemService<ActivityManager>()?.memoryClass ?: 0
        tileCacheBytes = maxOf(megabytes / 4, MIN_TILE_CACHE_MB) * 1024 * 1024
        baseCacheBytes = maxOf(megabytes / 16, MIN_BASE_CACHE_MB) * 1024 * 1024
    }

    private val pages = object : LruCache<PageKey, Bitmap>(baseCacheBytes) {
        override fun sizeOf(key: PageKey, value: Bitmap): Int = value.byteCount

        override fun entryRemoved(evicted: Boolean, key: PageKey, oldValue: Bitmap, newValue: Bitmap?) {
            recycleWhenOffScreen(oldValue)
        }
    }

    private val tileCache = object : LruCache<TileKey, Bitmap>(tileCacheBytes) {
        override fun sizeOf(key: TileKey, value: Bitmap): Int = value.byteCount

        override fun entryRemoved(evicted: Boolean, key: TileKey, oldValue: Bitmap, newValue: Bitmap?) {
            recycleWhenOffScreen(oldValue)
        }
    }

    // Evicted bitmaps remain alive until all display leases are released.
    private val inUse = HashMap<Bitmap, Int>()
    private val awaitingRecycle = HashSet<Bitmap>()

    private var prefetchJob: Job? = null

    // Reject renders from previous documents even if page indices match.
    private var generation = 0
    private val renderGate = RenderGate()

    private fun newBitmap(width: Int, height: Int): Bitmap =
        createBitmap(width, height, Bitmap.Config.RGB_565)

    // Retaining an evicted bitmap delays recycling; it must not remove the eviction mark.
    private fun retainTile(bitmap: Bitmap) {
        synchronized(inUse) { inUse[bitmap] = (inUse[bitmap] ?: 0) + 1 }
    }

    fun releaseTile(bitmap: Bitmap) {
        synchronized(inUse) {
            val left = (inUse[bitmap] ?: 0) - 1
            if (left > 0) {
                inUse[bitmap] = left
                return
            }
            inUse.remove(bitmap)
            if (awaitingRecycle.remove(bitmap) && !bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun recycleWhenOffScreen(bitmap: Bitmap) {
        synchronized(inUse) {
            if (inUse.containsKey(bitmap)) awaitingRecycle.add(bitmap)
            else if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    // Share renders only within a document generation; cancel after the last waiter leaves.
    private val inFlightBases = HashMap<InFlightKey, SharedRender>()

    private data class InFlightKey(val generation: Int, val key: PageKey)

    private class SharedRender(val deferred: Deferred<Bitmap?>, val priority: RenderGate.Priority) {
        var waiters = 0
    }

    private suspend fun basePage(index: Int, requestedWidthPx: Int, priority: Long): Bitmap? {
        val born = generation
        val current = session() ?: return null
        val info = try {
            current.exactPageInfo(index)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return null
        }

        // Bound both area and side length for extremely narrow pages.
        val ratio = info.aspectRatio.coerceAtLeast(0.001f)
        var widthPx = requestedWidthPx.coerceAtLeast(1)
        var heightPx = (widthPx / ratio).roundToInt().coerceAtLeast(1)
        val shrink = minOf(
            1f,
            kotlin.math.sqrt(MAX_BITMAP_PIXELS.toDouble() / (widthPx.toLong() * heightPx)).toFloat(),
            MAX_BITMAP_SIDE.toFloat() / heightPx,
            MAX_BITMAP_SIDE.toFloat() / widthPx,
        )
        if (shrink < 1f) {
            widthPx = (widthPx * shrink).roundToInt().coerceAtLeast(1)
            heightPx = (heightPx * shrink).roundToInt().coerceAtLeast(1)
        }

        val key = PageKey(index, widthPx, heightPx)
        pages[key]?.let { return it }
        val flightKey = InFlightKey(born, key)
        val shared = synchronized(inFlightBases) {
            // Never join a cancelled job that has not yet removed itself from the map.
            val alive = inFlightBases[flightKey]?.takeIf { it.deferred.isActive }
            alive?.also {
                it.waiters++
                renderGate.promote(it.priority, priority)
            } ?: run {
                val renderPriority = RenderGate.Priority(priority)
                SharedRender(
                    scope.async {
                        val me = coroutineContext[Job]
                        try {
                            renderBase(current, key, born, renderPriority)
                        } finally {
                            // A predecessor must not remove a newer render registered under the same key.
                            synchronized(inFlightBases) {
                                if (inFlightBases[flightKey]?.deferred === me) inFlightBases.remove(flightKey)
                            }
                        }
                    },
                    renderPriority,
                ).also {
                    inFlightBases[flightKey] = it
                    it.waiters = 1
                }
            }
        }
        try {
            return shared.deferred.await()
        } finally {
            synchronized(inFlightBases) {
                shared.waiters--
                if (shared.waiters == 0 && shared.deferred.isActive) shared.deferred.cancel()
            }
        }
    }

    // Acquire the render permit before allocating bitmap memory.
    private suspend fun renderBase(current: PdfSession, key: PageKey, born: Int, priority: RenderGate.Priority): Bitmap? =
        renderGate.withPermit(priority) {
            pages[key]?.let { return@withPermit it }
            Perf.count("base.request")
            val bitmap = newBitmap(key.widthPx, key.heightPx)
            var published = false
            try {
                Perf.time("base.render") {
                    current.renderRegion(bitmap, key.index, 0, 0, key.widthPx, key.heightPx)
                }
                if (born != generation) return@withPermit null
                pages[key]?.let { return@withPermit it }
                pages.put(key, bitmap)
                published = true
                return@withPermit bitmap
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return@withPermit null
            } finally {
                if (!published && !bitmap.isRecycled) bitmap.recycle()
            }
        }

    /** Retain before returning; retry eviction races. Callers must pair with releaseTile. */
    private suspend fun acquireRetrying(render: suspend () -> Bitmap?): Bitmap? {
        repeat(ACQUIRE_ATTEMPTS) {
            val bitmap = render() ?: return null
            val live = synchronized(inUse) {
                if (bitmap.isRecycled) false else { retainTile(bitmap); true }
            }
            if (live) return bitmap
        }
        return null
    }

    // Lease cached previews synchronously for the first composed frame.
    internal fun acquireCachedBase(index: Int): Bitmap? {
        val entry = pages.snapshot().entries.filter { it.key.index == index }
            .maxByOrNull { it.key.widthPx } ?: return null
        val bitmap = pages[entry.key] ?: return null
        return synchronized(inUse) {
            if (bitmap.isRecycled) null else bitmap.also(::retainTile)
        }
    }

    suspend fun acquireBase(index: Int, widthPx: Int = LOW_RES_WIDTH_PX): Bitmap? =
        acquireRetrying { basePage(index, widthPx, BASE_PRIORITY) }

    private suspend fun tile(
        index: Int,
        offsetX: Int,
        offsetY: Int,
        scaledWidth: Int,
        scaledHeight: Int,
        tileWidth: Int,
        tileHeight: Int,
        priority: Long,
    ): Bitmap? {
        val born = generation
        val current = session() ?: return null
        if (tileWidth <= 0 || tileHeight <= 0) return null
        val key = TileKey(index, offsetX, offsetY, scaledWidth, scaledHeight, tileWidth, tileHeight)
        tileCache[key]?.let { return it }
        // Allocate only after admission; queued bitmaps are outside the cache budget.
        return renderGate.withPermit(priority) {
            tileCache[key]?.let { return@withPermit it }
            // Recycle unpublished bitmaps on cancellation, failure, or duplicate completion.
            Perf.count("tile.bitmapAlloc")
            val bitmap = newBitmap(tileWidth, tileHeight)
            var published = false
            try {
                Perf.time("tile.render") {
                    current.renderRegion(
                        target = bitmap,
                        pageIndex = index,
                        offsetX = -offsetX,
                        offsetY = -offsetY,
                        scaledWidth = scaledWidth,
                        scaledHeight = scaledHeight,
                    )
                }
                if (born != generation) return@withPermit null
                tileCache[key]?.let { return@withPermit it }
                tileCache.put(key, bitmap)
                published = true
                return@withPermit bitmap
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return@withPermit null
            } finally {
                if (!published && !bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    suspend fun acquireTile(
        index: Int,
        offsetX: Int,
        offsetY: Int,
        scaledWidth: Int,
        scaledHeight: Int,
        tileWidth: Int,
        tileHeight: Int,
        priority: Long,
    ): Bitmap? = acquireRetrying {
        tile(index, offsetX, offsetY, scaledWidth, scaledHeight, tileWidth, tileHeight, priority)
    }

    fun prefetchLowRes(pages: List<Pair<Int, Int>>) {
        prefetchJob?.cancel()
        val current = session() ?: return
        if (pages.isEmpty()) return
        prefetchJob = scope.launch {
            for ((index, widthPx) in pages) {
                if (index in 0 until current.pageCount && widthPx > 0) {
                    basePage(index, widthPx, PREFETCH_BASE_PRIORITY)
                }
            }
        }
    }

    fun clear() {
        generation++
        prefetchJob?.cancel()
        synchronized(inFlightBases) {
            inFlightBases.values.forEach { it.deferred.cancel() }
            inFlightBases.clear()
        }
        pages.evictAll()
        tileCache.evictAll()
    }

    // Trimming must respect outstanding display leases, including already evicted images.
    fun trim() {
        pages.evictAll()
        tileCache.evictAll()
        releaseScratch()
    }

    // Requires the display to be detached before forcing outstanding leases to release.
    fun dispose() {
        clear()
        releaseScratch()
        synchronized(inUse) {
            inUse.clear()
            awaitingRecycle.forEach { if (!it.isRecycled) it.recycle() }
            awaitingRecycle.clear()
        }
    }

    // Scratch storage is native-thread confined; cleanup must survive scope cancellation.
    private fun releaseScratch() {
        if (!PdfiumNative.available) return
        scope.launch(NonCancellable) {
            withContext(PdfiumThread.dispatcher) { runCatching { PdfiumNative.releaseScratch() } }
        }
    }

    companion object {
        fun baseWidthFor(screenW: Int, screenH: Int): Int = when {
            screenW < 2 || screenH < 2 -> 0
            screenW < TINY_PAGE_PX -> TINY_BASE_WIDTH_PX
            else -> LOW_RES_WIDTH_PX
        }

        private const val TINY_PAGE_PX = 48
        private const val TINY_BASE_WIDTH_PX = 64

        private const val BASE_PRIORITY = Long.MAX_VALUE / 2

        private const val PREFETCH_BASE_PRIORITY = Long.MIN_VALUE / 2

        const val MAX_BITMAP_SIDE = 8192

        private const val MIN_BASE_CACHE_MB = 8

        private const val ACQUIRE_ATTEMPTS = 3

        private const val LOW_RES_WIDTH_PX = 512

        private const val MIN_TILE_CACHE_MB = 32

        private const val MAX_BITMAP_PIXELS = 8_000_000L
    }
}

private class RenderGate {
    class Priority(var value: Long)

    private class Waiter(val priority: Priority, val serial: Long) {
        val signal = CompletableDeferred<Unit>()
    }

    private val lock = Any()
    private var busy = false
    private var serial = 0L
    private val waiters = PriorityQueue<Waiter>(
        compareByDescending<Waiter> { it.priority.value }.thenBy { it.serial },
    )

    fun promote(priority: Priority, value: Long) = synchronized(lock) {
        if (value <= priority.value) return@synchronized
        val waiter = waiters.firstOrNull { it.priority === priority }
        if (waiter != null) waiters.remove(waiter)
        priority.value = value
        if (waiter != null) waiters.add(waiter)
    }

    suspend fun <T> withPermit(priority: Long, block: suspend () -> T): T =
        withPermit(Priority(priority), block)

    suspend fun <T> withPermit(priority: Priority, block: suspend () -> T): T {
        acquire(priority)
        try {
            return block()
        } finally {
            release()
        }
    }

    private suspend fun acquire(priority: Priority) {
        val waiter = synchronized(lock) {
            if (!busy) {
                busy = true
                null
            } else {
                Waiter(priority, ++serial).also { waiters.add(it) }
            }
        } ?: return
        try {
            waiter.signal.await()
        } catch (cancellation: CancellationException) {
            // Cancellation after admission must return the permit or the queue stays blocked.
            val granted = synchronized(lock) { !waiters.remove(waiter) }
            if (granted) release()
            throw cancellation
        }
    }

    private fun release() {
        val next = synchronized(lock) {
            waiters.poll().also { if (it == null) busy = false }
        }
        next?.signal?.complete(Unit)
    }
}
