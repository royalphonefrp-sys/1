package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RoyalCasinoColorScheme = darkColorScheme(
    primary = SuperGold,
    onPrimary = OnCasinoGold,
    secondary = IchancyMint,
    onSecondary = Color.White,
    tertiary = HotPink,
    onTertiary = Color.White,
    background = IchancyDeepPurple,
    onBackground = Color.White,
    surface = IchancyDarkViolet,
    onSurface = Color.White,
    surfaceVariant = CasinoSurfaceVariant,
    onSurfaceVariant = Color.LightGray,
    error = CrownCrimson,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Force dark theme for premium casino atmosphere
    dynamicColor: Boolean = false, // Disable dynamic colors to preserve royal gold/aesthetic
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = RoyalCasinoColorScheme,
        typography = Typography,
        content = content
    )
}
