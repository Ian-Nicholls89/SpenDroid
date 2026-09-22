package com.spendroid.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DarkColors = darkColorScheme(
    primary = Color(0xFF81C784),
    onPrimary = Color(0xFF00390C),
    primaryContainer = Color(0xFF1B5E20),
    onPrimaryContainer = Color(0xFFC8E6C9),
    secondary = Color(0xFFAED581),
    onSecondary = Color(0xFF203A0C),
    secondaryContainer = Color(0xFF2E5233),
    onSecondaryContainer = Color(0xFFCCE8CD),
    tertiary = Color(0xFF90CAF9),
    onTertiary = Color(0xFF0D47A1),
    background = Color(0xFF0F110F),
    onBackground = Color(0xFFE2E8E0),
    surface = Color(0xFF171A17),
    onSurface = Color(0xFFE2E8E0),
    surfaceVariant = Color(0xFF2A322B),
    onSurfaceVariant = Color(0xFFBECDBA),
    outline = Color(0xFF7A8A77),
    outlineVariant = Color(0xFF2A322B),
    error = Color(0xFFEF5350),
    onError = Color.White,
    surfaceTint = Color(0xFF81C784),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E7D32),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8E6C9),
    onPrimaryContainer = Color(0xFF0B3D12),
    secondary = Color(0xFF558B2F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCEDC8),
    onSecondaryContainer = Color(0xFF1B3A0A),
    tertiary = Color(0xFF1976D2),
    onTertiary = Color.White,
    background = Color(0xFFFBFDF8),
    onBackground = Color(0xFF171C17),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171C17),
    surfaceVariant = Color(0xFFE8F0E1),
    onSurfaceVariant = Color(0xFF40503D),
    outline = Color(0xFF6B7A68),
    outlineVariant = Color(0xFFD4E2CE),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    surfaceTint = Color(0xFF2E7D32),
)

private val HeroStart = Color(0xFF2E7D32)
private val HeroEnd = Color(0xFF1B5E20)

val HeroGradientStart: Color
    @Composable get() = HeroStart

val HeroGradientEnd: Color
    @Composable get() = HeroEnd

@Composable
fun BudgetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val dynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = when {
        dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = BudgetTypography,
        shapes = BudgetShapes,
        content = content
    )
}

private val BudgetShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val BudgetTypography = run {
    val base = Typography()
    base.copy(
        displayLarge = base.displayLarge.copy(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-1.5).sp
        ),
        headlineLarge = base.headlineLarge.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp
        ),
        headlineMedium = base.headlineMedium.copy(
            fontWeight = FontWeight.SemiBold
        ),
        headlineSmall = base.headlineSmall.copy(
            fontWeight = FontWeight.SemiBold
        ),
        titleLarge = base.titleLarge.copy(
            fontWeight = FontWeight.SemiBold
        ),
        titleMedium = base.titleMedium.copy(
            fontWeight = FontWeight.SemiBold
        ),
        bodyLarge = base.bodyLarge.copy(
            letterSpacing = 0.15.sp
        ),
        bodyMedium = base.bodyMedium.copy(
            letterSpacing = 0.25.sp
        ),
        labelLarge = base.labelLarge.copy(
            fontWeight = FontWeight.SemiBold
        ),
    )
}