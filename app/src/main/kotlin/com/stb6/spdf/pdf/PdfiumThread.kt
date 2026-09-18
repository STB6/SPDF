package com.stb6.spdf.pdf

import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

// PDFium has process-global state: all documents must share this one native thread.
internal object PdfiumThread {
    @Volatile
    private var thread: Thread? = null

    val dispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pdf-native").also { thread = it }
    }.asCoroutineDispatcher()

    val isCurrent: Boolean get() = Thread.currentThread() === thread
}
