package com.stb6.spdf.ui.reader

import java.io.IOException

internal class SelectionTooLargeException : IOException("Selection exceeds clipboard text limit")

internal class SelectionTextBuffer(private val limit: Int = MAX_CLIPBOARD_CHARS) {
    private val text = StringBuilder()

    fun append(piece: String) {
        if (piece.isEmpty()) return
        val separator = text.isNotEmpty() && !text.endsWith('\n')
        if (piece.length > limit - text.length - if (separator) 1 else 0) {
            throw SelectionTooLargeException()
        }
        if (separator) text.append('\n')
        text.append(piece)
    }

    override fun toString(): String = text.toString()
}

// UTF-16 units; leave space for ClipData metadata and other Binder traffic.
internal const val MAX_CLIPBOARD_CHARS = 100_000
