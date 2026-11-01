package dev.locus.core.designsystem

import androidx.compose.ui.graphics.compositeOver
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

// Paper and ink neutrals with a single moss accent. Error and the "needs approval" amber are the
// only other hues, so state colours stay meaningful.
private val Light = lightColorScheme(
    primary = Color(0xFF2E6B5E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD5E8E1),
    onPrimaryContainer = Color(0xFF0E2C25),
    secondary = Color(0xFF5B6360),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4E7E4),
    onSecondaryContainer = Color(0xFF1B201E),
    tertiary = Color(0xFF8A5A12),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF6E3C4),
    onTertiaryContainer = Color(0xFF2C1A00),
    background = Color(0xFFF7F6F2),
    onBackground = Color(0xFF1A1C1B),
    surface = Color(0xFFF7F6F2),
    onSurface = Color(0xFF1A1C1B),
    surfaceVariant = Color(0xFFE6E5E0),
    onSurfaceVariant = Color(0xFF55595A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F0EB),
    surfaceContainer = Color(0xFFECEBE6),
    surfaceContainerHigh = Color(0xFFE6E5E0),
    surfaceContainerHighest = Color(0xFFE0DFDA),
    outline = Color(0xFF8A8E8C),
    outlineVariant = Color(0xFFD4D4CF),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8CCBBB),
    onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF16483E),
    onPrimaryContainer = Color(0xFFCFE9E0),
    secondary = Color(0xFFBFC6C2),
    onSecondary = Color(0xFF2A302D),
    secondaryContainer = Color(0xFF353B38),
    onSecondaryContainer = Color(0xFFDDE3DF),
    tertiary = Color(0xFFE9BE7A),
    onTertiary = Color(0xFF452B00),
    tertiaryContainer = Color(0xFF5B3F0B),
    onTertiaryContainer = Color(0xFFFFDDB0),
    background = Color(0xFF121413),
    onBackground = Color(0xFFE3E3DF),
    surface = Color(0xFF121413),
    onSurface = Color(0xFFE3E3DF),
    surfaceVariant = Color(0xFF2C2F2D),
    onSurfaceVariant = Color(0xFFB4B8B5),
    surfaceContainerLowest = Color(0xFF0D0F0E),
    surfaceContainerLow = Color(0xFF1A1C1B),
    surfaceContainer = Color(0xFF1E201F),
    surfaceContainerHigh = Color(0xFF282A29),
    surfaceContainerHighest = Color(0xFF333534),
    outline = Color(0xFF8D918E),
    outlineVariant = Color(0xFF3F4341),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

private val LocusTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(lineHeight = 24.sp),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

/** Monospace for paths, tool arguments and command output. */
val CodeTextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp)

// One radius per role: chips and small controls, tiles and fields, cards, sheets and the composer.
private val LocusShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.chip),
    small = RoundedCornerShape(Radius.chip),
    medium = RoundedCornerShape(Radius.control),
    large = RoundedCornerShape(Radius.card),
    extraLarge = RoundedCornerShape(Radius.sheet),
)

@Composable
fun LocusTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors: ColorScheme = if (darkTheme) Dark else Light
    CompositionLocalProvider(LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = colors, typography = LocusTypography, shapes = LocusShapes, content = content)
    }
}

/**
 * The only spacing values in the app. Screens pad 16 at the edges, cards pad 12 inside and sit 12
 * apart, and related lines inside a group are 4 or 8 apart.
 */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Offsets built from [Spacing] that several screens share. */
object Insets {
    /** Where a row divider starts in a list of [ListRow]s with a 36dp leading tile: past the tile, under the text. */
    val iconRow = Spacing.lg + 36.dp + Spacing.md
}

object Radius {
    val chip = 8.dp
    val control = 12.dp
    val card = 16.dp
    val sheet = 28.dp
}

/**
 * Colours that mean a state, never decoration: something is running now (live), needs the user
 * (caution), or went wrong (critical). Cards and hairlines are the neutral surfaces everything sits on.
 */
@Immutable
data class StatusColors(
    val live: Color,
    val caution: Color,
    val critical: Color,
    val card: Color,
    val hairline: Color,
    val track: Color,
    /** A control raised above its track, like a segmented control's selection. */
    val raised: Color,
)

private val LightStatus = StatusColors(
    live = Color(0xFF1E8E50),
    caution = Color(0xFFB0680A),
    critical = Color(0xFFC4352B),
    card = Color(0xFFFFFFFF),
    hairline = Color(0x14000000),
    track = Color(0x1C000000),
    raised = Color(0xFFFFFFFF),
)

private val DarkStatus = StatusColors(
    live = Color(0xFF55D88A),
    caution = Color(0xFFF5B653),
    critical = Color(0xFFFF8A80),
    card = Color(0x0DFFFFFF),
    hairline = Color(0x17FFFFFF),
    track = Color(0x21FFFFFF),
    raised = Color(0xFF3A3E3C),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

/** The terminal panel's own palette: always dark, like a terminal, in either theme. */
object TerminalColors {
    val background = Color(0xFF15181A)
    /** A shade deeper in the dark theme, so the panel still reads as a terminal there. */
    val backgroundDark = Color(0xFF0B0D0C)
    val prompt = Color(0xFF6FD6A0)
    val output = Color(0xFFD7DBD8)
    /** The dark theme's critical, since the panel is dark. */
    val error = DarkStatus.critical
}

/**
 * For things that float over scrolling content (the composer and the cards above it): cards become
 * opaque, the same colour they'd have on the page, so content passing underneath never shows through.
 */
@Composable
fun FloatingLayer(content: @Composable () -> Unit) {
    val colors = Locus.colors
    val opaque = colors.card.compositeOver(MaterialTheme.colorScheme.background)
    CompositionLocalProvider(LocalStatusColors provides colors.copy(card = opaque), content = content)
}

/** Shorthand: `Locus.colors.live`. */
object Locus {
    val colors: StatusColors
        @Composable @ReadOnlyComposable get() = LocalStatusColors.current
}

/** Small mono text for times, sizes, paths and ids, so changing values don't jitter. */
val MonoCaption = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
