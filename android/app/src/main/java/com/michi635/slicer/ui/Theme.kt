package com.michi635.slicer.ui

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

private val Orange = Color(0xFFFF9800)

@Composable
fun SlicerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Orange, secondary = Color(0xFF90CAF9))
        else -> lightColorScheme(primary = Color(0xFFE65100), secondary = Color(0xFF1565C0))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** "#RRGGBB" or "#AARRGGBB" to a colour; white when unreadable. */
fun parseColor(text: String): Color {
    val s = text.removePrefix("#")
    val v = s.toLongOrNull(16) ?: return Color.White
    return when (s.length) {
        6 -> Color(0xFF000000 or v)
        8 -> Color(v)
        else -> Color.White
    }
}

fun Color.toHex(): String {
    val r = (red * 255).toInt(); val g = (green * 255).toInt(); val b = (blue * 255).toInt()
    return "#%02X%02X%02X".format(r, g, b)
}
