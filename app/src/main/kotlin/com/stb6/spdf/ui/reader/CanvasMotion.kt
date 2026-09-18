package com.stb6.spdf.ui.reader

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.Job
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Stable
internal class CanvasMotion(
    val position: CanvasPosition,
    val scope: CoroutineScope,
) {
    private var flingJob: Job? = null

    // Gesture cleanup must not clear a newly started animation.
    var gestureActive by mutableStateOf(false)
    var animationActive by mutableStateOf(false)

    var gestureZooming by mutableStateOf(false)
    private var animationZooming by mutableStateOf(false)
    val zooming: Boolean get() = gestureZooming || animationZooming
    val busy: Boolean get() = gestureActive || animationActive

    private var tapJob: Job? = null
    private var lastTapAt = 0L
    private var lastTapPos = Offset.Zero

    fun cancelFling() {
        flingJob?.cancel()
        flingJob = null
        // Cancellation does not wait for finally; clear flags before replacing the job.
        animationActive = false
        animationZooming = false
    }

    internal fun cancelPendingTap() {
        tapJob?.cancel()
    }

    internal fun clearTapHistory() {
        lastTapAt = 0L
    }

    internal fun registerTap(pos: Offset, now: Long, doubleTapMs: Long, slop: Float): Boolean {
        val double = now - lastTapAt < doubleTapMs && (pos - lastTapPos).getDistance() < slop
        if (double) {
            lastTapAt = 0L
        } else {
            lastTapAt = now
            lastTapPos = pos
        }
        return double
    }

    internal fun awaitDoubleTapThen(doubleTapMs: Long, action: suspend (Offset) -> Unit, pos: Offset) {
        tapJob?.cancel()
        tapJob = scope.launch {
            delay(doubleTapMs)
            action(pos)
        }
    }

    internal fun animateZoomTo(target: Float, pivot: Offset, rules: CanvasRules) {
        val from = position.scale.floatValue
        if (from <= 0f || from == target) return
        val baseX = position.offsetX.floatValue
        val baseY = position.offsetY.floatValue
        if (baseX.isNaN() || baseY.isNaN()) return
        animateWhileBusy(zoom = true) {
            animate(from, target, animationSpec = tween(ZOOM_MILLIS)) { s, _ ->
                val k = s / from
                position.scale.floatValue = s
                val anchored = Offset(
                    pivot.x - (pivot.x - baseX) * k,
                    pivot.y - (pivot.y - baseY) * k,
                )
                val at = rules.restingOffset(anchored, s) ?: anchored
                position.offsetX.floatValue = at.x
                position.offsetY.floatValue = at.y
            }
        }
    }

    internal fun animateWhileBusy(zoom: Boolean = false, block: suspend () -> Unit) {
        cancelFling()
        animationActive = true
        animationZooming = zoom
        var job: Job? = null
        // Assign the job before starting it, even if the block completes synchronously.
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                // A cancelled predecessor must not clear the current animation flags.
                if (flingJob === job) {
                    animationActive = false
                    animationZooming = false
                }
            }
        }
        flingJob = job
        job.start()
    }
}

@Composable
internal fun rememberCanvasMotion(position: CanvasPosition): CanvasMotion {
    val scope = rememberCoroutineScope()
    return remember(position, scope) { CanvasMotion(position, scope) }
}

@Composable
internal fun Modifier.canvasGestures(
    motion: CanvasMotion,
    rules: CanvasRules,
    fitScale: Float,
    minScale: Float,
    maxScale: Float,
    handleDragging: () -> Boolean,
    onLongPress: (Offset) -> Unit,
    onLongPressDrag: (Offset) -> Unit,
    onLongPressEnd: () -> Unit,
    onTap: suspend (Offset) -> Unit,
    layoutKey: Any?,
): Modifier {
    val decaySpec = rememberSplineBasedDecay<Float>()
    var scale by motion.position.scale
    var offsetX by motion.position.offsetX
    var offsetY by motion.position.offsetY
    val liveRules by rememberUpdatedState(rules)
    val liveFitScale by rememberUpdatedState(fitScale)
    val liveMinScale by rememberUpdatedState(minScale)
    val liveMaxScale by rememberUpdatedState(maxScale)
    val liveOnTap by rememberUpdatedState(onTap)

    // Clamp only after release; clamping a short axis during pinch moves the anchor.
    fun panFreely(dx: Float, dy: Float) {
        offsetX += dx
        offsetY += dy
    }

    fun glideX(dx: Float): Boolean {
        val nx = liveRules.glide(offsetX, dx, scale, axisX = true)
        if (nx == offsetX) return false
        offsetX = nx
        return true
    }

    fun glideY(dy: Float): Boolean {
        val ny = liveRules.glide(offsetY, dy, scale, axisX = false)
        if (ny == offsetY) return false
        offsetY = ny
        return true
    }

    suspend fun releaseAxis(
        rest: Float,
        read: () -> Float,
        write: (Float) -> Unit,
        velocity: Float,
        glide: (Float) -> Boolean,
    ) {
        val from = read()
        if (rest != from) {
            animate(0f, 1f, animationSpec = tween(SETTLE_MILLIS)) { t, _ ->
                write(from + (rest - from) * t)
            }
            return
        }
        if (velocity == 0f) return
        var last = 0f
        var hitEdgeAt = 0f
        AnimationState(0f, velocity).animateDecay(decaySpec) {
            val d = value - last
            last = value
            if (!glide(d)) {
                hitEdgeAt = this.velocity
                cancelAnimation()
            }
        }
        if (hitEdgeAt == 0f) return

        val edge = read()
        Animatable(edge).animateTo(
            targetValue = edge,
            animationSpec = spring(
                dampingRatio = EDGE_BOUNCE_DAMPING,
                stiffness = EDGE_BOUNCE_STIFFNESS,
            ),
            initialVelocity = hitEdgeAt,
        ) {
            write(value)
        }
    }

    fun zoomAround(pos: Offset) {
        val fit = liveFitScale
        val target = if (atFitScale(scale, fit)) {
            (fit * DOUBLE_TAP_ZOOM).coerceIn(liveMinScale, liveMaxScale)
        } else {
            fit.coerceIn(liveMinScale, liveMaxScale)
        }
        motion.animateZoomTo(target, pos, liveRules)
    }

    // Pending taps capture layout and are not cancelled by pointerInput recreation.
    DisposableEffect(layoutKey) {
        onDispose { motion.cancelPendingTap() }
    }

    return this.pointerInput(Unit) {
        val touchSlop = viewConfiguration.touchSlop
        val longPress = viewConfiguration.longPressTimeoutMillis
        val doubleTapMs = viewConfiguration.doubleTapTimeoutMillis
        awaitEachGesture {
            var selecting = false
            try {
                val down = awaitFirstDown(requireUnconsumed = false)
                motion.cancelPendingTap()
                if (handleDragging()) return@awaitEachGesture
                motion.gestureActive = true
                motion.cancelFling()
                val tracker = VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)

                var pastSlop = false
                var multiTouch = false
                var pointerCount = 1
                var takenByChild = false
                var accumulated = 0f
                var lastTime = down.uptimeMillis
                while (true) {
                    // Use the pointer scope timeout to handle PointerEventTimeoutCancellationException.
                    val event: PointerEvent? =
                        if (!pastSlop && !selecting && !multiTouch && !takenByChild) {
                            val remaining = longPress - (SystemClock.uptimeMillis() - down.uptimeMillis)
                            if (remaining <= 0) null else withTimeoutOrNull(remaining) { awaitPointerEvent() }
                        } else {
                            awaitPointerEvent()
                        }
                    if (event == null) {
                        selecting = true
                        onLongPress(down.position)
                        continue
                    }
                    if (selecting) {
                        event.changes.forEach { it.consume() }
                        val finger = event.changes.firstOrNull { it.id == down.id }
                        if (finger == null || !finger.pressed) break
                        onLongPressDrag(finger.position)
                        continue
                    }
                    if (!pastSlop && event.changes.any { it.isConsumed }) takenByChild = true
                    val pressed = event.changes.count { it.pressed }
                    // Pointer-count changes shift the centroid without representing a pan.
                    val countChanged = pressed != pointerCount
                    pointerCount = pressed
                    if (pressed >= 2) multiTouch = true
                    motion.gestureZooming = pressed >= 2
                    event.changes.firstOrNull()?.let { lastTime = it.uptimeMillis }

                    val pan = event.calculatePan()
                    val zoomChange = event.calculateZoom()

                    if (!pastSlop) {
                        if (multiTouch) {
                            pastSlop = true
                        } else if (!countChanged) {
                            accumulated += pan.getDistance()
                            if (accumulated > touchSlop) pastSlop = true
                        }
                    }

                    if (pastSlop && !countChanged && (pan != Offset.Zero || zoomChange != 1f)) {
                        if (zoomChange != 1f) {
                            // Scale around the previous centroid, then apply pan, to avoid anchor drift.
                            val centroid = event.calculateCentroid(useCurrent = false)
                            if (centroid.isSpecified) {
                                val next = (scale * zoomChange).coerceIn(liveMinScale, liveMaxScale)
                                val k = next / scale

                                val nx = centroid.x - (centroid.x - offsetX) * k
                                val ny = centroid.y - (centroid.y - offsetY) * k
                                scale = next
                                offsetX = nx
                                offsetY = ny
                            }
                        }
                        panFreely(pan.x, pan.y)
                        event.changes.forEach { it.consume() }
                    }

                    // A staggered pinch release must not seed a fling.
                    if (!multiTouch && pressed == 1) {
                        event.changes.firstOrNull()
                            ?.takeIf { it.pressed }
                            ?.let { tracker.addPosition(it.uptimeMillis, it.position) }
                    }
                    if (event.changes.none { it.pressed }) break
                }
                if (selecting) return@awaitEachGesture

                val isTap = !pastSlop && !takenByChild &&
                    (lastTime - down.uptimeMillis) < longPress
                if (isTap) {
                    val pos = down.position
                    val double = motion.registerTap(
                        pos = pos,
                        now = down.uptimeMillis,
                        doubleTapMs = doubleTapMs,
                        slop = touchSlop * 3,
                    )
                    if (double) zoomAround(pos) else motion.awaitDoubleTapThen(doubleTapMs, liveOnTap, pos)
                } else {
                    motion.clearTapHistory()
                    val here = Offset(offsetX, offsetY)
                    val rest = liveRules.restingOffset(here, scale) ?: here
                    val v = if (multiTouch) Velocity.Zero else tracker.calculateVelocity()

                    motion.animateWhileBusy {
                        coroutineScope {
                            launch { releaseAxis(rest.x, { offsetX }, { offsetX = it }, v.x, ::glideX) }
                            launch { releaseAxis(rest.y, { offsetY }, { offsetY = it }, v.y, ::glideY) }
                        }
                    }
                }
            } finally {
                if (selecting) onLongPressEnd()
                motion.gestureActive = false
                motion.gestureZooming = false
            }
        }
    }
}

private const val EDGE_BOUNCE_DAMPING = 0.72f
private const val EDGE_BOUNCE_STIFFNESS = 380f

private const val DOUBLE_TAP_ZOOM = 2f

private const val FIT_EPSILON = 0.01f

internal fun atFitScale(scale: Float, fitScale: Float): Boolean =
    abs(scale - fitScale) <= fitScale * FIT_EPSILON

private const val ZOOM_MILLIS = 260

private const val SETTLE_MILLIS = 220

class CanvasPosition {
    val scale = mutableFloatStateOf(1f)

    // NaN means the initial position has not been resolved.
    val offsetX = mutableFloatStateOf(Float.NaN)
    val offsetY = mutableFloatStateOf(Float.NaN)
    val lastPage = mutableIntStateOf(0)

    var anchorMain = Float.NaN

    var anchorCross = Float.NaN

    internal var lastStructure: CanvasStructure? = null

    fun reset() {
        scale.floatValue = 1f
        offsetX.floatValue = Float.NaN
        offsetY.floatValue = Float.NaN
        lastPage.intValue = 0
        anchorMain = Float.NaN
        anchorCross = Float.NaN
        lastStructure = null
    }
}
