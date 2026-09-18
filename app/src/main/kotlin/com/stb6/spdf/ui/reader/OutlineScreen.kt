package com.stb6.spdf.ui.reader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import com.stb6.spdf.ui.tapTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stb6.spdf.R
import com.stb6.spdf.pdf.PdfOutlineEntry
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material3.Surface
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.rotate
import com.stb6.spdf.ui.theme.Dimens

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OutlineScreen(
    fileName: String,
    pageCount: Int,
    currentPage: Int,
    outline: List<PdfOutlineEntry>,
    onJumpTo: (Int) -> Unit,
    onClose: () -> Unit,
) {

    val tree = remember(outline) { outline.pruned() }

    val defaultDepth = remember(tree) {
        if (tree.totalCount() <= SMALL_OUTLINE_LIMIT) Int.MAX_VALUE else DEFAULT_EXPANDED_DEPTH
    }
    var expanded by rememberSaveable(tree, stateSaver = ExpandedSaver) {
        mutableStateOf(tree.ancestorsOfPage(currentPage).associateWith { true })
    }
    var pageText by rememberSaveable { mutableStateOf((currentPage + 1).toString()) }
    val rows = remember(tree, expanded, defaultDepth) {
        tree.visibleRows(expanded, defaultDepth)
    }
    val listState = rememberLazyListState()
    var positioned by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (!imeVisible) focusManager.clearFocus()
    }

    LaunchedEffect(tree) {
        if (positioned || tree.isEmpty()) return@LaunchedEffect
        val at = rows.indexOfLast { it.entry.pageIndex in 0..currentPage }
        if (at > 0) listState.scrollToItem(at)
        positioned = true
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .heightIn(min = Dimens.TopBarHeight)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = fileName,
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(remember { ScrollState(0) })
                        .padding(end = 16.dp),
                    maxLines = 1,
                    softWrap = false,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.outline_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        // The window already resizes for the IME; do not apply its inset twice.
        bottomBar = {
            Column(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.background)
                    .navigationBarsPadding(),
            ) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PageJumpRow(
                    text = pageText,
                    onTextChange = { pageText = it },
                    pageCount = pageCount,
                    onJumpTo = {
                        onJumpTo(it)
                        onClose()
                    },
                )
            }
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            if (rows.isEmpty()) {
                Text(
                    text = stringResource(R.string.outline_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                )
            } else {
                val highlighted = pageText.toIntOrNull()?.minus(1)
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScrollbar(listState, MaterialTheme.colorScheme.outline),
                ) {
                    items(rows, key = { it.key }) { row ->
                        OutlineRowItem(
                            row = row,
                            selected = row.entry.pageIndex >= 0 &&
                                row.entry.pageIndex == highlighted,
                            modifier = Modifier.animateItem(),
                            onLongClick = if (row.entry.pageIndex in 0 until pageCount) {
                                {
                                    focusManager.clearFocus()
                                    onJumpTo(row.entry.pageIndex)
                                    onClose()
                                }
                            } else null,
                            onClick = {
                                focusManager.clearFocus()
                                if (row.entry.pageIndex >= 0) {
                                    pageText = (row.entry.pageIndex + 1).toString()
                                }
                                if (row.hasChildren) {
                                    expanded = expanded + (row.key to !row.expanded)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PageJumpRow(
    text: String,
    onTextChange: (String) -> Unit,
    pageCount: Int,
    onJumpTo: (Int) -> Unit,
) {
    val target = text.toIntOrNull()?.minus(1)?.takeIf { it in 0 until pageCount }
    val valid = target != null

    fun submit() {
        target?.let(onJumpTo)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            BasicTextField(
                value = text,
                onValueChange = { input ->
                    onTextChange(input.filter(Char::isDigit).take(MAX_PAGE_DIGITS))
                },
                modifier = Modifier
                    .width(FIELD_WIDTH.dp)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    textAlign = TextAlign.Center,
                    color = if (valid || text.isEmpty()) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { submit() }),
            )
        }
        Text(
            text = "/",
            modifier = Modifier.padding(horizontal = 10.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = pageCount.toString(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Button(onClick = ::submit, enabled = valid) {
            Text(stringResource(R.string.outline_jump))
        }
    }
}

@Composable
private fun OutlineRowItem(
    row: OutlineRow,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)?,
    onClick: () -> Unit,
) {
    val selectable = row.entry.pageIndex >= 0
    val titleStyle = if (row.depth == 0) {
        MaterialTheme.typography.bodyLarge
    } else {
        MaterialTheme.typography.bodyMedium
    }
    val firstLine = with(LocalDensity.current) { titleStyle.lineHeight.toDp() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = SELECTED_TINT_ALPHA)
                } else {
                    Color.Transparent
                },
            )
            .padding(horizontal = ROW_TAP_INSET.dp)
            .tapTarget(onClick = onClick, onLongClick = onLongClick)
            .padding(
                start = (ROW_START_PADDING - ROW_TAP_INSET).dp + INDENT_PER_LEVEL.dp * row.depth,
                end = (ROW_END_PADDING - ROW_TAP_INSET).dp,
            ),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = ROW_VERTICAL_PADDING.dp)
                .size(width = MARKER_SIZE.dp, height = firstLine),
            contentAlignment = Alignment.Center,
        ) {
            if (row.hasChildren) ExpandMarker(expanded = row.expanded)
        }
        Text(
            text = row.entry.title.ifBlank { stringResource(R.string.outline_untitled) },
            modifier = Modifier
                .weight(1f)
                .padding(
                    start = 4.dp,
                    top = ROW_VERTICAL_PADDING.dp,
                    bottom = ROW_VERTICAL_PADDING.dp,
                    end = 8.dp,
                ),
            style = titleStyle,
            color = if (selectable) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (selectable) {
            Box(
                modifier = Modifier
                    .padding(vertical = ROW_VERTICAL_PADDING.dp)
                    .height(firstLine),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = (row.entry.pageIndex + 1).toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ExpandMarker(expanded: Boolean) {
    val angle by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "outline-marker",
    )
    Icon(
        painter = painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        modifier = Modifier
            .size(MARKER_GLYPH_SIZE.dp)
            .rotate(angle),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Modifier.verticalScrollbar(state: LazyListState, color: Color): Modifier {
    val alpha by animateFloatAsState(
        targetValue = if (state.isScrollInProgress) SCROLLBAR_ACTIVE_ALPHA else SCROLLBAR_IDLE_ALPHA,
        label = "outline-scrollbar",
    )
    return drawWithContent {
        drawContent()
        val info = state.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty() || info.totalItemsCount == 0) return@drawWithContent

        val averageItem = visible.sumOf { it.size }.toFloat() / visible.size
        val totalHeight = averageItem * info.totalItemsCount
        val viewport = size.height
        if (totalHeight <= viewport) return@drawWithContent

        val scrolled = state.firstVisibleItemIndex * averageItem +
            state.firstVisibleItemScrollOffset
        val barHeight = (viewport * viewport / totalHeight)
            .coerceAtLeast(SCROLLBAR_MIN_LENGTH.dp.toPx())
            .coerceAtMost(viewport)
        val barTop = ((scrolled / totalHeight) * viewport)
            .coerceIn(0f, viewport - barHeight)
        val width = SCROLLBAR_WIDTH.dp.toPx()

        drawRoundRect(
            color = color.copy(alpha = alpha),
            topLeft = Offset(size.width - width - SCROLLBAR_INSET.dp.toPx(), barTop),
            size = Size(width, barHeight),
            cornerRadius = CornerRadius(width / 2f),
        )
    }
}

private val ExpandedSaver = listSaver<Map<String, Boolean>, String>(
    save = { state -> state.map { (key, open) -> (if (open) "+" else "-") + key } },
    restore = { saved ->
        saved.filter { it.isNotEmpty() }.associate { it.substring(1) to (it[0] == '+') }
    },
)

private class OutlineRow(
    val key: String,
    val depth: Int,
    val entry: PdfOutlineEntry,
    val hasChildren: Boolean,
    val expanded: Boolean,
)

// Use tree paths as keys: bookmark titles need not be unique.
private fun List<PdfOutlineEntry>.visibleRows(
    expanded: Map<String, Boolean>,
    defaultExpandedDepth: Int,
): List<OutlineRow> {
    val rows = mutableListOf<OutlineRow>()

    fun walk(entries: List<PdfOutlineEntry>, depth: Int, path: String) {
        entries.forEachIndexed { index, entry ->
            val key = "$path/$index"
            val hasChildren = entry.children.isNotEmpty()
            val open = expanded[key] ?: (depth < defaultExpandedDepth)
            rows += OutlineRow(key, depth, entry, hasChildren, open)
            if (hasChildren && open) walk(entry.children, depth + 1, key)
        }
    }

    walk(this, 0, "")
    return rows
}

private fun List<PdfOutlineEntry>.pruned(): List<PdfOutlineEntry> =
    mapNotNull { entry ->
        val children = entry.children.pruned()
        if (entry.pageIndex >= 0 || children.isNotEmpty()) {
            entry.copy(children = children)
        } else {
            null
        }
    }

private fun List<PdfOutlineEntry>.totalCount(): Int =
    sumOf { 1 + it.children.totalCount() }

private fun List<PdfOutlineEntry>.ancestorsOfPage(pageIndex: Int): List<String> {
    var best: List<String>? = null

    fun walk(entries: List<PdfOutlineEntry>, path: String, ancestors: List<String>) {
        entries.forEachIndexed { index, entry ->
            val key = "$path/$index"
            if (entry.pageIndex in 0..pageIndex) best = ancestors
            walk(entry.children, key, ancestors + key)
        }
    }

    walk(this, "", emptyList())
    return best.orEmpty()
}

private const val INDENT_PER_LEVEL = 16

private const val MAX_PAGE_DIGITS = 5

private const val DEFAULT_EXPANDED_DEPTH = 1

private const val SMALL_OUTLINE_LIMIT = 40

private const val MARKER_SIZE = 20

private const val MARKER_GLYPH_SIZE = 14

private const val ROW_START_PADDING = 16

private const val ROW_END_PADDING = 20
private const val ROW_TAP_INSET = 12

private const val ROW_VERTICAL_PADDING = 10

private const val FIELD_WIDTH = 72

private const val SELECTED_TINT_ALPHA = 0.14f

private const val SCROLLBAR_WIDTH = 3
private const val SCROLLBAR_INSET = 2
private const val SCROLLBAR_MIN_LENGTH = 24
private const val SCROLLBAR_IDLE_ALPHA = 0.28f
private const val SCROLLBAR_ACTIVE_ALPHA = 0.9f
