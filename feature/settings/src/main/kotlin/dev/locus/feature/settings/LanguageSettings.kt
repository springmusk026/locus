package dev.locus.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import java.util.Locale

/**
 * A language Settings → Language offers (docs/design/localization.md). [tag] is what goes to
 * [dev.locus.core.designsystem.AppLocale.set], empty for "Same as phone"; the others are the tags in the
 * generated LocaleConfig, so the choice matches App info → Language on Android 13+.
 */
internal data class AppLanguage(val tag: String, val name: String)

/**
 * Not translated: each language is named in its own language, so whoever set the wrong one can find
 * theirs. They stay literals (or `translatable="false"`) when the screen's strings move to resources.
 */
internal val AppLanguages = listOf(
    AppLanguage("", "Same as phone"),
    AppLanguage("en-US", "English"),
    AppLanguage("es", "Español"),
    // values-pt holds Brazilian Portuguese.
    AppLanguage("pt", "Português (Brasil)"),
    AppLanguage("zh-Hans", "简体中文"),
)

/**
 * Which of [AppLanguages] [tags] (a comma-separated locale list, "" for none) means, or null for a
 * language Locus doesn't offer (one set through another app, say). Only the first locale counts, and
 * only its language: "pt-PT" is still the Portuguese row. Traditional Chinese isn't Simplified.
 */
internal fun appLanguageFor(tags: String): AppLanguage? {
    val first = tags.substringBefore(',').trim()
    if (first.isEmpty()) return AppLanguages.first()
    val locale = Locale.forLanguageTag(first)
    val tag = when (locale.language) {
        "en" -> "en-US"
        "es" -> "es"
        "pt" -> "pt"
        "zh" -> "zh-Hans".takeUnless { locale.script == "Hant" || (locale.script.isEmpty() && locale.country in TRADITIONAL_REGIONS) }
        else -> null
    }
    return AppLanguages.firstOrNull { it.tag == tag }
}

private val TRADITIONAL_REGIONS = setOf("TW", "HK", "MO")

/** The Language row's summary: the language's own name, or "Same as phone". */
internal fun languageSummary(tags: String): String =
    appLanguageFor(tags)?.name ?: Locale.forLanguageTag(tags.substringBefore(',').trim()).let { it.getDisplayName(it) }

/** Settings → Language: one row per language, the current one checked. Picking one closes the sheet. */
@Composable
internal fun LanguageSheet(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val selected = appLanguageFor(current)
    LocusSheet(title = "Language", onDismiss = onDismiss) {
        Column {
            CardGroup {
                AppLanguages.forEachIndexed { i, language ->
                    if (i > 0) GroupDivider()
                    ListRow(
                        title = language.name,
                        titleMaxLines = 2,
                        onClick = { onPick(language.tag) },
                        trailing = {
                            if (language == selected) {
                                Icon(LocusIcons.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                            }
                        },
                    )
                }
            }
        }
    }
}
