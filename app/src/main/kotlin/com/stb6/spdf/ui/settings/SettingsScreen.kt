package com.stb6.spdf.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.stb6.spdf.BuildConfig
import com.stb6.spdf.R
import com.stb6.spdf.data.PAGE_LABEL_RANGE
import com.stb6.spdf.data.PAGE_LABEL_ALWAYS
import com.stb6.spdf.data.DocDarkTrigger
import com.stb6.spdf.data.ReaderSettings
import com.stb6.spdf.data.ScrollDirection
import com.stb6.spdf.pdf.DocDarkStyle
import com.stb6.spdf.ui.ConfirmDialog
import androidx.compose.material3.OutlinedButton
import com.stb6.spdf.ui.theme.Dimens
import androidx.compose.ui.input.nestedscroll.nestedScroll

@Composable
private fun directionOptions() = listOf(
    SettingOption(ScrollDirection.DOWN, stringResource(R.string.settings_direction_down)),
    SettingOption(ScrollDirection.RIGHT, stringResource(R.string.settings_direction_right)),
    SettingOption(ScrollDirection.LEFT, stringResource(R.string.settings_direction_left)),
)

@Composable
private fun docDarkTriggerOptions() = listOf(
    SettingOption(DocDarkTrigger.OFF, stringResource(R.string.settings_doc_dark_trigger_off)),
    SettingOption(DocDarkTrigger.ON, stringResource(R.string.settings_doc_dark_trigger_on)),
    SettingOption(DocDarkTrigger.FOLLOW_SYSTEM, stringResource(R.string.settings_follow_system)),
)

@Composable
private fun docDarkStyleOptions() = listOf(
    SettingOption(DocDarkStyle.INVERT, stringResource(R.string.settings_doc_dark_style_invert)),
    SettingOption(DocDarkStyle.SMART, stringResource(R.string.settings_doc_dark_style_smart)),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: ReaderSettings,

    onChange: ((ReaderSettings) -> ReaderSettings) -> Deferred<ReaderSettings?>,
) {
    var confirmRestore by remember { mutableStateOf(false) }

    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                scrollBehavior = scroll,
            )
        },
    ) { contentPadding ->
        SettingsContent(
            settings = settings,
            onChange = onChange,
            resetLabel = stringResource(R.string.settings_restore_defaults),
            onReset = { confirmRestore = true },
            showAbout = true,
            modifier = Modifier.fillMaxSize().padding(contentPadding),
        )
    }

    if (confirmRestore) {

        ConfirmDialog(
            title = stringResource(R.string.settings_restore_defaults),
            text = stringResource(R.string.settings_restore_defaults_message),
            confirmLabel = stringResource(R.string.settings_restore_defaults_confirm),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                onChange { ReaderSettings() }
                confirmRestore = false
            },
            onDismiss = { confirmRestore = false },
            destructive = true,
        )
    }
}

@Composable
fun SettingsContent(
    settings: ReaderSettings,

    onChange: ((ReaderSettings) -> ReaderSettings) -> Deferred<ReaderSettings?>,
    resetLabel: String,
    onReset: () -> Unit,
    showAbout: Boolean,
    modifier: Modifier = Modifier,

    sessionScope: Boolean = false,
) {
    val commitScope = rememberCoroutineScope()
    fun change(transform: (ReaderSettings) -> ReaderSettings) { onChange(transform) }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = Dimens.SettingsMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = Dimens.SettingsHorizontalPadding)
                .padding(bottom = Dimens.SettingsItemGap),
            verticalArrangement = Arrangement.spacedBy(Dimens.SettingsItemGap),
        ) {
            SectionTitle(stringResource(R.string.settings_section_reading), divider = false)
            SettingsChoice(
                label = stringResource(R.string.settings_scroll_direction),
                options = directionOptions(),
                selected = settings.scrollDirection,
                onSelected = { value -> change { it.copy(scrollDirection = value) } },
            )

            SectionTitle(text = stringResource(R.string.settings_section_display))
            if (sessionScope) {
                SwitchItem(
                    title = stringResource(R.string.settings_doc_dark_trigger),
                    checked = settings.docDarkTrigger == DocDarkTrigger.ON,
                    onCheckedChange = { enabled ->
                        change { it.copy(docDarkTrigger = if (enabled) DocDarkTrigger.ON else DocDarkTrigger.OFF) }
                    },
                )
            } else {
                SettingsChoice(
                    label = stringResource(R.string.settings_doc_dark_trigger),
                    options = docDarkTriggerOptions(),
                    selected = settings.docDarkTrigger,
                    onSelected = { value -> change { it.copy(docDarkTrigger = value) } },
                )
            }

            val docDarkOn = settings.docDarkTrigger != DocDarkTrigger.OFF
            SwitchItem(
                title = stringResource(R.string.settings_smart_skip_dark_documents),
                checked = settings.smartSkipDarkDocuments,
                enabled = docDarkOn,
                onCheckedChange = { value -> change { it.copy(smartSkipDarkDocuments = value) } },
            )
            SettingsChoice(
                label = stringResource(R.string.settings_doc_dark_style),
                options = docDarkStyleOptions(),
                selected = settings.docDarkStyle,
                description = stringResource(docDarkStyleDescription(settings.docDarkStyle)),
                enabled = docDarkOn,
                onSelected = { value -> change { it.copy(docDarkStyle = value) } },
            )
            SliderItem(
                label = stringResource(R.string.settings_page_label_duration),
                value = settings.pageLabelHalfSeconds,
                range = PAGE_LABEL_RANGE,
                valueText = { halfSeconds ->
                    when (halfSeconds) {
                        0 -> stringResource(R.string.settings_page_label_immediate)
                        PAGE_LABEL_ALWAYS -> stringResource(R.string.settings_page_label_always)
                        else -> stringResource(
                            R.string.settings_page_label_seconds,
                            formatHalfSeconds(halfSeconds),
                        )
                    }
                },
                onChange = { value ->
                    val commit = onChange { it.copy(pageLabelHalfSeconds = value) }
                    commitScope.async { commit.await()?.pageLabelHalfSeconds }
                },
            )
            SwitchItem(
                title = stringResource(R.string.settings_keep_screen_on),
                checked = settings.keepScreenOn,
                onCheckedChange = { value -> change { it.copy(keepScreenOn = value) } },
            )

            SectionTitle(text = stringResource(R.string.settings_section_gestures))
            SwitchItem(
                title = stringResource(R.string.settings_back_resets_zoom),
                checked = settings.backResetsZoom,
                onCheckedChange = { value -> change { it.copy(backResetsZoom = value) } },
            )

            SectionDivider(bottomPadding = 0.dp)
            OutlinedButton(onClick = onReset) {
                Text(resetLabel)
            }

            if (showAbout) {
                SectionTitle(text = stringResource(R.string.settings_section_about))
                ValueRow(
                    label = stringResource(R.string.settings_version),
                    value = BuildConfig.VERSION_NAME,
                )
                ValueRow(
                    label = stringResource(R.string.settings_license),
                    value = stringResource(R.string.app_license),
                )
            }
        }
    }
}

private fun formatHalfSeconds(halfSeconds: Int): String =
    if (halfSeconds % 2 == 0) "${halfSeconds / 2}" else "${halfSeconds / 2}.5"

@StringRes
private fun docDarkStyleDescription(value: DocDarkStyle): Int = when (value) {
    DocDarkStyle.INVERT -> R.string.settings_doc_dark_style_invert_description
    DocDarkStyle.SMART -> R.string.settings_doc_dark_style_smart_description
}
