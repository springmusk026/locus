package dev.locus.core.data.mcp

/** MCP tool and connector names as a person reads them on an approval card or in Activity. */
object McpNames {
    /**
     * `intent__HassTurnOff` → "Turn off", `create_issue` → "Create issue", `GetLiveContext` → "Get live
     * context", `front_door` → "Front door". A namespace before `__` is dropped, as is Home Assistant's
     * `Hass` prefix on its intents. The raw name stays in the call's details.
     */
    fun humanize(name: String): String {
        val bare = name.substringAfterLast("__").let { if (Regex("^Hass[A-Z]").containsMatchIn(it)) it.removePrefix("Hass") else it }
        val words = bare
            .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
            .split(Regex("[\\s_.\\-/]+"))
            .filter { it.isNotEmpty() }
        if (words.isEmpty()) return name
        return words.joinToString(" ") { w -> if (w.length > 1 && w.all { it.isUpperCase() || it.isDigit() }) w else w.lowercase() }
            .replaceFirstChar { it.uppercase() }
    }
}
