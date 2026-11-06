package dev.locus.core.data.device

import dev.locus.core.agent.ObjectSchema
import dev.locus.core.agent.SecretScrubber
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.str
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.model.DeviceIntegrations
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Three small hand-offs to the phone (docs/design/phone-integrations.md): a new contact in the Contacts
 * app, a workspace file saved through the system "Save as" picker, and text copied to the clipboard.
 * Each needs no permission; the user confirms the first two in the system's own screens.
 */
fun phoneHandoffTools(phone: PhoneIntegrations, workspace: Workspace, scrubber: suspend () -> SecretScrubber): List<Tool> =
    listOf(ContactAdd(phone), SaveFile(phone, workspace), Copy(phone, scrubber))

private const val NOT_ON_SCREEN = "Locus isn't on screen, so it can't open that now. Ask the user to open Locus and try again."

internal class ContactAdd(private val phone: PhoneIntegrations) : Tool {
    override val risk = ToolRisk.WRITE

    // The Contacts app shows the filled-in contact and the user saves it there; asking here too would be a double prompt.
    override val defaultPolicy = ToolPolicy.ALLOW

    override val spec = ToolSpec(
        DeviceIntegrations.CONTACT_ADD,
        "Open the phone's Contacts app on a new contact filled in with these details, for the user to check and save. " +
            "Nothing is saved unless they do; Locus can't read contacts.",
        ObjectSchema()
            .string("name", "Full name.")
            .string("phones", "Phone numbers, comma-separated (at most 3).", required = false)
            .string("emails", "Email addresses, comma-separated (at most 3).", required = false)
            .string("company", "Company.", required = false)
            .string("note", "A note on the contact.", required = false)
            .build(),
    )

    override fun describe(args: JsonObject) = "New contact: ${args.str("name").orEmpty().take(80)}"

    override suspend fun execute(args: JsonObject): ToolResult {
        val contact = contact(args)
        return when (phone.insertContact(contact)) {
            is UserPick.Picked -> ToolResult("Opened Contacts with “${contact.name}” filled in. The user saves it there; nothing is saved until they do.")
            UserPick.Cancelled -> ToolResult.error("No contacts app on this phone can add a contact.")
            UserPick.NotOnScreen -> ToolResult.error(NOT_ON_SCREEN)
        }
    }

    companion object {
        private val PHONE = Regex("\\+?[0-9 ()./-]{3,25}")
        private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

        internal fun contact(args: JsonObject): NewContact {
            val name = args.str("name")?.trim()?.takeIf { it.isNotEmpty() } ?: throw ToolException("Give the contact a name.")
            val phones = split(args.str("phones"))
            phones.firstOrNull { !PHONE.matches(it) }?.let { throw ToolException("“$it” isn't a phone number.") }
            val emails = split(args.str("emails"))
            emails.firstOrNull { !EMAIL.matches(it) }?.let { throw ToolException("“$it” isn't an email address.") }
            if (phones.size > 3 || emails.size > 3) throw ToolException("At most 3 phone numbers and 3 email addresses.")
            return NewContact(name.take(200), phones, emails, args.str("company")?.trim()?.takeIf { it.isNotEmpty() }, args.str("note")?.trim()?.takeIf { it.isNotEmpty() }?.take(2000))
        }

        private fun split(text: String?) = text.orEmpty().split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
}

internal class SaveFile(private val phone: PhoneIntegrations, private val workspace: Workspace) : Tool {
    // The file leaves Locus's sandbox, but only to where the user picks in the system picker, which is the confirmation.
    override val risk = ToolRisk.WRITE
    override val defaultPolicy = ToolPolicy.ALLOW
    override val runsAlone = true

    override val spec = ToolSpec(
        DeviceIntegrations.SAVE_FILE,
        "Save a workspace file to the phone's storage (Downloads, Documents, a cloud drive…): the system \"Save as\" " +
            "picker opens and the user picks the folder and name. It never overwrites a file.",
        ObjectSchema()
            .string("path", "The workspace file to save.")
            .string("name", "Suggested file name; default: the file's own.", required = false)
            .build(),
    )

    override fun describe(args: JsonObject) = "Save ${args.str("path")} to your phone's storage"

    override suspend fun precheck(args: JsonObject): String? = try {
        file(args)
        null
    } catch (e: ToolException) {
        e.message
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val file = file(args)
        val name = args.str("name")?.trim()?.takeIf { it.isNotEmpty() }?.let(::safeName) ?: file.name
        return when (val r = phone.saveAs(file, name, mimeOf(name))) {
            is UserPick.Picked -> ToolResult("Saved as “${r.value}” where the user chose.")
            UserPick.Cancelled -> ToolResult("The user closed the Save picker without saving.")
            UserPick.NotOnScreen -> ToolResult.error(NOT_ON_SCREEN)
        }
    }

    private fun file(args: JsonObject): File {
        val path = args.str("path")?.trim()?.takeIf { it.isNotEmpty() } ?: throw ToolException("Give the workspace path of the file.")
        val file = try {
            workspace.resolve(path)
        } catch (e: Exception) {
            throw ToolException(e.message ?: "That path isn't in the workspace.")
        }
        if (!file.isFile) throw ToolException("$path isn't a file in the workspace.")
        if (file.length() > MAX_BYTES) throw ToolException("$path is over ${MAX_BYTES / 1_000_000} MB.")
        return file
    }

    companion object {
        const val MAX_BYTES = 500_000_000L

        /** A name the picker can suggest: no folders, no control characters. */
        internal fun safeName(name: String): String =
            name.substringAfterLast('/').substringAfterLast('\\').filter { it >= ' ' }.trim().take(120).ifEmpty { "file" }

        internal fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"
            "txt", "log" -> "text/plain"
            "md" -> "text/markdown"
            "csv" -> "text/csv"
            "html", "htm" -> "text/html"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            "zip" -> "application/zip"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            else -> "application/octet-stream"
        }
    }
}

internal class Copy(private val phone: PhoneIntegrations, private val scrubber: suspend () -> SecretScrubber) : Tool {
    // Any app can paste what's copied, so it counts as outward: in a run that read outside content, taint asks.
    override val risk = ToolRisk.WRITE
    override val defaultPolicy = ToolPolicy.ALLOW

    override val spec = ToolSpec(
        DeviceIntegrations.COPY,
        "Copy text to the phone's clipboard for the user to paste elsewhere. Secrets from Locus's vault and credential-like " +
            "tokens are removed first. Locus can't read the clipboard.",
        ObjectSchema().string("text", "The text to copy (at most ${MAX_CHARS / 1000}k characters).").build(),
    )

    override fun describe(args: JsonObject) = "Copy to the clipboard: “${args.str("text").orEmpty().take(60).replace('\n', ' ')}”"

    override suspend fun execute(args: JsonObject): ToolResult {
        val text = args.str("text") ?: throw ToolException("Give the text to copy.")
        if (text.isBlank()) throw ToolException("Give the text to copy.")
        if (text.length > MAX_CHARS) throw ToolException("At most $MAX_CHARS characters.")
        val clean = scrubber().scrub(text)
        if (!phone.copy(clean, "Locus")) return ToolResult.error("Couldn't copy to the clipboard.")
        val removed = clean != text
        return ToolResult(
            "Copied ${clean.length} characters to the clipboard (marked sensitive, so Android doesn't show it in its preview)." +
                if (removed) " Some of it looked like a secret or a saved credential and was replaced with ${SecretScrubber.SECRET}." else "",
        )
    }

    companion object {
        const val MAX_CHARS = 20_000
    }
}
