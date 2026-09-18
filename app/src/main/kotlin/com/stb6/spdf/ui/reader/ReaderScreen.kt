package com.stb6.spdf.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import android.content.ClipData
import android.os.Build
import android.widget.Toast
import com.stb6.spdf.R
import com.stb6.spdf.ui.ErrorDialog
import com.stb6.spdf.ui.UiText
import com.stb6.spdf.ui.resolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.ColorFilter
import com.stb6.spdf.data.Axis
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.keepScreenOn
import com.stb6.spdf.data.ReaderSettings
import com.stb6.spdf.pdf.DocDarkMatrices
import com.stb6.spdf.pdf.DocDarkStyle

@Composable
fun ReaderScreen(
    state: ReaderUiState.Ready,
    viewModel: ReaderViewModel,
    settings: ReaderSettings,
    darkStyle: DocDarkStyle?,
    onOpenUrl: (String) -> Unit,
    onBusyChange: (Boolean) -> Unit,
) {
    val colorFilter = darkStyle?.let { ColorFilter.colorMatrix(DocDarkMatrices.of(it)) }
    val selection by viewModel.selection.collectAsState()
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.textExtractionWarnings.collect {
            Toast.makeText(context, R.string.selection_text_unavailable, Toast.LENGTH_SHORT).show()
        }
    }
    val scope = rememberCoroutineScope()
    var copying by remember { mutableStateOf(false) }
    var copyError by remember { mutableStateOf<UiText?>(null) }
    val copySelection: () -> Unit = {
        if (!copying) scope.launch {
            val expected = viewModel.selection.value ?: return@launch
            copying = true
            try {
                val text = viewModel.selectionText()?.takeIf { it.isNotBlank() } ?: return@launch
                if (viewModel.selection.value !== expected) return@launch
                clipboard.setClipEntry(ClipData.newPlainText(null, text).toClipEntry())
                if (viewModel.selection.value === expected) viewModel.clearSelection()
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && !viewModel.textExtractionWarningIssued) {
                    Toast.makeText(context, R.string.selection_copied, Toast.LENGTH_SHORT).show()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SelectionTooLargeException) {
                copyError = UiText.Res(R.string.selection_too_large)
            } catch (failure: Exception) {
                copyError = UiText.Raw(failure.toString())
            } finally {
                copying = false
            }
        }
    }
    copyError?.let {
        ErrorDialog(
            title = stringResource(R.string.selection_copy_failed),
            message = if (it is UiText.Res) it.resolve() else stringResource(R.string.selection_copy_failed_message),
            detail = (it as? UiText.Raw)?.value,
            detailLabel = stringResource(R.string.reader_error_detail_title),
            confirmLabel = stringResource(R.string.reader_error_close),
            onDismiss = { copyError = null },
        )
    }

    // Later BackHandlers win; selection cancellation precedes zoom reset.
    BackHandler(enabled = selection != null) { viewModel.clearSelection() }

    Box(Modifier.fillMaxSize().keepScreenOn(settings.keepScreenOn)) {
        CanvasReader(
            state = state,
            viewModel = viewModel,
            settings = settings,
            colorFilter = colorFilter,
            onOpenUrl = onOpenUrl,
            onBusyChange = onBusyChange,
            onCopySelection = copySelection,
        )
    }
}

private fun Modifier.keepScreenOn(enabled: Boolean): Modifier =
    if (enabled) this.keepScreenOn() else this
