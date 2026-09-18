package com.stb6.spdf.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Use a fixed contrasting color when the underlying content does not follow the app theme. */
fun topBarScrim(color: Color): Brush = Brush.verticalGradient(
    0f to color,
    0.62f to color.copy(alpha = 0.88f),
    0.85f to color.copy(alpha = 0.55f),
    1f to Color.Transparent,
)
