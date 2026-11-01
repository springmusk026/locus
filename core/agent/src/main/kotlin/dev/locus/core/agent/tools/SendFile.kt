package dev.locus.core.agent.tools

import dev.locus.core.agent.ObjectSchema
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.str
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Hands a workspace file to the user: the chat shows each successful call as a file card (open,
 * share, save to the device). The tool itself only checks the file; nothing leaves the phone unless
 * the user taps Share or Save.
 */
class SendFile(private val ws: Workspace) : Tool {
    override val risk = ToolRisk.READ

    // Files reach the user in the order the agent sent them.
    override val runsAlone = true
    override val spec = ToolSpec(
        NAME,
        "Give the user a file from the workspace. It appears in the chat as a card they can open, share or save to their phone. " +
            "Use it whenever you produced a file the user asked for (a document, image, spreadsheet, archive, export…), " +
            "after the file is written, instead of only naming its path. One call per file; folders must be zipped first.",
        ObjectSchema()
            .string("path", "File path relative to the workspace root (a /workspace/ prefix from the Linux shell is fine).")
            .string("note", "Optional short caption shown on the card, e.g. what the file contains.", required = false)
            .build(),
    )

    override fun describe(args: JsonObject) = "Send ${args.str("path")} to you"

    override suspend fun isObservation(args: JsonObject) = true

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val raw = args.str("path") ?: throw ToolException("Missing 'path'.")
        val file = ws.existing(normalize(raw))
        if (file.isDirectory) throw ToolException("'${ws.relative(file)}' is a folder. Zip it first (for example with run_command), then send the archive.")
        if (!file.canRead()) throw ToolException("'${ws.relative(file)}' can't be read.")
        ToolResult("Sent ${ws.relative(file)} (${readableSize(file.length())}) to the user; it shows in the chat as a file card. No need to repeat its contents.")
    }

    companion object {
        const val NAME = "send_file"

        /** The path as [Workspace.resolve] expects it: the Linux shell sees the workspace at /workspace. */
        fun normalize(path: String): String {
            val p = path.trim()
            return when {
                p == "/workspace" || p == "~/workspace" -> "."
                p.startsWith("/workspace/") -> p.removePrefix("/workspace/")
                else -> p
            }
        }

        /** The `path` and `note` arguments of a call, for the chat's file card. */
        fun parse(argumentsJson: String): Pair<String, String?>? {
            val args = runCatching { Json.parseToJsonElement(argumentsJson) as JsonObject }.getOrNull() ?: return null
            fun opt(key: String) = (args[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            val path = opt("path") ?: return null
            return normalize(path) to opt("note")
        }

        fun readableSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(java.util.Locale.ROOT, bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024))
            else -> "%.2f GB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024 * 1024))
        }
    }
}

/** File hand-off tools; registered in ToolCatalog. */
fun deliveryTools(ws: Workspace): List<Tool> = listOf(SendFile(ws))
