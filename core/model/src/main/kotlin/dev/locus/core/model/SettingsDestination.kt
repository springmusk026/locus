package dev.locus.core.model

import kotlinx.serialization.Serializable

/**
 * A page inside Settings that another screen can open directly ("Settings → Models"). It is the
 * argument of the app's Settings route, so a hint, a banner's action or an error can take the user
 * to the page it talks about instead of naming it.
 *
 * [path] is the page as the app writes it after "Settings": the hub row's own words, so a link reads
 * the same as the row it lands on.
 */
@Serializable
enum class SettingsDestination(val path: List<String>) {
    Models(listOf("Models")),
    Extras(listOf("Helper model & extras")),
    Network(listOf("Network")),
    Agent(listOf("Agent")),
    Permissions(listOf("Permissions")),
    Battery(listOf("Battery & heat")),
    Voice(listOf("Voice")),
    Automation(listOf("Automation")),
    Integrations(listOf("Integrations")),
    Ssh(listOf("SSH machines")),
    Backup(listOf("Backup & restore")),
    About(listOf("About & privacy")),
    Diagnostics(listOf("About & privacy", "Diagnostics")),
    ;

    /** The page's own name: the last part of [path] ("Diagnostics", "SSH machines"). */
    val title: String get() = path.last()

    companion object {
        /** What text calls each page. Older messages say "Providers" for Models and "Integrations & connectors". */
        private val byLabel: Map<String, SettingsDestination> = buildMap {
            SettingsDestination.entries.filter { it.path.size == 1 }.forEach { put(it.path.single().lowercase(), it) }
            put("providers", Models)
            put("integrations & connectors", Integrations)
            put("helper model", Extras)
            put("about", About)
            put("diagnostics", Diagnostics)
        }

        /** A destination by its enum name, as a saved route or a test writes it; null when unknown. */
        fun fromName(name: String?): SettingsDestination? = entries.firstOrNull { it.name == name }

        /**
         * The Locus settings page that [text] sends the user to ("… Add it in Settings → Models."), or null
         * when it names none. Only the pages Locus has count, so Android's "Settings → Apps → Locus" and
         * "ChatGPT Settings → Usage" don't match; nor does another app's "Stitch → Settings → …".
         */
        fun inText(text: String?): SettingsDestination? {
            if (text.isNullOrBlank()) return null
            SETTINGS_PATH.findAll(text).forEach { match ->
                val before = text.substring(0, match.range.first).trimEnd()
                if (before.endsWith("→") && !before.endsWith("Locus →")) return@forEach
                val parts = match.groupValues[1].split('→').map { it.trim().trimEnd('.', ',', ';', ':', ')').trim() }.filter { it.isNotEmpty() }
                val found = parts.firstOrNull()?.let(::pageNamed) ?: return@forEach
                return if (found == About && parts.getOrNull(1)?.lowercase()?.startsWith("diagnostics") == true) Diagnostics else found
            }
            return null
        }

        /**
         * The page [part] starts with: "Models first" is Models. The longest name wins, so "Helper model &
         * extras" isn't read as "Helper model".
         */
        private fun pageNamed(part: String): SettingsDestination? {
            val text = part.lowercase()
            return byLabel.entries.filter { (label, _) -> text == label || text.startsWith("$label ") }.maxByOrNull { it.key.length }?.value
        }

        /** "Settings → A → B": the parts after Settings, up to the end of the sentence. */
        private val SETTINGS_PATH = Regex("""Settings\s*→\s*([^.,;:()\n]+(?:\s*→\s*[^.,;:()\n]+)*)""")
    }
}
