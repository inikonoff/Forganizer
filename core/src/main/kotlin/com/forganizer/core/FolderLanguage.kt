package com.forganizer.core

/** Language of the folder names the app and the assistant propose. */
enum class FolderLanguage(val code: String) {
    RU("ru"), EN("en");

    companion object {
        fun fromCode(code: String?): FolderLanguage = entries.firstOrNull { it.code == code } ?: RU
    }
}
