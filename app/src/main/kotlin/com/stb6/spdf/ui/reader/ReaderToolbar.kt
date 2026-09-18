package com.stb6.spdf.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.stb6.spdf.R
import com.stb6.spdf.ui.tapTarget
import com.stb6.spdf.ui.theme.Dimens
import com.stb6.spdf.ui.theme.topBarScrim
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable

private const val TOOLBAR_ANIMATION_DURATION_MILLIS = 180

@Composable
fun ReaderToolbar(
    fileName: String,
    pageLabel: String,
    pageLabelVisible: Boolean,
    docDarkOn: Boolean,
    onToggleDocumentDark: () -> Unit,
    onOpenOutline: () -> Unit,
    onOpenQuickSettings: () -> Unit,
    onSaveAs: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val animationSpec = tween<Float>(TOOLBAR_ANIMATION_DURATION_MILLIS)

    Box(modifier = modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.TopCenter)) {
            ReaderTopBar(
                fileName = fileName,
                docDarkOn = docDarkOn,
                onToggleDocumentDark = onToggleDocumentDark,
                onOpenQuickSettings = onOpenQuickSettings,
                onSaveAs = onSaveAs,
                onShare = onShare,
            )
        }

        AnimatedVisibility(
            visible = pageLabelVisible,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            enter = fadeIn(animationSpec) + slideInVertically(
                animationSpec = tween(TOOLBAR_ANIMATION_DURATION_MILLIS),
                initialOffsetY = { it },
            ),
            exit = fadeOut(animationSpec),
        ) {
            Text(
                text = pageLabel,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(ToolbarScrim.copy(alpha = 0.9f))
                    .clickable(onClick = onOpenOutline)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                color = ToolbarContent,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun ReaderTopBar(
    fileName: String,
    docDarkOn: Boolean,
    onToggleDocumentDark: () -> Unit,
    onOpenQuickSettings: () -> Unit,
    onSaveAs: () -> Unit,
    onShare: () -> Unit,
) {
    var moreExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(topBarScrim(ToolbarScrim))
            .statusBarsPadding()
            .heightIn(min = Dimens.TopBarHeight)
            .padding(
                start = Dimens.TopBarStartPadding,
                end = Dimens.TopBarEndPadding,
                bottom = Dimens.TopBarScrimTail,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = fileName,
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
                .padding(end = 8.dp),
            maxLines = 1,
            softWrap = false,
            color = ToolbarContent,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.size(4.dp))
        IconButton(onClick = onToggleDocumentDark, modifier = Modifier.size(Dimens.TopBarIconSize)) {
            DocDarkIcon(
                on = docDarkOn,
                contentDescription = stringResource(
                    if (docDarkOn) R.string.reader_toolbar_doc_dark_on else R.string.reader_toolbar_doc_dark_off,
                ),
            )
        }
        IconButton(onClick = onOpenQuickSettings, modifier = Modifier.size(Dimens.TopBarIconSize)) {
            Icon(
                painter = painterResource(R.drawable.ic_settings),
                contentDescription = stringResource(
                    R.string.reader_toolbar_document_settings_description,
                ),
                tint = ToolbarContent,
            )
        }
        Box {
            IconButton(onClick = { moreExpanded = true }, modifier = Modifier.size(Dimens.TopBarIconSize)) {
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert),
                    contentDescription = stringResource(R.string.reader_toolbar_more),
                    tint = ToolbarContent,
                )
            }
            DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                MenuRow(stringResource(R.string.reader_share)) { moreExpanded = false; onShare() }
                MenuRow(stringResource(R.string.reader_save_as)) { moreExpanded = false; onSaveAs() }
            }
        }
    }
}

@Composable
private fun DocDarkIcon(
    on: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val color = ToolbarContent

    Canvas(
        modifier = modifier
            .size(24.dp)
            .semantics { this.contentDescription = contentDescription },
    ) {
        val outer = Path().apply {
            addOval(Rect(3.dp.toPx(), 2.dp.toPx(), 21.dp.toPx(), 20.dp.toPx()))
        }
        val cutout = Path().apply {
            addOval(Rect(9.dp.toPx(), 0.dp.toPx(), 23.dp.toPx(), 14.dp.toPx()))
        }
        val crescent = Path.combine(PathOperation.Difference, outer, cutout)
        val outline = Stroke(1.8.dp.toPx())

        if (on) {
            drawPath(crescent, color, style = Fill)
        } else {
            drawPath(crescent, color, style = outline)
            val from = Offset(4.5.dp.toPx(), 19.5.dp.toPx())
            val to = Offset(19.5.dp.toPx(), 4.5.dp.toPx())
            drawLine(ToolbarScrim, from, to, strokeWidth = 4.4.dp.toPx(), cap = StrokeCap.Round)
            drawLine(color, from, to, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

// Reader chrome must contrast with PDF content independently of the app theme.
private val ToolbarScrim = Color(0xF7101012)
private val ToolbarContent = Color(0xFFF2F2F2)

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .tapTarget(onClick = onClick)
            .heightIn(min = Dimens.SettingsRowMinHeight)
            .fillMaxWidth()
            .padding(horizontal = MENU_ITEM_HORIZONTAL_PADDING)
            .wrapContentHeight(Alignment.CenterVertically),
    )
}

private val MENU_ITEM_HORIZONTAL_PADDING = 20.dp
