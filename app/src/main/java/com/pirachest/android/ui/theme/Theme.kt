package com.pirachest.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

data class PiraThemePreset(
    val id: String, val name: String, val dark: Boolean,
    val background: Color, val surface: Color, val text: Color, val muted: Color, val accent: Color
)

val PiraThemePresets = listOf(
    PiraThemePreset("desktop_dark", "PiraChest Dark", true, Color(0xFF121212), Color(0xFF191919), Color(0xFFEAEAEA), Color(0xFF9A9A9A), Color(0xFFC9C9C9)),
    PiraThemePreset("desktop_light", "PiraChest Light", false, Color(0xFFF3F3F4), Color(0xFFFFFFFF), Color(0xFF101828), Color(0xFF5B6472), Color(0xFF0078D4)),
    PiraThemePreset("amoled", "AMOLED", true, Color(0xFF000000), Color(0xFF0A0A0A), Color(0xFFF2F2F2), Color(0xFF8A8F98), Color(0xFF22D3EE)),
    PiraThemePreset("gruvbox_dark", "Ember", true, Color(0xFF2C1006), Color(0xFF391A0E), Color(0xFFFFEEE7), Color(0xFFBE9C8F), Color(0xFFFF8346)),
    PiraThemePreset("bloodstone", "Bloodstone", true, Color(0xFF130B0D), Color(0xFF1D1114), Color(0xFFF2E7E6), Color(0xFFAA9093), Color(0xFFC73A4E)),
    PiraThemePreset("golden_wind", "Golden Wind", true, Color(0xFF16100A), Color(0xFF201810), Color(0xFFF5EAD2), Color(0xFFAE9C77), Color(0xFFC99A3E)),
    PiraThemePreset("dracula", "Nightshade", true, Color(0xFF1E0D21), Color(0xFF28162B), Color(0xFFF6ECF7), Color(0xFFAC96AF), Color(0xFFC0F447)),
    PiraThemePreset("cyberpunk", "Cyberpunk", true, Color(0xFF0D0221), Color(0xFF170A33), Color(0xFFF5F0FF), Color(0xFF9D8EC9), Color(0xFFFF2E88)),
    PiraThemePreset("rose_pine", "Rosewood", true, Color(0xFF4B2C39), Color(0xFF573544), Color(0xFFFDEFF4), Color(0xFFCEAFBB), Color(0xFF7FE3CE)),
    PiraThemePreset("solarized_dark", "Tidepool", true, Color(0xFF003631), Color(0xFF00423C), Color(0xFFE8F7F5), Color(0xFF95BEB8), Color(0xFFFBE6AF)),
    PiraThemePreset("everforest", "Slate", true, Color(0xFF343D47), Color(0xFF3E4753), Color(0xFFEFF4FA), Color(0xFFB6C3D1), Color(0xFFF3B7A4)),
    PiraThemePreset("nord", "Dune", true, Color(0xFF3C362A), Color(0xFF474033), Color(0xFFF5EFE6), Color(0xFFC3B8A4), Color(0xFFB6AAFF)),
    PiraThemePreset("vgui", "VGUI", true, Color(0xFF333D2E), Color(0xFF3F4A38), Color(0xFFEEF0E2), Color(0xFFA7B08F), Color(0xFF7A9C34)),
    PiraThemePreset("tokyo_night", "Linen", false, Color(0xFFF0E1C4), Color(0xFFFDF6E6), Color(0xFF2D2114), Color(0xFF665849), Color(0xFF006965)),
    PiraThemePreset("catppuccin_rose", "Sorbet", false, Color(0xFFFEEAEE), Color(0xFFFFF8F9), Color(0xFF361E27), Color(0xFF70545F), Color(0xFFA70D61))
)

@Composable
fun PiraTheme(themeId: String = "desktop_dark", mode: String = "system", content: @Composable () -> Unit) {
    val preset = PiraThemePresets.firstOrNull { it.id == themeId } ?: PiraThemePresets.first()
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val scheme = remember(preset, dark) { buildScheme(preset, dark) }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            var host = view.context
            while (host is ContextWrapper && host !is Activity) host = host.baseContext
            (host as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private fun buildScheme(preset: PiraThemePreset, dark: Boolean): ColorScheme {
    val usePresetPalette = (dark == preset.dark)
    val background = if (usePresetPalette) preset.background else if (dark) Color(0xFF121212) else Color(0xFFF3F3F4)
    val surface = if (usePresetPalette) preset.surface else if (dark) Color(0xFF191919) else Color.White
    val text = if (usePresetPalette) preset.text else if (dark) Color(0xFFEAEAEA) else Color(0xFF101828)
    val muted = if (usePresetPalette) preset.muted else if (dark) Color(0xFF9A9A9A) else Color(0xFF5B6472)
    return if (dark) darkColorScheme(
        primary = preset.accent, onPrimary = Color(0xFF101010), secondary = muted,
        background = background, surface = surface, surfaceVariant = surface.copy(alpha = .92f),
        onSurface = text, onSurfaceVariant = muted, outline = muted.copy(alpha = .45f), error = Color(0xFFE5484D)
    ) else lightColorScheme(
        primary = preset.accent, onPrimary = Color.White, secondary = muted,
        background = background, surface = surface, surfaceVariant = surface.copy(alpha = .92f),
        onSurface = text, onSurfaceVariant = muted, outline = muted.copy(alpha = .45f), error = Color(0xFFC42B1C)
    )
}
