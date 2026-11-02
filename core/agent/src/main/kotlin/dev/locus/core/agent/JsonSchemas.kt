package dev.locus.core.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * JSON Schemas written by others (MCP servers): shortened for the model without losing what it needs to
 * call the tool right, and checked against arguments before a call goes out. Anthropic's tool-use
 * guidance and its "advanced tool use" tests (complex parameters from 72% to 90% with examples) show
 * the schema details matter; docs/design/tool-discovery.md has the sources.
 */
object JsonSchemas {
    /** Keys kept in a compact schema: the ones that say what a valid value is. Titles, `$schema`, `$id` and the like go. */
    private val KEPT = setOf(
        "type", "description", "properties", "required", "enum", "const", "items", "anyOf", "oneOf", "allOf", "default",
        "format", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "minLength", "maxLength", "minItems", "maxItems",
        "pattern", "additionalProperties", "\$ref", "\$defs", "definitions", "nullable", "examples",
    )

    /** Longest description kept on an argument (a tool's own description is shortened by the caller). */
    const val ARGUMENT_DESCRIPTION_CHARS = 300

    /** At most this many examples are kept for one value. */
    private const val EXAMPLES = 2

    /**
     * [schema] with only the keys that describe valid values, argument descriptions cut to
     * [ARGUMENT_DESCRIPTION_CHARS], and at most [EXAMPLES] examples, all the way down: nested objects,
     * array items, `anyOf`/`oneOf`/`allOf` branches and `$defs` keep their own structure.
     */
    fun compact(schema: JsonObject): JsonObject = JsonObject(
        buildMap {
            for ((key, value) in schema) {
                if (key !in KEPT) continue
                put(
                    key,
                    when (key) {
                        "description" -> (value as? JsonPrimitive)?.contentOrNull?.let { JsonPrimitive(shorten(it, ARGUMENT_DESCRIPTION_CHARS)) } ?: value
                        "properties", "\$defs", "definitions" -> (value as? JsonObject)?.let { props ->
                            JsonObject(props.mapValues { (_, p) -> (p as? JsonObject)?.let(::compact) ?: p })
                        } ?: value
                        "items", "additionalProperties" -> (value as? JsonObject)?.let(::compact) ?: value
                        "anyOf", "oneOf", "allOf" -> (value as? JsonArray)?.let { JsonArray(it.map { b -> (b as? JsonObject)?.let(::compact) ?: b }) } ?: value
                        "examples" -> (value as? JsonArray)?.let { JsonArray(it.take(EXAMPLES)) } ?: value
                        else -> value
                    },
                )
            }
        },
    )

    private fun shorten(text: String, max: Int): String {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        return if (clean.length <= max) clean else clean.take(max - 1).trimEnd() + "…"
    }

    /**
     * What's wrong with [value] for [schema], one line each, at most [MAX_PROBLEMS]; empty when it fits.
     * Checks the common part of JSON Schema: types (one or a list), required and unknown properties
     * (`additionalProperties: false`), enums and consts, nested objects, array items, and `anyOf`/`oneOf`
     * (fits at least one branch). Anything it doesn't understand counts as fitting: the server decides.
     */
    fun problems(schema: JsonObject, value: JsonElement): List<String> {
        val out = mutableListOf<String>()
        check(schema, value, "arguments", schema, out, depth = 0)
        return out.take(MAX_PROBLEMS)
    }

    const val MAX_PROBLEMS = 6
    private const val MAX_DEPTH = 12
    private const val MAX_ITEMS_CHECKED = 50

    private fun check(schema: JsonObject, value: JsonElement, path: String, root: JsonObject, out: MutableList<String>, depth: Int) {
        if (depth > MAX_DEPTH || out.size >= MAX_PROBLEMS) return
        val resolved = resolveRef(schema, root) ?: return
        val types = when (val t = resolved["type"]) {
            is JsonPrimitive -> listOfNotNull(t.contentOrNull)
            is JsonArray -> t.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            else -> emptyList()
        }
        val nullable = (resolved["nullable"] as? JsonPrimitive)?.booleanOrNull == true
        if (value is JsonNull && (nullable || "null" in types || types.isEmpty())) return
        if (types.isNotEmpty() && types.none { matches(it, value) }) {
            out += "$path must be ${types.joinToString(" or ")}, got ${kind(value)}"
            return
        }
        (resolved["enum"] as? JsonArray)?.let { allowed ->
            if (allowed.isNotEmpty() && allowed.none { same(it, value) }) {
                out += "$path must be one of ${allowed.joinToString(", ") { it.toString() }.take(300)}, got $value"
                return
            }
        }
        resolved["const"]?.let { c -> if (!same(c, value)) { out += "$path must be $c, got $value"; return } }
        for (key in listOf("anyOf", "oneOf")) {
            val branches = (resolved[key] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: continue
            if (branches.isNotEmpty() && branches.none { b -> mutableListOf<String>().also { check(b, value, path, root, it, depth + 1) }.isEmpty() }) {
                out += "$path doesn't match any of the allowed forms"
                return
            }
        }
        (resolved["allOf"] as? JsonArray)?.forEach { b -> (b as? JsonObject)?.let { check(it, value, path, root, out, depth + 1) } }
        when (value) {
            is JsonObject -> {
                val props = resolved["properties"] as? JsonObject ?: JsonObject(emptyMap())
                (resolved["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?.filter { value[it] == null || value[it] is JsonNull && !allowsNull(props[it] as? JsonObject, root) }
                    ?.forEach { out += "$path.$it is required" }
                val extra = resolved["additionalProperties"]
                for ((key, v) in value) {
                    val declared = props[key] as? JsonObject
                    when {
                        declared != null -> check(declared, v, "$path.$key", root, out, depth + 1)
                        extra is JsonPrimitive && extra.booleanOrNull == false ->
                            out += "$path.$key isn't an argument this tool takes" + (if (props.isNotEmpty()) " (it takes: ${props.keys.joinToString()})" else "")
                        extra is JsonObject -> check(extra, v, "$path.$key", root, out, depth + 1)
                    }
                }
            }
            is JsonArray -> (resolved["items"] as? JsonObject)?.let { items ->
                value.take(MAX_ITEMS_CHECKED).forEachIndexed { i, item -> check(items, item, "$path[$i]", root, out, depth + 1) }
            }
            else -> Unit
        }
    }

    private fun allowsNull(schema: JsonObject?, root: JsonObject): Boolean {
        val s = schema?.let { resolveRef(it, root) } ?: return true
        if ((s["nullable"] as? JsonPrimitive)?.booleanOrNull == true) return true
        return when (val t = s["type"]) {
            is JsonPrimitive -> t.contentOrNull == "null"
            is JsonArray -> t.any { (it as? JsonPrimitive)?.contentOrNull == "null" }
            else -> true
        }
    }

    /** [schema], or what its local `$ref` (`#/$defs/x`, `#/definitions/x`) points to; null for a ref that can't be followed. */
    private fun resolveRef(schema: JsonObject, root: JsonObject): JsonObject? {
        val ref = (schema["\$ref"] as? JsonPrimitive)?.contentOrNull ?: return schema
        if (!ref.startsWith("#/")) return null
        var node: JsonElement = root
        for (part in ref.removePrefix("#/").split('/')) node = (node as? JsonObject)?.get(part.replace("~1", "/").replace("~0", "~")) ?: return null
        return node as? JsonObject
    }

    private fun matches(type: String, value: JsonElement): Boolean {
        val p = value as? JsonPrimitive
        return when (type) {
            "string" -> p != null && p !is JsonNull && p.isString
            "integer" -> p != null && p !is JsonNull && !p.isString && (p.longOrNull != null || p.doubleOrNull?.let { it % 1.0 == 0.0 } == true)
            "number" -> p != null && p !is JsonNull && !p.isString && p.doubleOrNull != null
            "boolean" -> p != null && p !is JsonNull && !p.isString && p.booleanOrNull != null
            "object" -> value is JsonObject
            "array" -> value is JsonArray
            "null" -> value is JsonNull
            else -> true
        }
    }

    private fun kind(value: JsonElement): String = when {
        value is JsonNull -> "null"
        value is JsonObject -> "an object"
        value is JsonArray -> "an array"
        (value as JsonPrimitive).isString -> "a string"
        value.booleanOrNull != null -> "a boolean"
        else -> "a number"
    }

    /** Equal as JSON values: 1 and 1.0 are the same number. */
    private fun same(a: JsonElement, b: JsonElement): Boolean {
        if (a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString) {
            val x = a.doubleOrNull
            val y = b.doubleOrNull
            if (x != null && y != null) return x == y
        }
        return a == b
    }
}
