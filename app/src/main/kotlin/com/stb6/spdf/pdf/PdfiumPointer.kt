@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.stb6.spdf.pdf

import io.legere.pdfiumandroid.suspend.PdfDocumentKt
import io.legere.pdfiumandroid.suspend.PdfPageKt
import io.legere.pdfiumandroid.suspend.PdfTextPageKt

// Access internal binding fields directly so dependency changes fail at compile time, not in reflection.
internal fun PdfTextPageKt.nativePointer(): Long = page.pagePtr

internal fun PdfPageKt.nativePointer(): Long = page.pagePtr

internal fun PdfDocumentKt.nativePointer(): Long = document.mNativeDocPtr
