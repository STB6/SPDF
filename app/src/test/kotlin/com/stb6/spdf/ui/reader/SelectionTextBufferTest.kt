package com.stb6.spdf.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SelectionTextBufferTest {
    @Test
    fun `跨页换行计入容量且拒绝时不截断`() {
        val text = SelectionTextBuffer(5)
        text.append("ab")
        text.append("cd")
        assertEquals("ab\ncd", text.toString())
        assertThrows(SelectionTooLargeException::class.java) { text.append("e") }
        assertEquals("ab\ncd", text.toString())
    }

    @Test
    fun `空页和已有换行不重复占容量`() {
        val text = SelectionTextBuffer(4)
        text.append("a\n")
        text.append("")
        text.append("bc")
        assertEquals("a\nbc", text.toString())
    }

    @Test
    fun `非BMP字符按UTF16容量计算`() {
        assertThrows(SelectionTooLargeException::class.java) {
            SelectionTextBuffer(1).append("\uD83D\uDE00")
        }
    }
}
