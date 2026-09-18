package com.stb6.spdf.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stb6.spdf.ui.theme.Dimens

fun Modifier.tapTarget(
    enabled: Boolean = true,
    bleed: Dp = 0.dp,
    onLongClick: (() -> Unit)? = null,
    role: Role? = null,
    checked: Boolean? = null,
    selected: Boolean? = null,
    onClick: () -> Unit,
): Modifier {
    val press = when {
        checked != null -> Modifier.toggleable(value = checked, enabled = enabled, role = role) { onClick() }
        selected != null -> Modifier.selectable(selected = selected, enabled = enabled, role = role, onClick = onClick)
        onLongClick != null -> Modifier.combinedClickable(enabled = enabled, role = role, onLongClick = onLongClick, onClick = onClick)
        else -> Modifier.clickable(enabled = enabled, role = role, onClick = onClick)
    }
    return bleed(bleed)
        .clip(RoundedCornerShape(Dimens.TapCornerRadius))
        .then(press)
        .padding(horizontal = bleed)
}

/** Expand drawing and hit bounds without changing the width reported to the parent. */
private fun Modifier.bleed(amount: Dp): Modifier = if (amount == 0.dp) this else layout { measurable, constraints ->
    val extra = (amount * 2).roundToPx()
    val maxWidth = if (constraints.maxWidth == Constraints.Infinity) constraints.maxWidth else constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = maxWidth))
    layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
}
