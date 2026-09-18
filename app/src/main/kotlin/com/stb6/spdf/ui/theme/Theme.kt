package com.stb6.spdf.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

/** Follow system appearance so edge-to-edge system icons keep the correct contrast. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

object StatusColors {
    val ok: Color
        @Composable get() = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) Color(0xFF166534) else Color(0xFF2ECC71)
    val bad: Color
        @Composable get() = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) Color(0xFFB3261E) else Color(0xFFFF8A80)
}
