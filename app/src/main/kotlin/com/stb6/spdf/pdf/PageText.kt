package com.stb6.spdf.pdf

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Corners follow the baseline in display points: start-top, end-top, end-bottom, start-bottom. */
data class TextQuad(
    val startTopX: Float,
    val startTopY: Float,
    val endTopX: Float,
    val endTopY: Float,
    val endBottomX: Float,
    val endBottomY: Float,
    val startBottomX: Float,
    val startBottomY: Float,
) {
    val minX: Float get() = minOf(startTopX, endTopX, endBottomX, startBottomX)
    val minY: Float get() = minOf(startTopY, endTopY, endBottomY, startBottomY)
    val maxX: Float get() = maxOf(startTopX, endTopX, endBottomX, startBottomX)
    val maxY: Float get() = maxOf(startTopY, endTopY, endBottomY, startBottomY)
}

/** Baseline-aligned cells in display points; starts/ends are offsets along flow from the run origin. */
class TextRun internal constructor(
    val first: Int,
    val last: Int,
    val originX: Float,
    val originY: Float,
    val flowX: Float,
    val flowY: Float,
    val crossX: Float,
    val crossY: Float,

    val crossMin: Float,
    val crossMax: Float,
    val starts: FloatArray,
    val ends: FloatArray,

    val size: Float,
    val vertical: Boolean,
) {
    val length: Int get() = last - first + 1

    fun quad(from: Int, to: Int): TextQuad {
        val s = starts[from - first]
        val e = ends[to - first]
        fun px(t: Float, c: Float) = originX + flowX * t + crossX * c
        fun py(t: Float, c: Float) = originY + flowY * t + crossY * c
        return TextQuad(
            px(s, crossMax), py(s, crossMax),
            px(e, crossMax), py(e, crossMax),
            px(e, crossMin), py(e, crossMin),
            px(s, crossMin), py(s, crossMin),
        )
    }

    internal fun slotAt(t: Float): Int {
        val n = length
        if (t <= starts[0]) return 0
        if (t >= ends[n - 1]) return n - 1
        var bestK = 0
        var bestD = Float.MAX_VALUE
        for (k in 0 until n) {
            if (t >= starts[k] && t < ends[k]) return k
            val d = min(abs(t - starts[k]), abs(t - ends[k]))
            if (d < bestD) {
                bestD = d
                bestK = k
            }
        }
        return bestK
    }
}

// Indices are PDFium character indices, including whitespace and newlines; they are not UTF-16 offsets.
class PageText internal constructor(
    val codepoints: IntArray,
    val runs: List<TextRun>,
) {
    val charCount: Int get() = codepoints.size

    val hasText: Boolean get() = runs.isNotEmpty()

    fun quadsFor(range: IntRange): List<TextQuad> {
        if (range.isEmpty() || charCount == 0) return emptyList()
        val lo = range.first.coerceAtLeast(0)
        val hi = range.last.coerceAtMost(charCount - 1)
        if (lo > hi) return emptyList()
        val out = ArrayList<TextQuad>()
        for (run in runs) {
            if (run.last < lo) continue
            if (run.first > hi) break
            out += run.quad(max(run.first, lo), min(run.last, hi))
        }
        return out
    }

    fun hitTest(x: Float, y: Float, maxDistance: Float): Int? {
        var best: TextRun? = null
        var bestCross = Float.MAX_VALUE
        var bestAlong = Float.MAX_VALUE
        var bestExtent = Float.MAX_VALUE
        for (run in runs) {
            val dx = x - run.originX
            val dy = y - run.originY
            val t = dx * run.flowX + dy * run.flowY
            val c = dx * run.crossX + dy * run.crossY
            val dCross = max(max(run.crossMin - c, c - run.crossMax), 0f)
            val dAlong = max(max(run.starts[0] - t, t - run.ends[run.length - 1]), 0f)
            // Reject unreachable runs before ranking, or a distant aligned run can suppress a valid nearby hit.

            if (dCross > maxDistance || dAlong > maxDistance) continue
            val extent = run.ends[run.length - 1] - run.starts[0]

            val tie = run.size * SAME_LINE_SLACK
            val better = when {
                dCross < bestCross - tie -> true
                dCross > bestCross + tie -> false

                (dCross == 0f) != (bestCross == 0f) -> dCross == 0f

                dCross == 0f && dAlong == 0f && bestAlong == 0f -> extent < bestExtent
                else -> dAlong < bestAlong
            }
            if (better) {
                best = run
                bestCross = dCross
                bestAlong = dAlong
                bestExtent = extent
            }
        }
        val run = best ?: return null
        val t = (x - run.originX) * run.flowX + (y - run.originY) * run.flowY
        return run.first + run.slotAt(t)
    }

    private val flat: FlatText by lazy { FlatText(codepoints) }

    fun wordRangeAt(index: Int): IntRange {
        require(index in 0 until charCount) { "char index $index out of range (char count $charCount)" }
        if (isLineBreak(codepoints[index])) return index..index
        val f = flat
        val iterator = BreakIterator.getWordInstance(Locale.getDefault())
        iterator.setText(f.text)
        val offset = f.offsetOfChar[index]
        val end = iterator.following(offset).let { if (it == BreakIterator.DONE) f.text.length else it }
        val start = iterator.previous().let { if (it == BreakIterator.DONE) 0 else it }
        if (end <= start) return index..index
        var lo = f.charOfOffset[start]
        var hi = f.charOfOffset[end - 1]

        while (lo < index && isLineBreak(codepoints[lo])) lo++
        while (hi > index && isLineBreak(codepoints[hi])) hi--
        var hasGlyph = false
        for (i in lo..hi) if (!isSpace(codepoints[i])) hasGlyph = true
        return if (hasGlyph) lo..hi else index..index
    }

    fun textOf(range: IntRange): String {
        if (range.isEmpty() || charCount == 0) return ""
        val sb = StringBuilder()
        for (i in range.first.coerceAtLeast(0)..range.last.coerceAtMost(charCount - 1)) {
            val cp = codepoints[i]
            if (cp == '\r'.code || cp == 0) continue
            if (Character.isValidCodePoint(cp)) sb.appendCodePoint(cp) else sb.append('�')
        }
        return sb.toString()
    }

    private class FlatText(codepoints: IntArray) {
        val text: String
        val offsetOfChar = IntArray(codepoints.size + 1)
        val charOfOffset: IntArray

        init {
            val sb = StringBuilder(codepoints.size)
            val owner = ArrayList<Int>(codepoints.size)
            for ((i, cp) in codepoints.withIndex()) {
                offsetOfChar[i] = sb.length
                val before = sb.length
                if (cp in 1..Character.MAX_CODE_POINT && !isSurrogateCodePoint(cp)) {
                    sb.appendCodePoint(cp)
                } else {
                    sb.append('�')
                }
                repeat(sb.length - before) { owner += i }
            }
            offsetOfChar[codepoints.size] = sb.length
            text = sb.toString()
            charOfOffset = owner.toIntArray()
        }

        private fun isSurrogateCodePoint(cp: Int) = cp in 0xD800..0xDFFF
    }

    companion object {
        private const val SAME_LINE_SLACK = 0.15f
    }
}

internal fun isLineBreak(cp: Int): Boolean = cp == '\n'.code || cp == '\r'.code

internal fun isSpace(cp: Int): Boolean =
    isLineBreak(cp) || cp == 0xA0 || cp == 0x3000 || (cp in 1..0x10FFFF && Character.isWhitespace(cp))

internal fun buildPageText(raw: FloatArray, space: PageSpace): PageText {
    val stride = PdfiumNative.STRIDE
    val n = raw.size / stride
    val cps = IntArray(n)
    val ox = FloatArray(n)
    val oy = FloatArray(n)
    val size = FloatArray(n)
    val dx = FloatArray(n)
    val dy = FloatArray(n)
    val hasBox = BooleanArray(n)
    val bl = FloatArray(n)
    val bt = FloatArray(n)
    val br = FloatArray(n)
    val bb = FloatArray(n)

    for (i in 0 until n) {
        val base = i * stride
        cps[i] = raw[base].toInt()
        val (px, py) = space.toDisplay(raw[base + 1], raw[base + 2])
        ox[i] = px
        oy[i] = py
        var a = raw[base + 4]
        var b = raw[base + 5]
        val c = raw[base + 6]
        val d = raw[base + 7]
        var len = hypot(a, b)
        if (!(len > 1e-6f)) {
            a = 1f
            b = 0f
            len = 1f
        }
        val (vx, vy) = space.rotateVector(a / len, b / len)
        dx[i] = vx
        dy[i] = vy
        // Font size excludes the text matrix scale (for example, 1 Tf with a 12x matrix).
        var s = raw[base + 3] * hypot(c, d)
        if (!(s > MIN_SIZE)) s = raw[base + 3]
        if (!(s > MIN_SIZE)) s = 1f
        size[i] = s
        val l = raw[base + 8]
        val r = raw[base + 9]
        val bottom = raw[base + 10]
        val top = raw[base + 11]
        if (r > l && top > bottom) {
            val (x0, y0) = space.toDisplay(l, bottom)
            val (x1, y1) = space.toDisplay(r, top)
            hasBox[i] = true
            bl[i] = min(x0, x1)
            br[i] = max(x0, x1)
            bt[i] = min(y0, y1)
            bb[i] = max(y0, y1)
        }
    }

    fun inkRange(i: Int, fx: Float, fy: Float): Pair<Float, Float> {
        if (!hasBox[i]) return 0f to 0f
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (corner in 0 until 4) {
            val cx = if (corner and 1 == 0) bl[i] else br[i]
            val cy = if (corner and 2 == 0) bt[i] else bb[i]
            val p = (cx - ox[i]) * fx + (cy - oy[i]) * fy
            if (p < lo) lo = p
            if (p > hi) hi = p
        }
        return lo to hi
    }

    val runs = ArrayList<TextRun>()
    val members = IntArray(n)
    var count = 0
    var flowX = 0f
    var flowY = 0f
    var crossX = 0f
    var crossY = 0f
    var vertical = false

    fun startRun(i: Int) {
        members[0] = i
        count = 1
        flowX = dx[i]
        flowY = dy[i]

        crossX = dy[i]
        crossY = -dx[i]
        vertical = false
    }

    fun flush() {
        if (count == 0) return
        val first = members[0]
        val last = members[count - 1]
        val originX = ox[first]
        val originY = oy[first]
        val sizes = FloatArray(count) { size[members[it]] }
        sizes.sort()
        val runSize = sizes[count / 2]
        val length = last - first + 1
        val starts = FloatArray(length)
        val ends = FloatArray(length)

        val gs = FloatArray(count)
        val ge = FloatArray(count)
        for (k in 0 until count) {
            val i = members[k]
            val originT = (ox[i] - originX) * flowX + (oy[i] - originY) * flowY
            val (inkLo, inkHi) = inkRange(i, flowX, flowY)
            if (!vertical) {
                gs[k] = originT
                ge[k] = originT + max(inkHi, MIN_ADVANCE * size[i])
            } else if (hasBox[i]) {
                gs[k] = originT + inkLo
                ge[k] = originT + inkHi
            } else {
                gs[k] = originT
                ge[k] = originT + size[i]
            }
        }
        // PDFium splits ligatures into characters sharing one origin; divide the cluster width between them.

        var k = 0
        while (k < count) {
            var kEnd = k
            while (kEnd + 1 < count &&
                members[kEnd + 1] == members[kEnd] + 1 &&
                abs(gs[kEnd + 1] - gs[k]) <= SAME_ORIGIN * runSize
            ) {
                kEnd++
            }
            val clusterStart = gs[k]
            var inkEnd = clusterStart
            for (q in k..kEnd) inkEnd = max(inkEnd, ge[q])
            val clusterEnd: Float
            if (kEnd + 1 >= count) {
                clusterEnd = inkEnd
            } else {
                val nextStart = gs[kEnd + 1]
                val gapChars = members[kEnd + 1] - members[kEnd] - 1
                if (gapChars == 0) {
                    clusterEnd = if (nextStart > clusterStart) nextStart else inkEnd
                } else {
                    val e = min(inkEnd, max(nextStart, clusterStart))
                    clusterEnd = e
                    val spanEnd = max(nextStart, e)
                    val step = (spanEnd - e) / gapChars
                    for (m in 0 until gapChars) {
                        val idx = members[kEnd] + 1 + m - first
                        starts[idx] = e + step * m
                        ends[idx] = e + step * (m + 1)
                    }
                }
            }

            val width = if (clusterEnd > clusterStart + 1e-3f) clusterEnd - clusterStart else MIN_ADVANCE * runSize
            val share = width / (kEnd - k + 1)
            for (q in k..kEnd) {
                val idx = members[q] - first
                starts[idx] = clusterStart + share * (q - k)
                ends[idx] = clusterStart + share * (q - k + 1)
            }
            k = kEnd + 1
        }

        var crossMin: Float
        var crossMax: Float
        if (!vertical) {
            crossMin = -DESCENT * runSize
            crossMax = ASCENT * runSize
        } else {
            crossMin = Float.MAX_VALUE
            crossMax = -Float.MAX_VALUE
            for (k in 0 until count) {
                val i = members[k]
                if (!hasBox[i]) continue
                val originC = (ox[i] - originX) * crossX + (oy[i] - originY) * crossY
                val (lo, hi) = inkRange(i, crossX, crossY)
                crossMin = min(crossMin, originC + lo)
                crossMax = max(crossMax, originC + hi)
            }
            if (crossMin >= crossMax) {
                crossMin = -runSize / 2f
                crossMax = runSize / 2f
            }
        }
        runs += TextRun(
            first = first, last = last,
            originX = originX, originY = originY,
            flowX = flowX, flowY = flowY,
            crossX = crossX, crossY = crossY,
            crossMin = crossMin, crossMax = crossMax,
            starts = starts, ends = ends,
            size = runSize, vertical = vertical,
        )
        count = 0
    }

    for (i in 0 until n) {
        val cp = cps[i]
        if (isLineBreak(cp)) {
            // Explicit newlines distinguish a new horizontal line from vertical text with similar geometry.

            flush()
            continue
        }

        if (isSpace(cp)) continue
        if (count == 0) {
            startRun(i)
            continue
        }
        val p = members[count - 1]
        val ref = max(size[p], size[i])
        val sameAngle = dx[i] * dx[p] + dy[i] * dy[p] >= COS_ANGLE_TOLERANCE
        val ddx = ox[i] - ox[p]
        val ddy = oy[i] - oy[p]
        var joined = false
        if (sameAngle) {
            val along = ddx * flowX + ddy * flowY
            val across = ddx * crossX + ddy * crossY
            val extentP = max(inkRange(p, flowX, flowY).second, 0f)
            if (abs(across) <= CROSS_TOLERANCE * ref &&
                along >= -BACKTRACK_TOLERANCE * ref &&
                along <= extentP + GAP_TOLERANCE * ref
            ) {
                joined = true
            } else if (count == 1 &&
                abs(along) <= CROSS_TOLERANCE * ref &&
                across <= -MIN_VERTICAL_STEP * ref &&
                across >= -(GAP_TOLERANCE + 1f) * ref
            ) {
                vertical = true
                flowX = -crossX
                flowY = -crossY
                crossX = -flowY
                crossY = flowX
                joined = true
            }
        }
        if (joined) {
            members[count++] = i
        } else {
            flush()
            startRun(i)
        }
    }
    flush()
    return PageText(cps, runs)
}

private const val ASCENT = 0.85f
private const val DESCENT = 0.25f

private const val MIN_ADVANCE = 0.3f

private const val MIN_SIZE = 0.1f

private const val COS_ANGLE_TOLERANCE = 0.99939f

private const val CROSS_TOLERANCE = 0.5f

private const val BACKTRACK_TOLERANCE = 0.5f

private const val GAP_TOLERANCE = 2f

private const val MIN_VERTICAL_STEP = 0.3f

private const val SAME_ORIGIN = 0.02f
