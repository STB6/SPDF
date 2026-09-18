package com.stb6.spdf.data

import com.stb6.spdf.pdf.DocDarkStyle

enum class ScrollDirection(val axis: Axis, val reversed: Boolean) {
    DOWN(Axis.VERTICAL, false),
    RIGHT(Axis.HORIZONTAL, false),
    LEFT(Axis.HORIZONTAL, true),
}

enum class Axis { VERTICAL, HORIZONTAL }

enum class DocDarkTrigger { OFF, ON, FOLLOW_SYSTEM }

fun ReaderSettings.forSession(systemInDarkTheme: Boolean): ReaderSettings =
    if (docDarkTrigger != DocDarkTrigger.FOLLOW_SYSTEM) this
    else copy(docDarkTrigger = if (systemInDarkTheme) DocDarkTrigger.ON else DocDarkTrigger.OFF)

data class ReaderSettings(
    val scrollDirection: ScrollDirection = ScrollDirection.DOWN,
    val docDarkTrigger: DocDarkTrigger = DocDarkTrigger.OFF,
    val docDarkStyle: DocDarkStyle = DocDarkStyle.SMART,
    val smartSkipDarkDocuments: Boolean = true,

    val pageLabelHalfSeconds: Int = 4,
    val keepScreenOn: Boolean = false,
    val backResetsZoom: Boolean = false,
)

const val PAGE_LABEL_ALWAYS = 11

val PAGE_LABEL_RANGE = 0..PAGE_LABEL_ALWAYS
