package com.stb6.spdf.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** At least one of message or detail must be present. */
@Composable
fun ErrorDialog(
    title: String,
    message: String?,
    detail: String?,
    confirmLabel: String,
    onDismiss: () -> Unit,

    detailLabel: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DETAIL_GAP)) {
                if (message != null) Text(text = message, style = MaterialTheme.typography.bodyMedium)
                if (!detail.isNullOrBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(DETAIL_LABEL_GAP)) {
                        if (message != null && detailLabel != null) {
                            Text(
                                text = detailLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val scroll = rememberScrollState()
                        SelectionContainer {
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .heightIn(max = DETAIL_MAX_HEIGHT)
                                    .scrollbar(scroll, MaterialTheme.colorScheme.outline)
                                    .verticalScroll(scroll),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(confirmLabel) } },
    )
}

/** Place before verticalScroll to draw in viewport coordinates. */
private fun Modifier.scrollbar(state: ScrollState, color: Color): Modifier = drawWithContent {
    drawContent()
    val viewport = size.height
    val total = viewport + state.maxValue
    if (state.maxValue <= 0 || viewport <= 0f) return@drawWithContent
    val barHeight = (viewport * viewport / total)
        .coerceAtLeast(SCROLLBAR_MIN_LENGTH.toPx())
        .coerceAtMost(viewport)
    val barTop = (state.value / total * viewport).coerceIn(0f, viewport - barHeight)
    val width = SCROLLBAR_WIDTH.toPx()
    drawRoundRect(
        color = color.copy(alpha = SCROLLBAR_ALPHA),
        topLeft = Offset(size.width - width, barTop),
        size = Size(width, barHeight),
        cornerRadius = CornerRadius(width / 2),
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

@Composable
fun DelayedLoading(modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SPINNER_DELAY_MILLIS)
        slow = true
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible = slow, enter = fadeIn()) {
            if (color.isSpecified) CircularProgressIndicator(color = color) else CircularProgressIndicator()
        }
    }
}

private const val SPINNER_DELAY_MILLIS = 220L

private val DETAIL_GAP = 12.dp

private val DETAIL_LABEL_GAP = 4.dp

private val DETAIL_MAX_HEIGHT = 160.dp

private val SCROLLBAR_WIDTH = 3.dp

private val SCROLLBAR_MIN_LENGTH = 24.dp

private const val SCROLLBAR_ALPHA = 0.5f
