package app.tagledger.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily

// Same palette as the web version: cobalt accent on a green-grey ground.
private val Light = lightColorScheme(
    primary = Color(0xFF2B44C9), onPrimary = Color.White, primaryContainer = Color(0xFFE1E6FA), onPrimaryContainer = Color(0xFF14205E),
    secondaryContainer = Color(0xFFE1E6FA), onSecondaryContainer = Color(0xFF14205E),
    background = Color(0xFFEDF0EC), onBackground = Color(0xFF17211C), surface = Color(0xFFFFFFFF), onSurface = Color(0xFF17211C),
    surfaceVariant = Color(0xFFE3E8E3), onSurfaceVariant = Color(0xFF5C6A62), outline = Color(0xFFD2D9D3), outlineVariant = Color(0xFFD2D9D3),
    surfaceContainer = Color(0xFFF6F8F5), surfaceContainerHigh = Color(0xFFF1F4F0), surfaceContainerLow = Color(0xFFFFFFFF),
    error = Color(0xFFB03A2B),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF93A5FF), onPrimary = Color(0xFF0C1230), primaryContainer = Color(0xFF222B52), onPrimaryContainer = Color(0xFFDDE3FF),
    secondaryContainer = Color(0xFF222B52), onSecondaryContainer = Color(0xFFDDE3FF),
    background = Color(0xFF0F1412), onBackground = Color(0xFFE6EDE8), surface = Color(0xFF171F1B), onSurface = Color(0xFFE6EDE8),
    surfaceVariant = Color(0xFF212B26), onSurfaceVariant = Color(0xFF95A49C), outline = Color(0xFF2D3933), outlineVariant = Color(0xFF2D3933),
    surfaceContainer = Color(0xFF1B2420), surfaceContainerHigh = Color(0xFF212B26), surfaceContainerLow = Color(0xFF171F1B),
    error = Color(0xFFF2897A),
)

/** True when the app is drawing its dark palette, whether chosen in Settings or following the phone. */
val LocalDark = staticCompositionLocalOf { false }

/** Resolves the Settings choice ("system", "light", "dark") to light or dark. */
@Composable
fun isDark(mode: String): Boolean = when (mode) {
    "light" -> false
    "dark" -> true
    else -> isSystemInDarkTheme()
}

object Ledger {
    val earn @Composable @ReadOnlyComposable get() = if (LocalDark.current) Color(0xFF6CD29A) else Color(0xFF1C774A)
    val spend @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.error
    val mono = FontFamily.Monospace

    // Ten hues far enough apart to tell at a glance; a free hue wheel kept landing short tags on near-identical greens.
    private val hues = floatArrayOf(4f, 28f, 48f, 140f, 172f, 198f, 222f, 252f, 284f, 328f)

    private fun hue(tag: String): Float {
        var hash = 0
        for (c in tag) hash = hash * 31 + c.code
        return hues[Math.floorMod(hash, hues.size)]
    }

    /** Stable soft background and readable foreground per tag, like the web version. */
    @Composable @ReadOnlyComposable
    fun tagColors(tag: String): Pair<Color, Color> {
        val h = hue(tag)
        val dark = LocalDark.current
        val bg = Color.hsl(h, 0.55f, if (dark) 0.22f else 0.91f)
        val fg = Color.hsl(h, 0.45f, if (dark) 0.82f else 0.27f)
        return bg to fg
    }

    /** Card background and accent for one home-screen section. */
    data class Tint(val bg: Color, val accent: Color)

    // Each home section has its own tint so they don't all read as the same box; entries stay on the plain surface.
    val addTint @Composable @ReadOnlyComposable get() =
        if (LocalDark.current) Tint(Color(0xFF1D2547), Color(0xFF93A5FF)) else Tint(Color(0xFFE5EAFC), Color(0xFF2B44C9))
    val reviewTint @Composable @ReadOnlyComposable get() =
        if (LocalDark.current) Tint(Color(0xFF3A2D12), Color(0xFFF2B95C)) else Tint(Color(0xFFFFF0D2), Color(0xFF9A5A00))
    val summaryTint @Composable @ReadOnlyComposable get() =
        if (LocalDark.current) Tint(Color(0xFF14301F), Color(0xFF6CD29A)) else Tint(Color(0xFFDFF1E5), Color(0xFF1C774A))
    val filterTint @Composable @ReadOnlyComposable get() =
        if (LocalDark.current) Tint(Color(0xFF2A2239), Color(0xFFC3A6F0)) else Tint(Color(0xFFF0E9F9), Color(0xFF6B3FA0))

    /** Strong colour for a tag's dot and bar: the one place a tag's colour shows. */
    @Composable @ReadOnlyComposable
    fun tagDot(tag: String): Color = Color.hsl(hue(tag), 0.6f, if (LocalDark.current) 0.66f else 0.47f)
}

@Composable
fun TagLedgerTheme(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDark provides dark) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
    }
}

@Suppress("unused") fun Color.argb() = toArgb()
