package dev.locus.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Test
import kotlin.test.assertTrue

/**
 * WCAG AA for the theme's text roles (docs/design/design-system.md, "Review gate"): every colour that
 * text is drawn in, on every surface it's drawn on, reaches 4.5:1 in both themes. Text that keeps to
 * these roles (and to a Surface, so it inherits one) can't be unreadable; a new role or surface goes
 * in [pairs] with its use.
 */
class ContrastTest {

    private class Pair(val use: String, val text: Color, val background: Color)

    private fun pairs(scheme: ColorScheme, status: StatusColors): List<Pair> {
        val bg = scheme.background
        val card = status.card.compositeOver(bg)
        val surfaces = mapOf("background" to bg, "surface" to scheme.surface, "card" to card)
        val text = mapOf(
            "onBackground" to scheme.onBackground,
            "onSurface" to scheme.onSurface,
            "onSurfaceVariant (SupportingText)" to scheme.onSurfaceVariant,
            "primary (links, text buttons)" to scheme.primary,
        )
        val roles = text.flatMap { (t, tc) -> surfaces.map { (s, sc) -> Pair("$t on $s", tc, sc) } }
        val tones = mapOf(
            "Brand" to (scheme.primary to scheme.primary),
            "Live" to (status.live to status.onLiveContainer),
            "Caution" to (status.caution to status.onCautionContainer),
            "Critical" to (status.critical to status.onCriticalContainer),
            "Info" to (status.info to status.onInfoContainer),
            "Neutral" to (scheme.onSurfaceVariant to scheme.onSurfaceVariant),
        ).flatMap { (name, colours) ->
            val (tone, on) = colours
            val alpha = if (name == "Neutral") Emphasis.neutralContainer else Emphasis.toneContainer
            surfaces.map { (s, sc) -> Pair("$name chip text on its container over $s", on, tone.copy(alpha = alpha).compositeOver(sc)) }
        }
        val filled = listOf(
            Pair("onPrimary on primary (buttons)", scheme.onPrimary, scheme.primary),
            Pair("surface on critical (destructive buttons)", scheme.surface, status.critical),
            Pair("onError on error", scheme.onError, scheme.error),
            Pair("onSurface on surfaceVariant (tonal buttons)", scheme.onSurface, scheme.surfaceVariant),
            Pair("onSurface on raised (segmented selection)", scheme.onSurface, status.raised.compositeOver(bg)),
        )
        // A state colour is also text on its own: a warning line, a failed count, a script's name.
        val stateText = mapOf("live" to status.live, "caution" to status.caution, "critical" to status.critical, "info" to status.info)
            .flatMap { (n, c) -> surfaces.map { (sn, sc) -> Pair("$n text on $sn", c, sc) } }
        return roles + tones + filled + stateText
    }

    @Test
    fun textRolesMeetAaInBothThemes() {
        val failures = listOf("light" to (LocusLightColors to LightStatus), "dark" to (LocusDarkColors to DarkStatus))
            .flatMap { (theme, schemes) ->
                pairs(schemes.first, schemes.second).mapNotNull { p ->
                    val ratio = contrast(p.text, p.background)
                    if (ratio < AA) "$theme: ${p.use} is ${"%.2f".format(ratio)}:1" else null
                }
            }
        assertTrue(failures.isEmpty(), "Below WCAG AA ($AA:1):\n" + failures.joinToString("\n"))
    }

    companion object {
        const val AA = 4.5

        fun contrast(a: Color, b: Color): Double {
            val la = a.luminance() + 0.05
            val lb = b.luminance() + 0.05
            return (maxOf(la, lb) / minOf(la, lb)).toDouble()
        }
    }
}
