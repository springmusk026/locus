package dev.locus.core.agent

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.home.homeTools
import dev.locus.core.agent.integrations.emailTools
import dev.locus.core.agent.integrations.integrationTools
import dev.locus.core.agent.tools.ProviderDraftStore
import dev.locus.core.agent.tools.ProviderDraftTool
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.agent.tools.workspaceTools
import dev.locus.core.model.ApprovalLineText
import dev.locus.core.model.ApprovalPhrase
import dev.locus.core.model.ProviderDraft
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The approval lines of core/agent's tools that ask (docs/design/localization.md): each tool's `describe`
 * still says exactly the English it said before its line became data (the audit log's, the reviewer's),
 * and its [Tool.describeText] says the same English, so the card can word it in the user's language.
 */
class ApprovalLinesTest {
    private val base = Files.createTempDirectory("lines").toFile()
    private val ws = Workspace(File(base, "workspace"), File(base, "trash"))
    private val http = HttpClient(MockEngine { respond("") })

    private val tools: Map<String, Tool> = (
        workspaceTools(ws) +
            homeTools(AgentHome(File(base, "home")), http, SecretResolver { null }, origins = { emptySet() }) +
            integrationTools(http, SecretResolver { null }, ws) +
            emailTools(SecretResolver { null }) +
            ProviderDraftTool(
                object : ProviderDraftStore {
                    override suspend fun save(draft: ProviderDraft) = draft
                },
            )
        ).associateBy { it.spec.name }

    private fun args(json: String) = Json.parseToJsonElement(json) as JsonObject

    /** [name] with [json] says [english], and its line as data says the same. */
    private fun says(english: String, name: String, json: String) {
        val tool = assertNotNull(tools[name], name)
        val a = args(json)
        assertEquals(english, tool.describe(a), "$name $json")
        val line = assertNotNull(tool.describeText(a), "$name has no line as data")
        assertEquals(english, line.english, "$name $json")
    }

    @Test
    fun workspaceToolsSayWhatTheyAlwaysSaid() {
        says("Create notes.md (5 characters)", "write_file", """{"path":"notes.md","content":"hello"}""")
        File(ws.root, "old.md").writeText("x")
        says("Overwrite old.md (1 characters)", "write_file", """{"path":"old.md","content":"y"}""")
        says("Edit a.kt", "edit_file", """{"path":"a.kt"}""")
        says("Edit a.kt (1 changes)", "multi_edit", """{"path":"a.kt","edits":[{}]}""")
        says("Edit a.kt (0 changes)", "multi_edit", """{"path":"a.kt"}""")
        says("Move a → b/a", "move_path", """{"from":"a","to":"b/a"}""")
        says("Copy a → b", "copy_path", """{"from":"a","to":"b"}""")
        says("Delete old/ (recoverable from trash)", "delete_path", """{"path":"old/"}""")
        says("Create folder src", "make_directory", """{"path":"src"}""")
    }

    @Test
    fun memoryAndDraftsSayWhatTheyAlwaysSaid() {
        says("Remember “coffee”", "memory_save", """{"topic":"coffee","content":"oat"}""")
        says("Forget “coffee”", "memory_forget", """{"topic":"coffee"}""")
        says("Draft provider “Groq” at api.groq.com for your review", dev.locus.core.model.ProviderDrafts.TOOL, """{"name":"Groq","base_url":"https://api.groq.com/openai/v1"}""")
        says("Draft provider “Local” at 192.168.1.5 for your review", dev.locus.core.model.ProviderDrafts.TOOL, """{"name":"Local","base_url":"http://192.168.1.5:8080/v1"}""")
    }

    @Test
    fun emailSaysWhatItAlwaysSaid() {
        says("Send email to a@b.com: “Hi” — Body text", "email_send", """{"to":"Ann <a@b.com>","subject":"Hi","body":"Body\n text"}""")
        says("Send email to a@b.com (cc c@d.com): “reply” — ok", "email_send", """{"to":"a@b.com","cc":"c@d.com","body":"ok","reply_to_message_id":"m1"}""")
        says("Send email to (an invalid address list, which won't be sent): “(no subject)” — ", "email_send", """{"to":"not an address"}""")
        // Locus's own words inside the line are data too, so the card words them in the same language.
        val line = tools.getValue("email_send").describeText(args("""{"to":"not an address"}"""))!!
        assertEquals(ApprovalLineText(ApprovalPhrase.EMAIL_INVALID_ADDRESSES), line.args[0])
        assertEquals(ApprovalLineText(ApprovalPhrase.EMAIL_NO_SUBJECT), line.args[1])
    }

    @Test
    fun platformWritesSayWhatTheyAlwaysSaid() {
        says("Open issue “Crash” in me/app", "github_create_issue", """{"repo":"me/app","title":"Crash"}""")
        says("Comment on me/app#12", "github_comment", """{"repo":"me/app","number":12}""")
        says("Deploy site to Vercel project web (production)", "vercel_deploy", """{"path":"site","project":"web","production":true}""")
        says("Deploy site to Vercel project web (preview)", "vercel_deploy", """{"path":"site","project":"web"}""")
        says("Redeploy dpl_1 to Vercel project web (preview)", "vercel_deploy", """{"redeploy_id":"dpl_1","project":"web"}""")
        says("Set API_URL on Vercel project web", "vercel_env_set", """{"key":"API_URL","project":"web","value":"x"}""")
        says("Deploy site to Netlify site blog (draft)", "netlify_deploy", """{"path":"site","site":"blog"}""")
        says("Deploy site to Netlify site blog (production)", "netlify_deploy", """{"path":"site","site":"blog","production":true}""")
        says("Set KEY on Netlify site blog", "netlify_env_set", """{"key":"KEY","site":"blog"}""")
        says("Deploy Render service api", "render_deploy", """{"service":"api"}""")
        says("Deploy Render service api at abcdef1 (clear cache)", "render_deploy", """{"service":"api","commit":"abcdef1234","clear_cache":true}""")
        says("Set KEY on Render service api", "render_env_set", """{"key":"KEY","service":"api"}""")
        says("Resume Render service api", "render_suspend", """{"service":"api","action":" Resume"}""")
        says("Suspend Render service api", "render_suspend", """{"service":"api","action":"suspend"}""")
        says("Run SQL on proj: delete from t where id = 1", "supabase_sql", """{"project":"proj","sql":"delete from t\n where id = 1"}""")
        says("Deploy fn as Edge Function hello on proj", "supabase_function_deploy", """{"path":"fn","slug":"hello","project":"proj"}""")
        says("Set DNS CNAME www → example.com in example.org", "cloudflare_dns_set", """{"type":"cname","name":"www","content":"example.com","zone":"example.org"}""")
        says("Delete DNS record r1 from example.org", "cloudflare_dns_delete", """{"record_id":"r1","zone":"example.org"}""")
        says("Deploy Pages project blog (production)", "cloudflare_pages_deploy", """{"project":"blog"}""")
        says("Deploy Pages project blog from dev", "cloudflare_pages_deploy", """{"project":"blog","branch":"dev"}""")
        says("Publish site to Cloudflare as blog.workers.dev", "cloudflare_deploy_static", """{"path":"site","name":"blog"}""")
        says("Publish site to Cloudflare as blog.workers.dev (single-page app)", "cloudflare_deploy_static", """{"path":"site","name":"blog","spa":true}""")
    }

    @Test
    fun aPluralLineCountsAndAnUnknownToolHasNone() {
        val line = tools.getValue("multi_edit").describeText(args("""{"path":"a.kt","edits":[{},{}]}"""))!!
        assertEquals(2, line.count)
        // A tool that doesn't ask has no line as data: the card shows its describe() as it is.
        assertEquals(null, tools.getValue("read_file").describeText(args("""{"path":"a"}""")))
    }
}
