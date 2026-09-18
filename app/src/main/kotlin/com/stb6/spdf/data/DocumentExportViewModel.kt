package com.stb6.spdf.data

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stb6.spdf.pdf.withCancelWatchdog
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class ExportFailure(val cause: Throwable, val isShare: Boolean)

data class ShareReady(val uri: Uri, val name: String)

class DocumentExportViewModel(private val savedState: SavedStateHandle) : ViewModel() {
    private val _error = MutableStateFlow<ExportFailure?>(null)
    val error = _error.asStateFlow()
    private val _share = MutableStateFlow<ShareReady?>(null)
    val share = _share.asStateFlow()
    private var busy = savedState.get<Uri>(SOURCE) != null

    fun dismissError() { _error.value = null }

    fun beginSave(source: Uri): Boolean {
        if (busy) return false
        busy = true
        savedState[SOURCE] = source
        return true
    }

    fun pickerFailed(cause: Throwable) {
        savedState.remove<Uri>(SOURCE)
        busy = false
        _error.value = ExportFailure(cause, isShare = false)
    }

    fun saveResult(context: Context, target: Uri?) {
        val source = savedState.remove<Uri>(SOURCE)
        if (target == null) {
            busy = false
            return
        }
        val app = context.applicationContext
        viewModelScope.launch {
            var completed = false
            var failure: Exception? = null
            try {
                checkNotNull(source) { "Export source lost" }
                copyDocument(app, source) { signal ->
                    app.contentResolver.openFileDescriptor(target, "wt", signal)
                }
                completed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Exception) {
                failure = cause
            } finally {
                if (!completed) {
                    val cleanup = withContext(NonCancellable + Dispatchers.IO) {
                        runCatching {
                            check(DocumentsContract.deleteDocument(app.contentResolver, target)) {
                                "Provider did not delete the incomplete destination"
                            }
                        }.exceptionOrNull()
                    }
                    if (cleanup != null) failure = IOException(
                        "${failure?.toString() ?: "Export cancelled"}; cleanup failed: $cleanup", failure,
                    )
                }
                failure?.let { _error.value = ExportFailure(it, isShare = false) }
                busy = false
            }
        }
    }

    fun prepareShare(context: Context, source: Uri, displayName: String) {
        if (busy) return
        busy = true
        val app = context.applicationContext
        val name = displayName.substringAfterLast('/').takeUnless { it.isBlank() || it == "." || it == ".." }
            ?: "document.pdf"
        viewModelScope.launch {
            var directory: File? = null
            var published = false
            try {
                val file = withContext(Dispatchers.IO) {
                    val root = File(app.cacheDir, "share")
                    check(root.isDirectory || root.mkdirs()) { "Can't create share directory" }
                    val cutoff = System.currentTimeMillis() - SHARE_KEEP_MILLIS
                    root.listFiles()?.forEach {
                        coroutineContext.ensureActive()
                        if (it.lastModified() < cutoff) it.deleteRecursively()
                    }
                    val dir = Files.createTempDirectory(root.toPath(), "export-").toFile()
                    directory = dir
                    File(dir, name)
                }
                copyDocument(app, source) {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY)
                }
                _share.value = ShareReady(FileProvider.getUriForFile(app, "${app.packageName}.share", file), name)
                published = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _error.value = ExportFailure(failure, isShare = true)
            } finally {
                if (!published) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { directory?.deleteRecursively() }
                    }
                    busy = false
                }
            }
        }
    }

    fun finishShare(request: ShareReady, failure: Throwable? = null) {
        if (!_share.compareAndSet(request, null)) return
        busy = false
        if (failure != null) _error.value = ExportFailure(failure, isShare = true)
    }

    private companion object {
        const val SOURCE = "export_source"
        const val SHARE_KEEP_MILLIS = 60 * 60 * 1000L
    }
}

private suspend fun copyDocument(
    context: Context,
    source: Uri,
    openTarget: (CancellationSignal) -> ParcelFileDescriptor?,
) = withContext(Dispatchers.IO) {
    val signal = CancellationSignal()
    val input = AtomicReference<ParcelFileDescriptor?>()
    val output = AtomicReference<ParcelFileDescriptor?>()
    fun closeDescriptors() {
        runCatching { input.get()?.close() }
        runCatching { output.get()?.close() }
    }
    try {
        withCancelWatchdog({
            closeDescriptors()
            runCatching { signal.cancel() }
        }) {
            val sourceFd = context.contentResolver.openFileDescriptor(source, "r", signal)
                ?.also(input::set) ?: throw IOException("Can't read the source document")
            coroutineContext.ensureActive()
            val targetFd = openTarget(signal)?.also(output::set)
                ?: throw IOException("Can't write to the destination")
            coroutineContext.ensureActive()
            ParcelFileDescriptor.AutoCloseInputStream(sourceFd).use { from ->
                ParcelFileDescriptor.AutoCloseOutputStream(targetFd).use { to ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = from.read(buffer)
                        if (count < 0) break
                        coroutineContext.ensureActive()
                        to.write(buffer, 0, count)
                    }
                    to.flush()
                }
            }
        }
    } finally {
        closeDescriptors()
    }
}
