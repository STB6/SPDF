package com.stb6.spdf.ui.reader

import kotlinx.coroutines.yield

import androidx.compose.ui.graphics.Color
import com.stb6.spdf.pdf.PageInfo

/** Content uses PDF points; screen = content * scale + offset, including page gaps. */
data class PageBox(
    val index: Int,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

private const val NO_BOX = -1

class CanvasLayout(
    val boxes: List<PageBox>,
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
) {

    // Box order follows the reading axis, not page indices.
    private val boxByPage: IntArray =
        IntArray((boxes.maxOfOrNull { it.index } ?: -1) + 1) { NO_BOX }.also { map ->
            boxes.forEachIndexed { position, box -> map[box.index] = position }
        }

    fun boxOf(index: Int): PageBox? {
        if (index !in boxByPage.indices) return null
        val position = boxByPage[index]
        return if (position == NO_BOX) null else boxes[position]
    }

    // Page gaps must scale with content to preserve pinch anchors.
    fun screenRect(
        box: PageBox,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
    ): FloatArray {
        val l = box.left * scale + offsetX
        val t = box.top * scale + offsetY
        return floatArrayOf(l, t, l + box.width * scale, t + box.height * scale)
    }

    inline fun <T> screenRect(
        box: PageBox,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
        block: (left: Float, top: Float, right: Float, bottom: Float) -> T,
    ): T {
        val l = box.left * scale + offsetX
        val t = box.top * scale + offsetY
        return block(l, t, l + box.width * scale, t + box.height * scale)
    }

    fun visibleBoxes(
        scale: Float,
        offsetX: Float,
        offsetY: Float,
        vertical: Boolean,
        viewportW: Int,
        viewportH: Int,
        overscanPx: Float,
    ): List<PageBox> {
        if (boxes.isEmpty()) return emptyList()
        val offsetMain = if (vertical) offsetY else offsetX
        val limit = (if (vertical) viewportH else viewportW) + overscanPx

        var lo = 0
        var hi = boxes.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            val box = boxes[mid]
            val end = (if (vertical) box.bottom else box.right) * scale + offsetMain
            if (end <= -overscanPx) lo = mid + 1 else hi = mid
        }

        val result = ArrayList<PageBox>(4)
        var i = lo
        while (i < boxes.size) {
            val box = boxes[i]
            val start = (if (vertical) box.top else box.left) * scale + offsetMain
            if (start >= limit) break
            val inside = screenRect(box, scale, offsetX, offsetY) { left, top, right, bottom ->
                right > -overscanPx && left < viewportW + overscanPx &&
                    bottom > -overscanPx && top < viewportH + overscanPx
            }
            if (inside) result += box
            i++
        }
        return result
    }

    fun pageAtCenter(
        scale: Float,
        offsetX: Float,
        offsetY: Float,
        vertical: Boolean,
        viewportW: Int,
        viewportH: Int,
    ): Int {
        val center = if (vertical) viewportH / 2f else viewportW / 2f
        val visible = visibleBoxes(scale, offsetX, offsetY, vertical, viewportW, viewportH, 0f)
        return (visible.ifEmpty { boxes }).minByOrNull { box ->
            screenRect(box, scale, offsetX, offsetY) { left, top, right, bottom ->
                val lo = if (vertical) top else left
                val hi = if (vertical) bottom else right
                when {
                    center < lo -> lo - center
                    center > hi -> center - hi
                    else -> 0f
                }
            }
        }?.index ?: 0
    }

}

data class PageMetrics(
    val sizes: List<PageInfo>,
    val standard: PageInfo,
    val maxWidthPt: Int,
    val maxHeightPt: Int,
)

internal fun medianOf(buffer: IntArray, count: Int): Int {
    if (count <= 0) return 1
    return buffer.copyOf(count).also { it.sort() }[count / 2].coerceAtLeast(1)
}

internal suspend fun collectPageMetrics(
    count: Int,
    read: suspend (Int) -> PageInfo?,
    publish: (PageMetrics) -> Unit,
) {
    val exact = arrayOfNulls<PageInfo>(count)
    val widths = IntArray(count)
    val heights = IntArray(count)
    var loaded = 0
    var maxWidthPt = 1
    var maxHeightPt = 1
    var publishedLoaded = -1
    var lastPublishNs = 0L

    for (i in 0 until count) {
        read(i)?.let { info ->
            exact[i] = info
            widths[loaded] = info.widthPt
            heights[loaded] = info.heightPt
            loaded++
            maxWidthPt = maxOf(maxWidthPt, info.widthPt)
            maxHeightPt = maxOf(maxHeightPt, info.heightPt)
        }
        // Publish the final batch even if the last page is unreadable.
        val now = System.nanoTime()
        val due = publishedLoaded < 0 || i == count - 1 ||
            now - lastPublishNs >= METRICS_PUBLISH_INTERVAL_NS
        if (loaded > 0 && loaded != publishedLoaded && due) {
            val standard = PageInfo(medianOf(widths, loaded), medianOf(heights, loaded))
            publish(PageMetrics(List(count) { exact[it] ?: standard }, standard, maxWidthPt, maxHeightPt))
            publishedLoaded = loaded
            lastPublishNs = now
        }
        if (i % 64 == 63) yield()
    }
}

private const val METRICS_PUBLISH_INTERVAL_NS = 100_000_000L

fun buildCanvasLayout(
    sizes: List<PageInfo>,
    vertical: Boolean,
    reversed: Boolean,
    gapPt: Float,
): CanvasLayout {
    if (sizes.isEmpty()) return CanvasLayout(emptyList(), 0f, 0f, 0f, 0f)

    val boxes = ArrayList<PageBox>(sizes.size)
    var cursor = 0f
    var crossExtent = 1f
    sizes.forEachIndexed { index, size ->
        val width = size.widthPt.toFloat().coerceAtLeast(1f)
        val height = size.heightPt.toFloat().coerceAtLeast(1f)
        crossExtent = maxOf(crossExtent, if (vertical) width else height)
        boxes += if (vertical) {
            PageBox(index, -width / 2f, cursor, width, height)
        } else {
            PageBox(index, cursor, -height / 2f, width, height)
        }
        cursor += (if (vertical) height else width) + gapPt
    }
    val mainExtent = (cursor - gapPt).coerceAtLeast(0f)

    val placed = if (!vertical && reversed) {
        // Binary visibility searches require boxes sorted along the main axis.
        boxes.map { it.copy(left = mainExtent - it.left - it.width) }.sortedBy { it.left }
    } else {
        boxes
    }

    val half = crossExtent / 2f
    return CanvasLayout(
        boxes = placed,
        minX = if (vertical) -half else 0f,
        maxX = if (vertical) half else mainExtent,
        minY = if (vertical) 0f else -half,
        maxY = if (vertical) mainExtent else half,
    )
}

internal val CanvasColor = Color(0xFF1C1C1E)
internal val PagePlaceholderColor = Color(0xFF2A2A2C)
internal const val PAGE_GAP_PT = 8f

internal const val MIN_PAGE_CROSS_FRACTION = 0.4f

internal const val MAX_TOTAL_ZOOM = 5f
