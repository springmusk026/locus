package dev.locus.feature.chat.voice

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import dev.locus.core.agent.ApprovalRequest
import dev.locus.core.model.ApprovalLineText
import dev.locus.core.model.ApprovalPhrase
import dev.locus.core.model.ToolRisk
import dev.locus.feature.chat.SpeechText
import dev.locus.feature.chat.SpeechWords
import dev.locus.feature.chat.speechWords
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Voice mode says its own words in the app's language (docs/design/voice-mode.md): the English strings
 * say exactly what [VoiceWords] and [SpeechWords] say (the words VoiceSessionTest and SpeechTextTest
 * check), every language has its own, and an approval is answered in the words the screen asks for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoiceWordsTest {

    private fun resources(tag: String): Resources {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return context.createConfigurationContext(config).resources
    }

    private val english = voiceWords(resources("en-US"))
    private val spanish = voiceWords(resources("es"))
    private val portuguese = voiceWords(resources("pt-BR"))
    private val chinese = voiceWords(resources("zh-Hans-CN"))

    @Test
    fun englishStringsSayTheWordsVoiceModeAlwaysSaid() {
        val defaults = VoiceWords()
        // The lambdas word tools and approval lines from resources; everything else is the same text.
        assertEquals(defaults, english.copy(activity = defaults.activity, approvalLine = defaults.approvalLine))
        assertEquals(SpeechWords(), speechWords(resources("en-US")))
        assertEquals("Running a command", english.activity("run_command"))
    }

    @Test
    fun everyLanguageHasItsOwnWords() {
        for (words in listOf(spanish, portuguese, chinese)) {
            assertNotEquals(VoiceWords().tapToTalk, words.tapToTalk)
            assertNotEquals(VoiceWords().confirmPrompt, words.confirmPrompt)
            assertNotEquals(VoiceWords().activity("web_search"), words.activity("web_search"))
            assertNotEquals(SpeechWords().code, words.speech.code)
        }
        assertEquals("Toca para hablar", spanish.tapToTalk)
        assertEquals("Bloque de código.", SpeechText.prepare("```\nls\n```", spanish.speech))
    }

    @Test
    fun approvalsAreAnsweredInTheAppsLanguageAndInEnglish() {
        fun said(words: VoiceWords, heard: String, confirming: Boolean = false) = VoiceApprovals.parse(heard, confirming, words.answers)
        // Spanish: the screen says "permitir", then "confirmar", or "rechazar".
        assertEquals(VoiceReply.ALLOW, said(spanish, "Permitir"))
        assertEquals(VoiceReply.ALLOW, said(spanish, "sí"))
        assertEquals(VoiceReply.CONFIRM, said(spanish, "confirmar", confirming = true))
        assertEquals(VoiceReply.UNCLEAR, said(spanish, "sí", confirming = true))
        assertEquals(VoiceReply.DENY, said(spanish, "rechazar"))
        assertEquals(VoiceReply.DENY, said(spanish, "no permitir"))
        assertEquals(VoiceReply.DENY, said(spanish, "no confirmar", confirming = true))
        assertEquals(VoiceReply.UNCLEAR, said(spanish, "sí no"))
        assertEquals(VoiceReply.ALLOW, said(spanish, "allow"))
        // Portuguese, with or without the accent the recognizer writes.
        assertEquals(VoiceReply.ALLOW, said(portuguese, "permitir"))
        assertEquals(VoiceReply.DENY, said(portuguese, "não"))
        assertEquals(VoiceReply.DENY, said(portuguese, "nao permitir"))
        assertEquals(VoiceReply.CONFIRM, said(portuguese, "Confirmar", confirming = true))
        // Chinese is written without spaces: the words count where they are, and a negation still wins.
        assertEquals(VoiceReply.ALLOW, said(chinese, "允许"))
        assertEquals(VoiceReply.CONFIRM, said(chinese, "确认", confirming = true))
        assertEquals(VoiceReply.DENY, said(chinese, "不允许"))
        assertEquals(VoiceReply.DENY, said(chinese, "不确认", confirming = true))
        assertEquals(VoiceReply.DENY, said(chinese, "拒绝"))
        assertEquals(VoiceReply.UNCLEAR, said(chinese, "允许", confirming = true))
    }

    @Test
    fun anApprovalIsSpokenInTheAppsLanguage() {
        val request = ApprovalRequest(
            "chat", "c1", "run_command", ToolRisk.EXECUTE, "Run: ls -la", "{}", flags = emptySet(),
            summaryText = ApprovalLineText(ApprovalPhrase.RUN_COMMAND, "ls -la"),
        )
        assertEquals("Locus wants to: Run: ls -la. Say allow, or deny.", VoiceApprovals.askPrompt(request, english))
        assertEquals("Locus quiere: Ejecutar: ls -la. Di permitir o rechazar.", VoiceApprovals.askPrompt(request, spanish))
        assertEquals("Esto necesita un toque.", VoiceApprovals.tapReason(request.copy(flags = null), spanish))
    }

    @Test
    fun aQuestionIsReadInTheAppsLanguage() {
        val q = dev.locus.core.agent.tools.UserQuestion("chat", "q1", "¿Qué ciudad?", options = listOf("París", "Roma", "Oslo"))
        val state = dev.locus.feature.chat.ChatUiState(run = dev.locus.core.data.agent.RunState(running = true, questions = listOf(q)))
        assertEquals("¿Qué ciudad? Puedes decir París, Roma o Oslo.", voiceView(state, spanish).question)
        assertEquals("¿Qué ciudad? You can say París, Roma, or Oslo.", voiceView(state, english).question)
    }
}
