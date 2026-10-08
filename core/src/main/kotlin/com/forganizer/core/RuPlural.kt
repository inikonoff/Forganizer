package com.forganizer.core

import java.util.Locale

/** Russian plural forms for short counters ("1 файл", "3 файла", "1 200 файлов"). */
object RuPlural {
    fun files(n: Int): String = "${grouped(n)} ${form(n, "файл", "файла", "файлов")}"

    fun form(n: Int, one: String, few: String, many: String): String {
        val a = kotlin.math.abs(n)
        val m100 = a % 100
        val m10 = a % 10
        return when {
            m100 in 11..14 -> many
            m10 == 1 -> one
            m10 in 2..4 -> few
            else -> many
        }
    }

    private fun grouped(n: Int): String =
        "%,d".format(Locale.forLanguageTag("ru"), n).replace(' ', ' ').replace(' ', ' ')
}
