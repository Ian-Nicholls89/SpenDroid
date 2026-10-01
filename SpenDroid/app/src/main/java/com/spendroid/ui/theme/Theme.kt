package com.spendroid.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.unit.TextUnit
import com.spendroid.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app's accent: buttons, the selected tab, the glow behind the header, headings in a
 * list. The user picks it; orange is nzb360's own and the default.
 */
enum class Accent(val label: String, val colour: Color) {
    ORANGE("Orange", Color(0xFFF5A623)),
    BLUE("Blue", Color(0xFF29B6F6)),
    GREEN("Green", Color(0xFF43B05C)),
    PINK("Pink", Color(0xFFE04A7A)),
    ;

    companion object {
        fun from(name: String?): Accent = entries.firstOrNull { it.name == name } ?: ORANGE
    }
}

/** The charcoal the whole app sits on, and the panels on top of it. */
object Charcoal {
    val Background = Color(0xFF18191D)
    val Panel = Color(0xFF23252B)
    val PanelHigh = Color(0xFF2A2C33)
    val Line = Color(0xFF2E3037)
    val Text = Color(0xFFFFFFFF)
    val Muted = Color(0xFF9AA0AA)
    val Faint = Color(0xFF6B707A)
    val Good = Color(0xFF43B05C)
    val Warn = Color(0xFFF5A623)
    val Bad = Color(0xFFE5534B)
    val In = Color(0xFF5FD38A)
}

/** Dark always: nzb360's look is built on it, and the colours below are chosen against it. */
private fun charcoalScheme(accent: Color) = darkColorScheme(
    primary = accent,
    onPrimary = Color(0xFF111111),
    primaryContainer = accent.copy(alpha = 0.22f).compositeOver(Charcoal.Panel),
    onPrimaryContainer = Color.White,
    secondary = accent,
    onSecondary = Color(0xFF111111),
    secondaryContainer = Charcoal.PanelHigh,
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFF29B6F6),
    onTertiary = Color(0xFF08121A),
    tertiaryContainer = Charcoal.PanelHigh,
    onTertiaryContainer = Color.White,
    background = Charcoal.Background,
    onBackground = Charcoal.Text,
    surface = Charcoal.Background,
    onSurface = Charcoal.Text,
    surfaceVariant = Charcoal.PanelHigh,
    onSurfaceVariant = Charcoal.Muted,
    surfaceContainerLowest = Charcoal.Background,
    surfaceContainerLow = Color(0xFF1F2025),
    surfaceContainer = Charcoal.Panel,
    surfaceContainerHigh = Charcoal.PanelHigh,
    surfaceContainerHighest = Color(0xFF32353D),
    inverseSurface = Color(0xFFE8E9EC),
    inverseOnSurface = Charcoal.Background,
    outline = Color(0xFF5A5F68),
    outlineVariant = Charcoal.Line,
    error = Charcoal.Bad,
    onError = Color.White,
    surfaceTint = Color.Transparent,
    scrim = Color.Black,
)

/** The header's glow and the old hero gradient, from the accent. */
val HeroGradientStart: Color
    @Composable get() = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f).compositeOver(Charcoal.Panel)

val HeroGradientEnd: Color
    @Composable get() = Charcoal.Panel

@Composable
fun BudgetTheme(
    accent: Accent = Accent.ORANGE,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = charcoalScheme(accent.colour),
        typography = BudgetTypography,
        shapes = BudgetShapes,
        content = content
    )
}

private val BudgetShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

val Lato = FontFamily(
    Font(R.font.lato_regular, FontWeight.Normal),
    Font(R.font.lato_bold, FontWeight.Bold),
    Font(R.font.lato_bold, FontWeight.SemiBold),
    Font(R.font.lato_bold, FontWeight.Medium),
    Font(R.font.lato_black, FontWeight.Black),
    Font(R.font.lato_black, FontWeight.ExtraBold),
)

/** Lato throughout, as nzb360 uses; headings heavier than Material's. */
private val BudgetTypography = run {
    val base = Typography()
    fun TextStyle.lato(weight: FontWeight? = null, spacing: TextUnit? = null) =
        copy(fontFamily = Lato, fontWeight = weight ?: fontWeight, letterSpacing = spacing ?: letterSpacing)
    base.copy(
        displayLarge = base.displayLarge.lato(FontWeight.Black, (-1).sp),
        displayMedium = base.displayMedium.lato(FontWeight.Black, (-0.5).sp),
        displaySmall = base.displaySmall.lato(FontWeight.Black),
        headlineLarge = base.headlineLarge.lato(FontWeight.Black, (-0.5).sp),
        headlineMedium = base.headlineMedium.lato(FontWeight.Black),
        headlineSmall = base.headlineSmall.lato(FontWeight.Black),
        titleLarge = base.titleLarge.lato(FontWeight.Black),
        titleMedium = base.titleMedium.lato(FontWeight.Bold),
        titleSmall = base.titleSmall.lato(FontWeight.Bold),
        bodyLarge = base.bodyLarge.lato(spacing = 0.15.sp),
        bodyMedium = base.bodyMedium.lato(spacing = 0.2.sp),
        bodySmall = base.bodySmall.lato(),
        labelLarge = base.labelLarge.lato(FontWeight.Bold),
        labelMedium = base.labelMedium.lato(FontWeight.Bold),
        labelSmall = base.labelSmall.lato(FontWeight.Bold),
    )
}
