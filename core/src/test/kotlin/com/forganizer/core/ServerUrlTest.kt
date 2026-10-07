package com.forganizer.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerUrlTest {
    @Test fun normalizesCommonMistakes() {
        assertEquals("https://a.onrender.com", ServerUrl.normalize("https://a.onrender.com"))
        assertEquals("https://a.onrender.com", ServerUrl.normalize("  https://a.onrender.com/  "))
        assertEquals("https://a.onrender.com", ServerUrl.normalize("a.onrender.com"))
        assertEquals("https://a.onrender.com", ServerUrl.normalize("https://a.onrender.com/health"))
        assertEquals("https://a.onrender.com", ServerUrl.normalize("https://a.onrender.com/Plan/"))
        assertEquals("https://a.onrender.com", ServerUrl.normalize("https://a.onrender.com/ping/health"))
        assertEquals("http://10.0.2.2:8000", ServerUrl.normalize("http://10.0.2.2:8000/"))
        assertEquals("", ServerUrl.normalize("   "))
    }
}
