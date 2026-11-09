package dev.locus.feature.settings

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which row Settings → Language checks, and what the hub row says, for the language tags AppLocale reports. */
class LanguageSettingsTest {

    @Test fun `no app language follows the phone`() {
        assertEquals("", appLanguageFor("")?.tag)
        assertEquals("Same as phone", languageSummary(""))
    }

    @Test fun `each offered tag checks its own row`() {
        AppLanguages.forEach { assertEquals(it, appLanguageFor(it.tag)) }
    }

    @Test fun `a region or a second locale still finds the language`() {
        assertEquals("en-US", appLanguageFor("en-GB")?.tag)
        assertEquals("pt", appLanguageFor("pt-BR")?.tag)
        assertEquals("pt", appLanguageFor("pt-PT")?.tag)
        assertEquals("es", appLanguageFor("es-419,en-US")?.tag)
        assertEquals("zh-Hans", appLanguageFor("zh-CN")?.tag)
        assertEquals("zh-Hans", appLanguageFor("zh-Hans-SG")?.tag)
    }

    @Test fun `traditional chinese and other languages match no row`() {
        assertNull(appLanguageFor("zh-Hant"))
        assertNull(appLanguageFor("zh-TW"))
        assertNull(appLanguageFor("fr-FR"))
        // The hub row still names it, in its own language.
        assertEquals("français (France)", languageSummary("fr-FR"))
    }

    @Test fun `the summary is the language's own name`() {
        assertEquals("Español", languageSummary("es"))
        assertEquals("Português (Brasil)", languageSummary("pt"))
        assertEquals("简体中文", languageSummary("zh-Hans"))
    }
}
