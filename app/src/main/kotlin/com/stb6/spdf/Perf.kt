package com.stb6.spdf

import android.util.Log
import android.view.Choreographer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object Perf {

    @PublishedApi
    internal val enabled: Boolean = BuildConfig.DEBUG

    @PublishedApi
    internal const val TAG = "SPDFPERF"
    private const val DUMP_INTERVAL_MS = 3_000L

    private const val FRAME_SAMPLES = 512

    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val timers = ConcurrentHashMap<String, Timer>()

    private class Timer {
        val count = AtomicLong()
        val totalNs = AtomicLong()
        val maxNs = AtomicLong()
    }

    private val frameGaps = LongArray(FRAME_SAMPLES)
    private var frameCount = 0
    private var lastFrameNs = 0L
    private var lastDumpMs = 0L

    private var frameCallback: Choreographer.FrameCallback? = null

    fun count(name: String, delta: Long = 1) {
        if (!enabled) return
        counters.getOrPut(name) { AtomicLong() }.addAndGet(delta)
    }

    inline fun <T> time(name: String, block: () -> T): T {
        if (!enabled) return block()
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            record(name, System.nanoTime() - start)
        }
    }

    fun record(name: String, elapsedNs: Long) {
        if (!enabled) return
        val timer = timers.getOrPut(name) { Timer() }
        timer.count.incrementAndGet()
        timer.totalNs.addAndGet(elapsedNs)

        if (elapsedNs > timer.maxNs.get()) timer.maxNs.set(elapsedNs)
    }

    fun startFrameMonitor() {
        if (!enabled || frameCallback != null) return
        lastDumpMs = System.currentTimeMillis()
        lastFrameNs = 0L
        frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (frameCallback !== this) return
                onFrame(frameTimeNanos)
                Choreographer.getInstance().postFrameCallback(this)
            }
        }.also { Choreographer.getInstance().postFrameCallback(it) }
    }

    fun stopFrameMonitor() {
        val callback = frameCallback ?: return
        frameCallback = null
        Choreographer.getInstance().removeFrameCallback(callback)
        dump()
    }

    private fun onFrame(frameTimeNanos: Long) {
        if (lastFrameNs != 0L && frameCount < FRAME_SAMPLES) {
            frameGaps[frameCount++] = frameTimeNanos - lastFrameNs
        }
        lastFrameNs = frameTimeNanos

        val now = System.currentTimeMillis()
        if (now - lastDumpMs >= DUMP_INTERVAL_MS) {
            lastDumpMs = now
            dump()
        }
    }

    fun dump() {
        val hasFrames = frameCount > 2
        val hasWork = counters.values.any { it.get() > 0 } || timers.values.any { it.count.get() > 0 }
        if (!hasFrames && !hasWork) return

        if (hasFrames) {
            val gaps = frameGaps.copyOf(frameCount).apply { sort() }
            val p50 = gaps[frameCount / 2]

            val threshold = p50 * 3 / 2
            val janky = gaps.count { it > threshold }
            Log.i(
                TAG,
                "帧 %d 掉 %d(%.1f%%) 节奏 p50=%.1f p90=%.1f p99=%.1f 最长 %.1fms".format(
                    frameCount, janky, janky * 100.0 / frameCount,
                    p50 / 1e6, gaps[frameCount * 9 / 10] / 1e6,
                    gaps[frameCount * 99 / 100] / 1e6, gaps[frameCount - 1] / 1e6,
                ),
            )
        }
        for ((name, value) in counters.entries.sortedBy { it.key }) {
            val n = value.getAndSet(0)
            if (n > 0) Log.i(TAG, "计数 %-28s %d".format(name, n))
        }
        for ((name, timer) in timers.entries.sortedBy { it.key }) {
            val n = timer.count.getAndSet(0)
            if (n == 0L) continue
            val total = timer.totalNs.getAndSet(0)
            val max = timer.maxNs.getAndSet(0)
            Log.i(
                TAG,
                "耗时 %-28s %d 次 共 %.1fms 均 %.2fms 峰 %.1fms".format(
                    name, n, total / 1e6, total / 1e6 / n, max / 1e6,
                ),
            )
        }
        frameCount = 0
    }

    inline fun mark(message: () -> String) {
        if (enabled) Log.i(TAG, "事件 ${message()}")
    }
}
