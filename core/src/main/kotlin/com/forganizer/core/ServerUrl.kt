package com.forganizer.core

/** Cleans up a server address typed by hand or taken from a build variable. */
object ServerUrl {
    private val ENDPOINTS = listOf("/health", "/ping", "/plan", "/refine")

    /**
     * Adds https:// when no scheme is given, removes trailing slashes and a pasted endpoint such as
     * /health or /plan, so "forganizer.onrender.com/health" works as a base address.
     */
    fun normalize(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s
        if (!s.contains("://")) s = "https://$s"
        s = s.trimEnd('/')
        var changed = true
        while (changed) {
            changed = false
            for (suffix in ENDPOINTS) {
                if (s.endsWith(suffix, ignoreCase = true)) {
                    s = s.dropLast(suffix.length).trimEnd('/')
                    changed = true
                }
            }
        }
        return s
    }
}
