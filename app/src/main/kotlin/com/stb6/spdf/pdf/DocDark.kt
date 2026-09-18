package com.stb6.spdf.pdf

import androidx.compose.ui.graphics.ColorMatrix

enum class DocDarkStyle {
    INVERT,

    SMART,
}

object DocDarkMatrices {
    // SMART = saturation(0.85) * hueRotate(180 degrees) * invert; luminance weights: 0.213, 0.715, 0.072.
    // ColorMatrix offsets use 0..255, not 0..1.
    fun of(style: DocDarkStyle): ColorMatrix = when (style) {
        DocDarkStyle.INVERT -> ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )

        DocDarkStyle.SMART -> ColorMatrix(
            floatArrayOf(
                0.455950000f, -1.322750000f, -0.133200000f, 0f, 255f,
                -0.394050000f, -0.472750000f, -0.133200000f, 0f, 255f,
                -0.394050000f, -1.322750000f, 0.716800000f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
    }
}
