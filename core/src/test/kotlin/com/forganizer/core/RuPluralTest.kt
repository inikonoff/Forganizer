package com.forganizer.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RuPluralTest {
    @Test fun forms() {
        assertEquals("0 файлов", RuPlural.files(0))
        assertEquals("1 файл", RuPlural.files(1))
        assertEquals("2 файла", RuPlural.files(2))
        assertEquals("4 файла", RuPlural.files(4))
        assertEquals("5 файлов", RuPlural.files(5))
        assertEquals("11 файлов", RuPlural.files(11))
        assertEquals("12 файлов", RuPlural.files(12))
        assertEquals("21 файл", RuPlural.files(21))
        assertEquals("22 файла", RuPlural.files(22))
        assertEquals("111 файлов", RuPlural.files(111))
        assertEquals("1 200 файлов", RuPlural.files(1200))
    }
}
