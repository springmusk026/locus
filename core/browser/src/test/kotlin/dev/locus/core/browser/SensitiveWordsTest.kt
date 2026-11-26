package dev.locus.core.browser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The page script's field patterns (#363). They run as JavaScript in the WebView (`new RegExp(src, 'iu')`),
 * which a JVM test can't execute; here they are compiled as Kotlin does for [SensitiveActions], and the
 * sources are written to mean the same in both (no `\b`, no flags inside the pattern). The page script
 * itself is checked to take them from [SensitiveWords] rather than keep its own copies.
 */
class SensitiveWordsTest {
    private fun regex(src: String) = Regex(src, RegexOption.IGNORE_CASE)
    private val autocomplete = regex(SensitiveWords.SENSITIVE_AUTOCOMPLETE)
    private val payment = regex(SensitiveWords.PAYMENT)
    private val otp = regex(SensitiveWords.OTP)
    private val search = regex(SensitiveWords.SEARCH)

    /** What sensitive() in locus-agent.js tests: the autocomplete token, then name, id and token together. */
    private fun sensitive(name: String = "", id: String = "", ac: String = ""): Boolean {
        val names = "$name $id $ac"
        return autocomplete.containsMatchIn(ac) || payment.containsMatchIn(names) || otp.containsMatchIn(names)
    }

    @Test
    fun autocompleteTokensAreCaseInsensitive() {
        listOf("One-Time-Code", "ONE-TIME-CODE", "Current-Password", "New-Password", "CC-Number", "cc-csc").forEach {
            assertTrue(sensitive(ac = it), it)
        }
        assertFalse(sensitive(ac = "username"))
        assertFalse(sensitive(ac = "email"))
    }

    @Test
    fun localisedCardFieldsAreSensitive() {
        listOf("numero_tarjeta", "NumeroTarjeta", "cartao", "numeroCartão", "validade", "fecha_vencimiento", "caducidad",
            "codigo_de_seguridad", "卡号", "信用卡号码", "安全码", "cardNumber", "cvv").forEach {
            assertTrue(sensitive(name = it), it)
        }
    }

    @Test
    fun oneTimeCodeFieldsAreSensitiveInEveryLanguage() {
        listOf("otp", "OTP", "otp_code", "smsOtp", "otpInput", "totp", "one_time_code", "verificationCode", "codigo_verificacion",
            "código de verificação", "codigoDeAcesso", "验证码", "驗證碼").forEach {
            assertTrue(sensitive(id = it), it)
        }
    }

    @Test
    fun ordinaryFieldsAreNot() {
        listOf("footprint", "hotpot", "codigo_postal", "código postal", "nombre", "email", "telefone", "cartoon", "cart_qty", "search").forEach {
            assertFalse(sensitive(name = it), it)
        }
    }

    @Test
    fun localisedSearchFieldsAreSearches() {
        listOf("Buscar", "busca", "búsqueda", "Pesquisar", "pesquisa", "搜索", "搜尋").forEach { assertTrue(search.containsMatchIn(it), it) }
        assertFalse(search.containsMatchIn("nombre"))
    }

    @Test
    fun thePageScriptTakesTheSharedWords() {
        val source = File("src/main/assets/locus-agent.js").readText()
        // No hard-coded copies left, which would drift from SensitiveWords (and miss "One-Time-Code").
        assertFalse("var SENSITIVE_AUTOCOMPLETE = /" in source)
        assertFalse("var PAYMENT = /" in source)
        assertFalse("var SEARCHY = /" in source)
        assertTrue("var WORDS = ${SensitiveWords.PLACEHOLDER};" in source)
        assertTrue("new RegExp(WORDS.sensitiveAutocomplete, 'iu')" in source)
        val filled = SensitiveWords.forPageScript(source)
        assertFalse(SensitiveWords.PLACEHOLDER in filled)
        // The object the script reads holds exactly these sources.
        val words = Json.parseToJsonElement(SensitiveWords.pageScriptJson).jsonObject
        assertEquals(SensitiveWords.SENSITIVE_AUTOCOMPLETE, words.getValue("sensitiveAutocomplete").jsonPrimitive.content)
        assertEquals(SensitiveWords.PAYMENT, words.getValue("payment").jsonPrimitive.content)
        assertEquals(SensitiveWords.OTP, words.getValue("otp").jsonPrimitive.content)
        assertEquals(SensitiveWords.SEARCH, words.getValue("search").jsonPrimitive.content)
        assertTrue("var WORDS = ${SensitiveWords.pageScriptJson};" in filled)
    }
}
