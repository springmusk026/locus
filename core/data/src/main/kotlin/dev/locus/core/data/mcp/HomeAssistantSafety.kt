package dev.locus.core.data.mcp

import dev.locus.core.agent.home.Connector
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Which calls to a Home Assistant MCP server always need a tap in Locus (docs/design/integrations.md,
 * "Home Assistant"). Every HA tool that isn't read-only already asks ([McpToolRisk]); this names the
 * physical-security ones, so the card says what's at stake, and makes sure they ask even if the server
 * marked a tool read-only. A forced confirmation is never remembered and never answered by voice or
 * Telegram (docs/design/remembered-approvals.md, "Never remembered").
 *
 * Calls are judged by their arguments, not tool names alone, because the same `HassTurnOn` lights a
 * lamp or locks a door. The tools and arguments are Home Assistant's own (2026.11):
 * - Assist intents, `intent__HassTurnOn` / `HassTurnOff` / `HassSetPosition` with slots `name`, `area`,
 *   `floor`, `domain` and `device_class` (homeassistant/components/intent/llm.py; older servers send
 *   the bare intent name);
 * - `climate__HassClimateSetTemperature` with `temperature` (components/climate/intent.py);
 * - exposed scripts as `script__<name>` (components/script/llm.py);
 * - with full access, `homeassistant__call_action` with `action` (`lock.unlock`), `target` (`entity_id`…)
 *   and `data` (components/homeassistant/llm.py).
 *
 * Home Assistant has no "security" device class, so "security" is matched as a word in names, areas,
 * entity ids and scripts; the `siren` domain counts too.
 */
object HomeAssistantSafety {
    /** Domains whose every change is a physical-security action. */
    val SECURITY_DOMAINS = setOf("lock", "alarm_control_panel", "siren")

    /** Cover device classes that open a way into the home. */
    val DOOR_CLASSES = setOf("garage", "door", "gate")

    /** Words that mark a device, area or script as part of the home's security, whatever its domain. */
    val SECURITY_WORDS = setOf("lock", "locks", "unlock", "alarm", "garage", "gate", "door", "doors", "security", "siren", "disarm", "arm")

    /** Domains whose setpoint is checked against the user's range. */
    private val SETPOINT_DOMAINS = setOf("climate", "water_heater")

    /** The user-facing list of what always confirms, for the setup sheet and Library. */
    fun builtInSummary(rules: Connector.HomeAssistantRules): String =
        "Locks, alarm panels, sirens, garage doors, doors and gates, anything named “security”, and heating or cooling set " +
            "outside ${degrees(rules.climateMin)}–${degrees(rules.climateMax)}°."

    /** Whether [connector] is a Home Assistant server this applies to. */
    fun applies(connector: Connector): Boolean =
        connector.kind == "mcp" && (connector.template == TEMPLATE_ID || connector.homeAssistant != null)

    const val TEMPLATE_ID = "home-assistant"

    /**
     * Why calling [tool] with [args] always needs a tap, or null when it's an ordinary control (which
     * still asks if the tool isn't read-only).
     */
    fun reason(rules: Connector.HomeAssistantRules, tool: String, args: JsonObject): String? {
        val call = Call.of(tool, args)
        val what = call.label()
        call.domains.firstOrNull { it in SECURITY_DOMAINS }?.let { d ->
            return physical("${verb(d, call)}$what")
        }
        if ("cover" in call.domains && call.classes.any { it in DOOR_CLASSES }) return physical("opens or closes$what (a garage door, door or gate)")
        call.words.firstOrNull { it in SECURITY_WORDS }?.let { return physical("controls$what, which looks like part of your home's security (“$it”)") }
        call.setpoints.firstOrNull { it < rules.climateMin || it > rules.climateMax }?.let { t ->
            return "Sets the temperature to ${degrees(t)}°$what, outside the ${degrees(rules.climateMin)}–${degrees(rules.climateMax)}° range you allow without a tap."
        }
        rules.alwaysConfirm.firstOrNull { call.matches(it) }?.let { entry ->
            return "You asked Locus to always confirm “${entry.trim()}”, and this ${call.action}$what."
        }
        return null
    }

    /** One line for an ordinary control call's card: what it does, to what. */
    fun describe(tool: String, args: JsonObject): String {
        val call = Call.of(tool, args)
        val what = call.label().ifEmpty { if (call.action.endsWith(" on") || call.action.endsWith(" of")) " something" else "" }
        return "This changes something in your home: it ${call.action}$what."
    }

    private fun physical(detail: String) =
        "Physical security: this $detail. Locus always asks before these, even if you've allowed this server."

    private fun verb(domain: String, call: Call): String = when (domain) {
        "lock" -> when {
            call.base.equals("HassTurnOff", true) || call.rawAction.lowercase().endsWith(".unlock") -> "unlocks"
            call.base.equals("HassTurnOn", true) || call.rawAction.lowercase().endsWith(".lock") -> "locks"
            else -> "controls a lock:"
        }
        "alarm_control_panel" -> "arms or disarms an alarm"
        else -> "sounds or silences a siren"
    }

    private fun degrees(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

    /** What a call names: its domains, device classes, words, setpoints and targets. */
    private class Call(
        val base: String,
        val action: String,
        /** The `action` argument as sent (`lock.unlock`), or empty. */
        val rawAction: String,
        val domains: Set<String>,
        val classes: Set<String>,
        val targets: List<String>,
        val words: Set<String>,
        val setpoints: List<Double>,
    ) {
        fun label(): String = targets.firstOrNull()?.let { " “$it”" }.orEmpty()

        /** A user entry: an entity id or action (has a dot), a domain, or a name or phrase. */
        fun matches(entry: String): Boolean {
            val e = entry.trim().lowercase()
            if (e.isEmpty()) return false
            if ('.' in e) return targets.any { it.lowercase() == e } || rawAction.lowercase() == e
            if (Regex("^[a-z0-9_]+$").matches(e) && e in domains) return true
            val phrase = words(e)
            if (phrase.isEmpty()) return false
            return targets.any { t -> words(t).windowed(phrase.size).any { it == phrase } }
        }

        companion object {
            fun of(tool: String, args: JsonObject): Call {
                // "intent__HassTurnOn" → namespace "intent", base "HassTurnOn"; "script__open_garage" → "script", "open_garage".
                val namespace = tool.substringBefore("__", "").lowercase()
                val base = tool.substringAfter("__")
                val action = strings(args["action"]).firstOrNull().orEmpty()
                val target = args["target"] as? JsonObject
                val data = args["data"] as? JsonObject
                val entityIds = strings(target?.get("entity_id")) + strings(data?.get("entity_id")) + strings(args["entity_id"])
                val names = strings(args["name"]) + strings(args["area"]) + strings(args["floor"]) +
                    strings(target?.get("area_id")) + strings(target?.get("device_id")) + strings(target?.get("label_id")) + strings(target?.get("floor_id"))
                val domains = buildSet {
                    strings(args["domain"]).forEach { add(it.lowercase()) }
                    if ('.' in action) add(action.substringBefore('.').lowercase())
                    entityIds.forEach { if ('.' in it) add(it.substringBefore('.').lowercase()) }
                    names.forEach { if (Regex("^[a-z_]+\\.[a-z0-9_]+$").matches(it)) add(it.substringBefore('.')) }
                    if (namespace in SECURITY_DOMAINS || namespace in SETPOINT_DOMAINS || namespace == "cover") add(namespace)
                    if (base.contains("Climate", ignoreCase = true)) add("climate")
                }
                val classes = (strings(args["device_class"]) + strings(data?.get("device_class"))).map { it.lowercase() }.toSet()
                val targets = (entityIds + names).filter { it.isNotBlank() }.distinct()
                val scriptName = if (namespace == "script") listOf(base) else emptyList()
                val words = (targets + scriptName + listOf(action)).flatMap(::words).toSet()
                val setpoints = if (domains.any { it in SETPOINT_DOMAINS } || base.contains("Temperature", ignoreCase = true)) {
                    listOf("temperature", "target_temp_high", "target_temp_low").flatMap { k -> listOfNotNull(number(args[k]), number(data?.get(k))) }
                } else {
                    emptyList()
                }
                val shown = when {
                    action.isNotEmpty() -> "calls $action on"
                    namespace == "script" -> "runs the script “$base”"
                    base.equals("HassTurnOn", true) -> "turns on"
                    base.equals("HassTurnOff", true) -> "turns off"
                    base.equals("HassSetPosition", true) -> "moves"
                    base.equals("HassStopMoving", true) -> "stops"
                    base.contains("Temperature", true) -> "sets the temperature of"
                    else -> "runs " + base.removePrefix("Hass").replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase() + " on"
                }
                return Call(base, shown, action, domains, classes, targets, words, setpoints)
            }

            fun words(text: String): List<String> =
                text.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

            private fun strings(e: JsonElement?): List<String> = when (e) {
                is JsonPrimitive -> listOfNotNull(e.contentOrNull?.takeIf { e.isString })
                is JsonArray -> e.flatMap(::strings)
                else -> emptyList()
            }

            private fun number(e: JsonElement?): Double? = (e as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
        }
    }
}
