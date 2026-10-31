package dev.locus.core.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Small builder for the object schemas tools declare. */
class ObjectSchema {
    private class Prop(val name: String, val type: String, val description: String, val required: Boolean)

    private val props = mutableListOf<Prop>()

    fun string(name: String, description: String, required: Boolean = true) = apply { props += Prop(name, "string", description, required) }
    fun integer(name: String, description: String, required: Boolean = false) = apply { props += Prop(name, "integer", description, required) }
    fun boolean(name: String, description: String, required: Boolean = false) = apply { props += Prop(name, "boolean", description, required) }
    fun obj(name: String, description: String, required: Boolean = false) = apply { props += Prop(name, "object", description, required) }

    fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            props.forEach { p ->
                putJsonObject(p.name) {
                    put("type", p.type)
                    put("description", p.description)
                }
            }
        }
        putJsonArray("required") { props.filter { it.required }.forEach { add(JsonPrimitive(it.name)) } }
        put("additionalProperties", false)
    }
}

/**
 * Checks arguments against a tool's declared object schema (required keys and primitive types).
 * Needed because streamed tool input is not validated by the provider. Returns an error message
 * for the model, or null when the arguments are acceptable.
 */
fun validateArguments(schema: JsonObject, args: JsonObject): String? {
    val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
    val required = (schema["required"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
    required.filter { args[it] == null || args[it] is JsonNull }.takeIf { it.isNotEmpty() }?.let {
        return "Missing required argument(s): ${it.joinToString()}"
    }
    for ((key, value) in args) {
        val declared = properties[key] as? JsonObject ?: return "Unknown argument '$key'"
        val type = declared["type"]?.jsonPrimitive?.contentOrNull ?: continue
        if (!matchesType(type, value)) return "Argument '$key' must be of type $type"
    }
    return null
}

private fun matchesType(type: String, value: JsonElement): Boolean {
    if (value is JsonNull) return true
    val primitive = value as? JsonPrimitive
    return when (type) {
        "string" -> primitive?.isString == true
        "integer" -> primitive != null && !primitive.isString && primitive.intOrNull != null
        "boolean" -> primitive != null && !primitive.isString && primitive.booleanOrNull != null
        "object" -> runCatching { value.jsonObject }.isSuccess
        "array" -> runCatching { value.jsonArray }.isSuccess
        else -> true
    }
}

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
