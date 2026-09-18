package com.stb6.spdf.ui.reader

import com.stb6.spdf.data.DocDarkTrigger
import com.stb6.spdf.data.ReaderSettings
import com.stb6.spdf.pdf.DocDarkStyle

fun resolveDarkStyle(settings: ReaderSettings, documentIsDark: Boolean): DocDarkStyle? {
    if (settings.docDarkTrigger != DocDarkTrigger.ON) return null
    if (settings.smartSkipDarkDocuments && documentIsDark) return null
    return settings.docDarkStyle
}
