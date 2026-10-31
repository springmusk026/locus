package dev.locus.core.markdown

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** What the render page reported for one diagram. */
sealed interface MermaidOutcome {
    /** A PNG (base64) of a diagram whose natural size is [width] × [height] CSS pixels. */
    data class Drawn(val pngBase64: String, val width: Int, val height: Int) : MermaidOutcome

    /** Mermaid couldn't parse or draw it; the card falls back to the source. */
    data class Failed(val message: String) : MermaidOutcome
}

/**
 * Decodes what `locusTake(id)` returned through evaluateJavascript: the JSON encoding of a string that
 * is empty while the diagram is still drawing, or JSON describing the outcome. Null means not yet.
 */
fun parseMermaidTake(evaluated: String?): MermaidOutcome? {
    val inner = runCatching { (Json.parseToJsonElement(evaluated ?: return null) as? JsonPrimitive)?.contentOrNull }
        .getOrNull()
    if (inner.isNullOrEmpty() || inner == "null") return null
    val obj = runCatching { Json.parseToJsonElement(inner) as? JsonObject }.getOrNull()
        ?: return MermaidOutcome.Failed("Unreadable result")
    obj["error"]?.jsonPrimitive?.contentOrNull?.let { return MermaidOutcome.Failed(it) }
    val png = obj["png"]?.jsonPrimitive?.contentOrNull
    val width = obj["width"]?.jsonPrimitive?.intOrNull
    val height = obj["height"]?.jsonPrimitive?.intOrNull
    if (png.isNullOrEmpty() || width == null || height == null || width <= 0 || height <= 0) {
        return MermaidOutcome.Failed("Unreadable result")
    }
    return MermaidOutcome.Drawn(png, width, height)
}
