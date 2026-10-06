package com.forganizer.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun ForganizerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF9CC4EE))
        else -> lightColorScheme(primary = Color(0xFF2E5E8C))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

fun confidenceColor(c: Double): Color = when {
    c >= 0.9 -> Color(0xFF2E7D32)
    c >= 0.7 -> Color(0xFF558B2F)
    else -> Color(0xFFEF8F00)
}
