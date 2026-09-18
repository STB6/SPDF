package com.stb6.spdf

import android.content.ClipboardManager
import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.withStarted
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.stb6.spdf.data.DocumentExportViewModel
import com.stb6.spdf.data.DataStoreSettingsRepository
import com.stb6.spdf.data.DocDarkTrigger
import com.stb6.spdf.data.PAGE_LABEL_ALWAYS
import com.stb6.spdf.ui.ErrorDialog
import com.stb6.spdf.ui.reader.ErrorState
import com.stb6.spdf.ui.reader.LoadingState
import com.stb6.spdf.ui.reader.OutlineScreen
import com.stb6.spdf.ui.reader.PasswordDialog
import com.stb6.spdf.ui.reader.ReaderScreen
import com.stb6.spdf.ui.reader.ReaderToolbar
import com.stb6.spdf.ui.reader.ReaderUiState
import com.stb6.spdf.ui.reader.ReaderViewModel
import com.stb6.spdf.ui.reader.resolveDarkStyle
import com.stb6.spdf.ui.settings.DocumentSettingsScreen
import com.stb6.spdf.ui.settings.SettingsScreen
import com.stb6.spdf.ui.theme.AppTheme
import com.stb6.spdf.ui.theme.pageEnter
import com.stb6.spdf.ui.theme.pageExit
import com.stb6.spdf.ui.theme.pagePopEnter
import com.stb6.spdf.ui.theme.pagePopExit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class ReaderActivity : ComponentActivity() {

    private val currentUri = mutableStateOf<Uri?>(null)

    private var displayName by mutableStateOf("")

    private val readerViewModel: ReaderViewModel by viewModels()

    private val exportViewModel: DocumentExportViewModel by viewModels()

    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { target -> exportViewModel.saveResult(applicationContext, target) }

    private fun saveAs() {
        val source = currentUri.value ?: return
        if (!exportViewModel.beginSave(source)) return
        try {
            createDocument.launch(displayName.ifBlank { "document.pdf" })
        } catch (failure: Exception) {
            exportViewModel.pickerFailed(failure)
        }
    }

    private fun share() {
        val source = currentUri.value ?: return
        exportViewModel.prepareShare(applicationContext, source, displayName)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        readerViewModel.onLowMemory()
    }

    override fun onStart() {
        super.onStart()
        Perf.startFrameMonitor()
    }

    override fun onStop() {
        Perf.stopFrameMonitor()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        val initial = resolveUri(intent)
        if (initial == null) {
            finish()
            return
        }
        currentUri.value = initial

        setContent {
            AppTheme {
                val repository = remember { DataStoreSettingsRepository(applicationContext) }
                val vm = readerViewModel
                val uri by currentUri

                val persisted by repository.settings.collectAsState(initial = null)

                val systemDark = isSystemInDarkTheme()
                LaunchedEffect(persisted) { persisted?.let { vm.applyPersistedSettings(it, systemDark) } }

                LaunchedEffect(uri, persisted != null) { if (persisted != null) uri?.let { vm.open(it) } }

                LaunchedEffect(uri) {
                    val target = uri ?: return@LaunchedEffect
                    displayName = ""
                    // Provider queries may block; only publish the current URI's result.
                    val name = withContext(Dispatchers.IO) { queryDisplayName(target) }

                    if (currentUri.value == target) displayName = name
                }
                val state by vm.state.collectAsState()

                val exportError by exportViewModel.error.collectAsState()
                val pendingShare by exportViewModel.share.collectAsState()
                LaunchedEffect(pendingShare) {
                    val request = pendingShare ?: return@LaunchedEffect
                    lifecycle.withStarted {
                        // Do not suspend between launching and consuming this share request.
                        val failure = runCatching {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "application/pdf"
                                putExtra(Intent.EXTRA_STREAM, request.uri)
                                clipData = ClipData.newUri(contentResolver, request.name, request.uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(Intent.createChooser(send, request.name))
                        }.exceptionOrNull()
                        exportViewModel.finishShare(request, failure)
                    }
                }
                exportError?.let { failure ->
                    ErrorDialog(
                        title = stringResource(if (failure.isShare) R.string.reader_share_failed else R.string.reader_save_failed),
                        message = null,
                        detail = failure.cause.toString(),
                        detailLabel = stringResource(R.string.reader_error_detail_title),
                        confirmLabel = stringResource(R.string.reader_error_close),
                        onDismiss = exportViewModel::dismissError,
                    )
                }

                val loadedSettings = persisted
                if (loadedSettings == null) {
                    LoadingState()
                    return@AppTheme
                }
                when (val current = state) {
                    ReaderUiState.Loading -> LoadingState()

                    is ReaderUiState.PasswordRequired -> PasswordDialog(
                        retry = current.retry,
                        verifying = current.verifying,
                        password = vm.passwordDraft.value,
                        onPasswordChange = { vm.passwordDraft.value = it },
                        onSubmit = { vm.retryWithPassword(it) },
                        onCancel = { finish() },
                    )

                    is ReaderUiState.Error -> ErrorState(
                        reason = current.reason,
                        detail = current.detail,
                        onDismiss = { finish() },
                    )

                    is ReaderUiState.Ready -> {
                        val settings by vm.sessionSettings.collectAsState()
                        val page by vm.currentPage.collectAsState()
                        val darkStyle = resolveDarkStyle(settings, current.documentIsDark)
                        val outline by vm.outline.collectAsState()

                        val navController = rememberNavController()

                        val onReader = navController.currentBackStackEntryAsState()
                            .value?.destination?.route == ROUTE_READER

                        val view = LocalView.current
                        val darkTheme = isSystemInDarkTheme()
                        val lightBars = !onReader && !darkTheme
                        SideEffect {
                            WindowCompat.getInsetsController(window, view).apply {
                                isAppearanceLightStatusBars = lightBars
                                isAppearanceLightNavigationBars = lightBars
                            }
                        }

                        var canvasBusy by remember { mutableStateOf(false) }
                        var pageLabelVisible by remember { mutableStateOf(true) }
                        LaunchedEffect(canvasBusy, settings.pageLabelHalfSeconds, onReader) {
                            if (settings.pageLabelHalfSeconds >= PAGE_LABEL_ALWAYS) {
                                pageLabelVisible = true
                                return@LaunchedEffect
                            }
                            pageLabelVisible = true
                            if (canvasBusy) return@LaunchedEffect

                            delay(maxOf(settings.pageLabelHalfSeconds * 500L, PAGE_LABEL_MIN_DWELL_MILLIS))
                            pageLabelVisible = false
                        }

                        NavHost(
                            navController = navController,
                            startDestination = ROUTE_READER,
                            enterTransition = { pageEnter() },
                            exitTransition = { pageExit() },
                            popEnterTransition = { pagePopEnter() },
                            popExitTransition = { pagePopExit() },
                        ) {
                            composable(ROUTE_READER) {
                                Box(Modifier.fillMaxSize()) {
                                    ReaderScreen(
                                        state = current,
                                        viewModel = vm,
                                        settings = settings,
                                        darkStyle = darkStyle,
                                        onOpenUrl = ::openExternalLink,
                                        onBusyChange = { canvasBusy = it },
                                    )

                                    ReaderToolbar(
                                        fileName = displayName,
                                        pageLabel = "${page + 1} / ${current.pageCount}",
                                        pageLabelVisible = pageLabelVisible,
                                        docDarkOn = settings.docDarkTrigger == DocDarkTrigger.ON,
                                        onToggleDocumentDark = vm::toggleDocDark,
                                        onOpenOutline = {

                                            vm.settleNow()

                                            vm.loadOutlineIfNeeded()
                                            navController.navigate(ROUTE_OUTLINE) { launchSingleTop = true }
                                        },
                                        onOpenQuickSettings = {
                                            vm.settleNow()
                                            navController.navigate(ROUTE_DOCUMENT_SETTINGS) { launchSingleTop = true }
                                        },
                                        onSaveAs = ::saveAs,
                                        onShare = ::share,
                                    )
                                }
                            }

                            composable(ROUTE_OUTLINE) {

                                BackHandler { navController.popBackStack() }

                                OutlineScreen(
                                    fileName = displayName,
                                    pageCount = current.pageCount,
                                    currentPage = page,
                                    outline = outline,
                                    onJumpTo = vm::jumpToPage,
                                    onClose = { navController.popBackStack() },
                                )
                            }

                            composable(ROUTE_GLOBAL_SETTINGS) {

                                BackHandler { navController.popBackStack() }

                                SettingsScreen(
                                    settings = loadedSettings,

                                    onChange = repository::update,
                                )
                            }

                            composable(ROUTE_DOCUMENT_SETTINGS) {

                                BackHandler { navController.popBackStack() }

                                DocumentSettingsScreen(
                                    settings = settings,
                                    onChange = { transform ->
                                        vm.updateSessionSettings(transform)
                                        CompletableDeferred(vm.sessionSettings.value)
                                    },
                                    onResetToGlobal = { vm.resetSessionToGlobal(systemDark) },
                                    onOpenGlobalSettings = {
                                        navController.navigate(ROUTE_GLOBAL_SETTINGS) { launchSingleTop = true }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        resolveUri(intent)?.let {
            currentUri.value = it

            // The same URI may now contain a different document.
            readerViewModel.open(it, force = true)
        }
    }

    private companion object {
        const val ROUTE_READER = "reader"
        const val ROUTE_OUTLINE = "outline"
        const val ROUTE_DOCUMENT_SETTINGS = "document-settings"
        const val ROUTE_GLOBAL_SETTINGS = "global-settings"

        const val PAGE_LABEL_MIN_DWELL_MILLIS = 100L

        val ALLOWED_LINK_SCHEMES = setOf("http", "https")
    }

    private fun queryDisplayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull().orEmpty()

    private fun openExternalLink(url: String) {
        val uri = runCatching { url.toUri() }.getOrNull() ?: return
        if (uri.scheme?.lowercase() !in ALLOWED_LINK_SCHEMES) {
            val copied = runCatching {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText(null, url))
            }.isSuccess
            if (!copied || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(this, if (copied) R.string.reader_link_copied else R.string.reader_link_copy_failed,
                    Toast.LENGTH_SHORT).show()
            }
            return
        }
        val opened = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        }.getOrDefault(false)
        if (!opened) {
            Toast.makeText(this, R.string.reader_link_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun resolveUri(intent: Intent?): Uri? = when (intent?.action) {

        Intent.ACTION_SEND ->
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)

        else -> intent?.data
    }?.takeIf { it.scheme?.lowercase() == ContentResolver.SCHEME_CONTENT }
}
