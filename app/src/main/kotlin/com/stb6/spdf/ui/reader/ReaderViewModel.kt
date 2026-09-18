package com.stb6.spdf.ui.reader

import com.stb6.spdf.Perf
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stb6.spdf.data.DocDarkTrigger
import com.stb6.spdf.data.ReaderSettings
import com.stb6.spdf.data.forSession
import com.stb6.spdf.pdf.LocalizedCause
import com.stb6.spdf.pdf.OpenResult
import com.stb6.spdf.pdf.PageInfo
import com.stb6.spdf.pdf.PdfLink
import com.stb6.spdf.pdf.PdfSession
import com.stb6.spdf.pdf.PageText
import com.stb6.spdf.pdf.TextQuad
import com.stb6.spdf.pdf.openPdf
import com.stb6.spdf.ui.UiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.FileNotFoundException
import com.stb6.spdf.pdf.PdfOutlineEntry
import androidx.compose.runtime.mutableStateOf

class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    // Written on Main, read by exactPageInfo on Default.
    @Volatile private var session: PdfSession? = null
    private var uri: Uri? = null

    private var globalSettings = ReaderSettings()

    private val _sessionSettings = MutableStateFlow(ReaderSettings())
    val sessionSettings: StateFlow<ReaderSettings> = _sessionSettings.asStateFlow()

    // Password drafts survive rotation in memory, but must not enter saved state.
    val passwordDraft = mutableStateOf("")

    private var openJob: Job? = null
    private var toneJob: Job? = null
    private var toneJudgedToken = -1L

    init {
        viewModelScope.launch {
            sessionSettings.collect { if (toneWanted(it)) judgeToneIfNeeded() }
        }
    }

    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private val _outline = MutableStateFlow<List<PdfOutlineEntry>>(emptyList())
    val outline: StateFlow<List<PdfOutlineEntry>> = _outline.asStateFlow()

    private var outlineJob: Job? = null

    // Defer outline parsing so it cannot block first-page rendering on the native queue.
    fun loadOutlineIfNeeded() {
        if (outlineJob != null) return
        val current = session ?: return
        outlineJob = viewModelScope.launch {
            val entries = failSoft(emptyList()) { current.outline() }
            // Suspension can outlive cancellation; verify the document before publishing.
            if (session === current) _outline.value = entries
        }
    }

    // Keep jumps pending until positioned so layout reconstruction cannot lose them.
    private val _pendingJump = MutableStateFlow<PageJump?>(null)
    val pendingJump: StateFlow<PageJump?> = _pendingJump.asStateFlow()

    // Distinguish repeated requests to the same page in StateFlow.
    data class PageJump(val pageIndex: Int, val token: Long)

    private var jumpToken = 0L

    private var documentToken = 0L

    val canvas = CanvasPosition()

    private val _settleRequest = MutableStateFlow(0L)
    val settleRequest: StateFlow<Long> = _settleRequest.asStateFlow()

    private var settleToken = 0L

    fun settleNow() {
        _settleRequest.value = ++settleToken
    }

    fun jumpToPage(pageIndex: Int) {
        val count = (_state.value as? ReaderUiState.Ready)?.pageCount ?: return
        if (pageIndex !in 0 until count) return
        _pendingJump.value = PageJump(pageIndex, ++jumpToken)
    }

    fun consumeJump(jump: PageJump) {
        _pendingJump.compareAndSet(jump, null)
    }

    fun updateSessionSettings(transform: (ReaderSettings) -> ReaderSettings) {
        sessionTouched = true
        _sessionSettings.value = transform(_sessionSettings.value)
    }

    fun applyPersistedSettings(settings: ReaderSettings, systemInDarkTheme: Boolean) {
        globalSettings = settings
        this.systemInDarkTheme = systemInDarkTheme
        if (!sessionTouched) _sessionSettings.value = settings.forSession(systemInDarkTheme)
    }

    private var sessionTouched = false

    private var systemInDarkTheme = false

    fun toggleDocDark() {
        val next = if (_sessionSettings.value.docDarkTrigger == DocDarkTrigger.ON) DocDarkTrigger.OFF else DocDarkTrigger.ON
        sessionTouched = true
        _sessionSettings.value = _sessionSettings.value.copy(docDarkTrigger = next)
    }

    fun onCurrentPageChange(index: Int) {
        _currentPage.value = index
    }

    fun resetSessionToGlobal(systemInDarkTheme: Boolean) {
        sessionTouched = false
        this.systemInDarkTheme = systemInDarkTheme
        _sessionSettings.value = globalSettings.forSession(systemInDarkTheme)
    }

    val tiles = TilePool(viewModelScope, application) { session }

    fun open(target: Uri, password: String? = null, force: Boolean = false) {
        // Activity recreation must preserve the open document and password verification.
        if (!force && password == null && uri == target) {
            if (session != null && _state.value is ReaderUiState.Ready) return
            if (openJob?.isActive == true) return
        }
        if (uri != target) passwordDraft.value = ""
        uri = target
        openJob?.cancel()
        toneJob?.cancel()
        releaseDocument()
        // Keep the password dialog composed while verifying to preserve its draft.
        _state.value = if (password == null) {
            ReaderUiState.Loading
        } else {
            ReaderUiState.PasswordRequired(retry = false, verifying = true)
        }
        openJob = viewModelScope.launch {
            val openStartNs = System.nanoTime()
            Perf.mark { "open 开始" }
            when (val result = openPdf(getApplication(), target, password)) {
                is OpenResult.Success -> {
                    if (uri != target) {
                        closeInBackground(result.session)
                        return@launch
                    }
                    Perf.mark { "openPdf 返回 %.0fms".format((System.nanoTime() - openStartNs) / 1e6) }
                    session = result.session
                    passwordDraft.value = ""
                    val token = ++documentToken
                    // Queue tone sampling before Ready so the first rendered page uses the resolved tone.
                    if (toneWanted(_sessionSettings.value)) judgeToneInBackground(result.session, token)
                    _state.value = ReaderUiState.Ready(
                        pageCount = result.session.pageCount,
                        documentIsDark = false,
                        token = token,
                    )
                    Perf.mark { "Ready 总计 %.0fms".format((System.nanoTime() - openStartNs) / 1e6) }
                }

                OpenResult.PasswordRequired ->
                    _state.value = ReaderUiState.PasswordRequired(retry = password != null)

                is OpenResult.Failure ->
                    _state.value = ReaderUiState.Error(
                        reason = result.cause.toErrorReason(),
                        detail = result.cause.describe(),
                    )
            }
        }
    }

    private fun Throwable.toErrorReason(): ErrorReason = when (this) {
        is FileNotFoundException, is SecurityException -> ErrorReason.UNAVAILABLE
        else -> ErrorReason.CANNOT_OPEN
    }

    private fun Throwable.describe(): UiText {
        if (this is LocalizedCause) return text
        val name = this::class.java.simpleName
        val text = message?.trim()?.takeIf { it.isNotEmpty() }
        return UiText.Raw(if (text == null) name else "$name: $text")
    }

    private fun releaseDocument() {
        sessionTouched = false
        _sessionSettings.value = globalSettings.forSession(systemInDarkTheme)
        tiles.clear()
        _currentPage.value = 0
        outlineJob?.cancel()
        outlineJob = null
        _outline.value = emptyList()
        _pendingJump.value = null
        canvas.reset()
        linkCache.clear()
        selectionStore.clear()
        val previous = session
        session = null
        previous?.let { closeInBackground(it) }
    }

    // This scope must outlive onCleared so native document closure finishes.
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Native closure waits for rendering and must not block Main.
    private fun closeInBackground(target: PdfSession) {
        closeScope.launch {
            runCatching { target.closeSuspending() }
        }
    }

    fun retryWithPassword(password: String) {
        uri?.let { open(it, password) }
    }

    private fun toneWanted(settings: ReaderSettings): Boolean =
        settings.docDarkTrigger != DocDarkTrigger.OFF && settings.smartSkipDarkDocuments

    private fun judgeToneIfNeeded() {
        val current = _state.value as? ReaderUiState.Ready ?: return
        val active = session ?: return
        if (current.token == toneJudgedToken) return
        judgeToneInBackground(active, current.token)
    }

    private fun judgeToneInBackground(session: PdfSession, token: Long) {
        toneJudgedToken = token
        toneJob?.cancel()
        toneJob = viewModelScope.launch {
            fun applyTone(samples: List<Float>) {
                val dark = isDarkByLuminance(samples)
                val current = _state.value
                if (current !is ReaderUiState.Ready || current.token != token) return
                if (current.documentIsDark != dark) _state.value = current.copy(documentIsDark = dark)
            }
            failSoft(Unit) {
                val first = session.backgroundLuminance(0)
                applyTone(listOf(first))
                applyTone(toneSamples(session, first))
            }
        }
    }

    private val selectionStore = SelectionStore { session }

    val selection: StateFlow<TextSelection?> get() = selectionStore.selection

    internal fun primePageText(pageIndex: Int, text: PageText) = selectionStore.primePageText(pageIndex, text)

    suspend fun pageText(pageIndex: Int, includeEmpty: Boolean = false): PageText? =
        selectionStore.pageText(pageIndex, includeEmpty)

    suspend fun selectWordAt(pageIndex: Int, pageX: Float, pageY: Float, maxDistancePt: Float): Boolean =
        selectionStore.selectWordAt(pageIndex, pageX, pageY, maxDistancePt)

    fun selectionAnchor(movingStart: Boolean): DocPosition? = selectionStore.selectionAnchor(movingStart)

    suspend fun extendSelection(
        pageIndex: Int,
        pageX: Float,
        pageY: Float,
        anchorStart: DocPosition,
        anchorEnd: DocPosition,
    ): Boolean = selectionStore.extendSelection(pageIndex, pageX, pageY, anchorStart, anchorEnd)

    suspend fun selectionQuads(pageIndex: Int): List<TextQuad> = selectionStore.selectionQuads(pageIndex)

    val textExtractionWarnings = selectionStore.extractionWarnings
    val textExtractionWarningIssued: Boolean get() = selectionStore.extractionWarningIssued

    suspend fun selectionText(): String? = selectionStore.selectionText()

    suspend fun selectWholePage(pageIndex: Int) = selectionStore.selectWholePage(pageIndex)

    fun clearSelection() = selectionStore.clearSelection()

    private val linkCache = mutableMapOf<Int, List<PdfLink>>()

    suspend fun links(pageIndex: Int): List<PdfLink> {
        linkCache[pageIndex]?.let { return it }
        // Do not cache failed link reads as empty results; later attempts may succeed.
        val current = session ?: return emptyList()
        val list = failSoftCatching { current.links(pageIndex) }.getOrElse { return emptyList() }
        // Reject links read from a document that was replaced during suspension.
        if (session !== current) return emptyList()
        linkCache[pageIndex] = list
        return list
    }

    suspend fun exactPageInfo(index: Int): PageInfo? = failSoft<PageInfo?>(null) {
        Perf.count("pageInfo.read")
        session?.exactPageInfo(index)
    }

    fun onLowMemory() {
        tiles.trim()
    }

    override fun onCleared() {
        openJob?.cancel()
        toneJob?.cancel()
        releaseDocument()
        tiles.dispose()
    }

}

// Isolate unreadable pages without swallowing cancellation or fatal Errors.
internal suspend fun <T> failSoftCatching(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (exception: Exception) {
    Result.failure(exception)
}

internal suspend fun <T> failSoft(fallback: T, block: suspend () -> T): T =
    failSoftCatching(block).getOrDefault(fallback)
