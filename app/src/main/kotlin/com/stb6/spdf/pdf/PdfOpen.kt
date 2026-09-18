package com.stb6.spdf.pdf

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import io.legere.pdfiumandroid.api.PdfPasswordException
import io.legere.pdfiumandroid.suspend.PdfDocumentKt
import io.legere.pdfiumandroid.suspend.PdfiumCoreKt
import java.io.File
import com.stb6.spdf.R
import com.stb6.spdf.ui.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val INITIAL_PAGE_INFO_COUNT = 3

private const val MAX_PIPE_BYTES = 256L * 1024 * 1024

// PDF page counts are untrusted and drive page-indexed allocations.
private const val MAX_PAGE_COUNT = 20_000

// A child observes cancellation even while the parent is blocked in native or provider I/O.
internal suspend fun <T> withCancelWatchdog(
    onCancel: () -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    block: suspend () -> T,
): T = coroutineScope {
    val watchdog = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            runCatching(onCancel)
        }
    }
    try {
        block()
    } finally {
        watchdog.cancel()
    }
}

// Spill pipes to an unlinked file: PDFium needs seeking, and buffering the document would multiply memory use.
private suspend fun spillToTempFile(
    context: Context,
    pfd: ParcelFileDescriptor,
    takeOwnership: (ParcelFileDescriptor) -> Unit,
): ParcelFileDescriptor = withCancelWatchdog({ pfd.close() }) {
    val temp = File.createTempFile("spdf-pipe", ".pdf", context.cacheDir)
    val target = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_WRITE)
    takeOwnership(target)
    temp.delete()
    try {
        withContext(Dispatchers.IO) {
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                val chunk = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    total += read
                    if (total > MAX_PIPE_BYTES) {
                        throw PdfIoException(
                            UiText.Res(R.string.reader_error_detail_too_large, MAX_PIPE_BYTES / 1024 / 1024),
                        )
                    }
                    // Keep the descriptor open for the session; an output stream would close it.

                    var written = 0
                    while (written < read) {
                        written += Os.write(target.fileDescriptor, chunk, written, read - written)
                    }
                }
            }
        }
        Os.lseek(target.fileDescriptor, 0, OsConstants.SEEK_SET)
        target
    } catch (throwable: Throwable) {
        target.close()
        throw throwable
    }
}

suspend fun openPdf(
    context: Context,
    uri: Uri,
    password: String? = null,
): OpenResult {
    var fileDescriptor: ParcelFileDescriptor? = null
    var document: PdfDocumentKt? = null

    try {
        val source = withContext(Dispatchers.IO) {
            val signal = CancellationSignal()
            val pfd = withCancelWatchdog({ signal.cancel() }) {
                context.contentResolver.openFileDescriptor(uri, "r", signal)?.also { fileDescriptor = it }
            } ?: throw PdfUnavailableException(UiText.Res(R.string.reader_error_detail_uri, uri.toString()))
            fileDescriptor = pfd

            if (pfd.statSize < 0) spillToTempFile(context, pfd) { fileDescriptor = it } else pfd
        }

        val opened = withContext(PdfiumThread.dispatcher) {
            val core = PdfiumCoreKt(PdfiumThread.dispatcher)
            val pdfDocument = if (password == null) {
                core.newDocument(source)
            } else {
                core.newDocument(source, password)
            }
            document = pdfDocument

            val count = pdfDocument.getPageCount()
            if (count <= 0) throw PdfIoException(UiText.Res(R.string.reader_error_detail_no_pages))
            if (count > MAX_PAGE_COUNT) {
                throw PdfIoException(
                    UiText.Res(R.string.reader_error_detail_too_many_pages, count, MAX_PAGE_COUNT),
                )
            }

            val firstPageCount = minOf(count, INITIAL_PAGE_INFO_COUNT)
            val initialPageInfo = ArrayList<PageInfo>(firstPageCount)
            repeat(firstPageCount) { index -> initialPageInfo += pdfDocument.readPageInfo(index) }
            count to initialPageInfo
        }

        return OpenResult.Success(
            PdfiumSession(
                pageCount = opened.first,
                document = checkNotNull(document),
                fileDescriptor = checkNotNull(fileDescriptor),
                initialPageInfo = opened.second,
            ),
        )
    } catch (exception: CancellationException) {
        closeFailedOpen(document, fileDescriptor)
        throw exception
    } catch (exception: PdfPasswordException) {
        closeFailedOpen(document, fileDescriptor)
        return OpenResult.PasswordRequired
    } catch (exception: Exception) {
        closeFailedOpen(document, fileDescriptor)
        return OpenResult.Failure(exception)
    } catch (error: Throwable) {
        closeFailedOpen(document, fileDescriptor)
        throw error
    }
}

private suspend fun closeFailedOpen(document: PdfDocumentKt?, fileDescriptor: ParcelFileDescriptor?) {
    withContext(NonCancellable) {
        try {
            if (document != null) {
                withContext(PdfiumThread.dispatcher) { document.close() }
            }
        } catch (_: Throwable) {
            // Preserve the original open failure.
        }
        try {
            fileDescriptor?.let { withContext(Dispatchers.IO) { it.close() } }
        } catch (_: Throwable) {
        }
    }
}
