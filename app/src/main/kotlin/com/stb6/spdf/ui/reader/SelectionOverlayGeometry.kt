package com.stb6.spdf.ui.reader

import androidx.compose.ui.geometry.Rect

internal fun selectionOverlayBounds(
    text: List<Rect>,
    handles: List<Rect>,
    viewport: Rect,
): Rect? {
    fun clipped(rect: Rect): Rect? = rect.intersect(viewport).takeUnless { it.isEmpty }
    fun union(a: Rect, b: Rect) = Rect(
        minOf(a.left, b.left), minOf(a.top, b.top),
        maxOf(a.right, b.right), maxOf(a.bottom, b.bottom),
    )
    val visibleText = text.mapNotNull(::clipped)
    if (visibleText.isEmpty()) return null
    return (visibleText + handles.mapNotNull(::clipped)).reduce(::union)
}
