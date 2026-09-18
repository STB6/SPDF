package com.stb6.spdf.pdf

import android.graphics.RectF

data class PdfLink(
    val bounds: RectF,
    val uri: String?,
    val destPage: Int?,
)
