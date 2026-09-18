package com.stb6.spdf.ui.settings

import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import com.stb6.spdf.ui.tapTarget
import kotlinx.coroutines.launch
import kotlinx.coroutines.Deferred
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stb6.spdf.ui.theme.Dimens
import kotlin.math.roundToInt

internal data class SettingOption<T>(val value: T, val label: String)

@Composable
internal fun SectionDivider(bottomPadding: Dp = Dimens.SectionGap) {
    HorizontalDivider(
        modifier = Modifier.padding(bottom = bottomPadding),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
internal fun SectionTitle(text: String, divider: Boolean = true, show: Boolean = true) {
    if (!show) {
        if (divider) SectionDivider(bottomPadding = 0.dp) else Spacer(Modifier.height(SECTION_TOP_GAP))
        return
    }
    Column(Modifier.fillMaxWidth()) {

        if (divider) SectionDivider() else Spacer(Modifier.height(SECTION_TOP_GAP))
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
internal fun SwitchItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supporting: String? = null,
    enabled: Boolean = true,
) {
    val alpha = if (enabled) 1f else Dimens.DisabledAlpha
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.SettingsRowMinHeight)
            .tapTarget(enabled = enabled, bleed = Dimens.TapBleed, role = Role.Switch, checked = checked) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            )
            supporting?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Keep the acknowledged preview until its authoritative value reaches the UI. */
@Composable
internal fun SliderItem(
    label: String,
    value: Int,
    range: IntRange,

    valueText: @Composable (Int) -> String,
    onChange: (Int) -> Deferred<Int?>,
    enabled: Boolean = true,

    preview: (@Composable (Int) -> Unit)? = null,
) {
    var draft by remember { mutableStateOf<Int?>(null) }
    var acknowledged by remember { mutableStateOf<Int?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(value, acknowledged) {
        if (acknowledged != null && value == acknowledged) { draft = null; acknowledged = null }
    }
    val local = (draft ?: value).coerceIn(range)
    val description = valueText(local)
    val alpha = if (enabled) 1f else Dimens.DisabledAlpha

    Column(Modifier.fillMaxWidth().padding(top = Dimens.SettingsMultiLineMargin)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            )
        }

        Slider(
            value = local.toFloat(),
            onValueChange = { revision++; acknowledged = null; draft = it.roundToInt().coerceIn(range) },
            onValueChangeFinished = {
                val submitted = draft
                if (submitted != null) {
                    val ticket = ++revision
                    val commit = onChange(submitted.coerceIn(range))
                    scope.launch {
                        val accepted = commit.await()
                        if (ticket == revision) {
                            draft = accepted
                            acknowledged = accepted
                        }
                    }
                }
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            enabled = enabled,
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = description
            },
        )
        preview?.invoke(local)
    }
}

@Composable
internal fun <T> SettingsChoice(
    label: String,
    options: List<SettingOption<T>>,
    selected: T,
    onSelected: (T) -> Unit,
    description: String? = null,
    enabled: Boolean = true,
) {
    val alpha = if (enabled) 1f else Dimens.DisabledAlpha
    Column(
        modifier = Modifier.padding(vertical = Dimens.SettingsMultiLineMargin),
        verticalArrangement = Arrangement.spacedBy(CHOICE_GAP),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option.value == selected,
                    onClick = { onSelected(option.value) },
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    // Preserve the selected state when disabled; M3 otherwise makes both states look alike.

                    colors = SegmentedButtonDefaults.colors(
                        disabledActiveContainerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = alpha),
                        disabledActiveContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                    ),
                ) { Text(option.label) }
            }
        }
        description?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> ChoiceSetting(
    title: String,
    value: T,
    choices: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    val label = choices.firstOrNull { it.first == value }?.second.orEmpty()
    val alpha = if (enabled) 1f else Dimens.DisabledAlpha

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.SettingsRowMinHeight)
            .tapTarget(enabled = enabled, bleed = Dimens.TapBleed) { open = true },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
        )
    }
    if (open) {
        val sheetState = rememberModalBottomSheetState()
        val scope = rememberCoroutineScope()
        // Finish the hide animation before removing the sheet from composition.
        fun close(then: () -> Unit = {}) {
            scope.launch { sheetState.hide() }.invokeOnCompletion { open = false; then() }
        }
        ModalBottomSheet(onDismissRequest = { open = false }, sheetState = sheetState) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = SHEET_HORIZONTAL_PADDING, vertical = SHEET_TITLE_VERTICAL_PADDING),
            )
            val current = choices.indexOfFirst { it.first == value }.coerceAtLeast(0)
            LazyColumn(
                modifier = Modifier.selectableGroup(),
                state = rememberLazyListState(initialFirstVisibleItemIndex = current),
                contentPadding = PaddingValues(bottom = SHEET_BOTTOM_PADDING),
            ) {
                items(choices) { (choice, choiceLabel) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Dimens.SettingsRowMinHeight)
                            .tapTarget(role = Role.RadioButton, selected = choice == value) { close { onSelect(choice) } }
                            .padding(horizontal = SHEET_HORIZONTAL_PADDING),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice == value, onClick = null)
                        Text(choiceLabel, modifier = Modifier.padding(start = SHEET_RADIO_GAP))
                    }
                }
            }
        }
    }
}

@Composable
internal fun ValueRow(
    label: String, value: String, modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.SettingsRowMinHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            color = valueColor,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

private val SHEET_HORIZONTAL_PADDING = 24.dp

private val SHEET_TITLE_VERTICAL_PADDING = 8.dp

private val SHEET_BOTTOM_PADDING = 16.dp

private val SHEET_RADIO_GAP = 12.dp

private val SECTION_TOP_GAP = 8.dp

private val CHOICE_GAP = 8.dp

/** Match the implicit vertical space around text in a single-line setting. */
