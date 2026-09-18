package com.stb6.spdf.ui.reader

internal class RenderScalePolicy {
    private var tier = Float.NaN
    private var target = Float.NaN

    fun update(scale: Float, zooming: Boolean): Float {
        if (!scale.isFinite() || scale <= 0f) return Float.NaN
        if (!zooming || tier.isNaN()) {
            tier = Math.scalb(1.0, Math.getExponent(scale.toDouble())).toFloat()
            target = scale
            return target
        }
        var next = tier
        while (scale >= next * 2f) next *= 2f
        while (scale < next * 0.75f) next *= 0.5f
        if (next != tier) {
            tier = next
            target = next
        }
        return target
    }
}
