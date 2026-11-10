package dev.locus.core.model

/**
 * view_image: the agent shows the model images from the workspace (docs/design/view-image.md). The
 * images travel in [Part.ToolResult.images].
 */
object ViewImages {
    const val TOOL = "view_image"

    /** At most this many images per call. */
    const val MAX_IMAGES = 4

    /** The workspace paths a view_image call names (`path` and `paths`), in order, without repeats; empty for anything else. */
    fun paths(argumentsJson: String): List<String> {
        val args = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(argumentsJson) as? kotlinx.serialization.json.JsonObject }.getOrNull() ?: return emptyList()
        fun str(e: kotlinx.serialization.json.JsonElement?) = (e as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
        val many = (args["paths"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull(::str).orEmpty()
        return (listOfNotNull(str(args["path"])) + many).distinct()
    }
}
