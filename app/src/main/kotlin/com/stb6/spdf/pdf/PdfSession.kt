package com.stb6.spdf.pdf

import android.graphics.Bitmap
import com.stb6.spdf.ui.UiText
import java.io.Closeable
import java.io.FileNotFoundException
import java.io.IOException

/** Dimensions in PDF points, including /Rotate. */
data class PageInfo(
    val widthPt: Int,
    val heightPt: Int,
) {
    val aspectRatio: Float get() = widthPt.toFloat() / heightPt.toFloat()
}

sealed interface OpenResult {
    data class Success(val session: PdfSession) : OpenResult

    data object PasswordRequired : OpenResult

    data class Failure(val cause: Throwable) : OpenResult
}

// Keep exception types intact so URI failures remain distinguishable from invalid PDFs.
interface LocalizedCause {
    val text: UiText
}

class PdfIoException(override val text: UiText) : IOException(), LocalizedCause

class PdfUnavailableException(override val text: UiText) : FileNotFoundException(), LocalizedCause

interface PdfSession : Closeable {
    suspend fun closeSuspending() = close()

    suspend fun exactPageInfo(index: Int): PageInfo

    val pageCount: Int

    suspend fun links(pageIndex: Int): List<PdfLink>

    /** Immutable geometry in display points: top-left origin, y downward. Null means no selectable text. */
    suspend fun pageText(pageIndex: Int): PageText?

    suspend fun outline(): List<PdfOutlineEntry>

    /** Offsets place the scaled page origin in target pixels; cropped regions use negative offsets. */
    suspend fun renderRegion(
        target: Bitmap,
        pageIndex: Int,
        offsetX: Int,
        offsetY: Int,
        scaledWidth: Int,
        scaledHeight: Int,
    )

    suspend fun backgroundLuminance(index: Int): Float
}
