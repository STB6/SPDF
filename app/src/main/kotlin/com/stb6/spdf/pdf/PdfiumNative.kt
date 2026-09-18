package com.stb6.spdf.pdf

import android.graphics.Bitmap

// All calls must run on the process-wide PdfiumThread.
internal object PdfiumNative {
    val available: Boolean = runCatching { System.loadLibrary("spdf_pdfium") }.isSuccess

    // Must match kStride in pdfium_bridge.cpp.
    const val STRIDE = 12

    const val RENDER_DONE = 0
    const val RENDER_CANCELLED = 1
    const val RENDER_FAILED = 2

    const val RENDER_UNAVAILABLE = 3

    const val FLAG_ANNOT = 0x01

    /** charCount * STRIDE floats; layout is defined by the native readChars implementation. */
    external fun readChars(textPage: Long, maxChars: Int): FloatArray?

    /** Setting cancel[0] stops rendering; a cancelled bitmap is incomplete and must be discarded. */
    external fun render(
        page: Long,
        bitmap: Bitmap,
        startX: Int,
        startY: Int,
        sizeX: Int,
        sizeY: Int,
        flags: Int,
        cancel: IntArray,
    ): Int

    /** CropBox intersected with MediaBox: left, top, right, bottom in unrotated PDF coordinates. */
    external fun pageBounds(page: Long): FloatArray?

    /** doc is pdfiumandroid's DocumentFile pointer, not FPDF_DOCUMENT; bookmark 0 selects the root. */
    external fun bookmarkFirstChild(doc: Long, bookmark: Long): Long

    external fun bookmarkNextSibling(doc: Long, bookmark: Long): Long

    external fun bookmarkTitle(bookmark: Long): String?

    external fun bookmarkPage(doc: Long, bookmark: Long): Int

    external fun releaseScratch()
}
