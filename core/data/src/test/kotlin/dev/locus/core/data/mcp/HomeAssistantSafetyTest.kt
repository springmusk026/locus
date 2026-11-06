package dev.locus.core.data.mcp

import dev.locus.core.agent.home.Connector
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which Home Assistant calls always need a tap. Tool names and arguments are Home Assistant's own
 * (2026.11.0: components/intent/llm.py, climate/intent.py, script/llm.py, homeassistant/llm.py).
 */
class HomeAssistantSafetyTest {
    private val rules = Connector.HomeAssistantRules()

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun always(tool: String, json: String, r: Connector.HomeAssistantRules = rules) =
        assertNotNull(HomeAssistantSafety.reason(r, tool, args(json)), "$tool $json should always ask")

    private fun ordinary(tool: String, json: String, r: Connector.HomeAssistantRules = rules) =
        assertNull(HomeAssistantSafety.reason(r, tool, args(json)), "$tool $json")

    @Test
    fun `locks and alarms always ask, whichever way the call names them`() {
        val unlock = always("intent__HassTurnOff", """{"name":"Front door","domain":["lock"]}""")
        assertTrue("unlocks" in unlock && "Front door" in unlock && "Physical security" in unlock, unlock)
        assertTrue("locks" in always("HassTurnOn", """{"name":"Back door","domain":"lock"}"""))
        always("homeassistant__call_action", """{"action":"lock.unlock","target":{"entity_id":"lock.front_door"}}""")
        always("homeassistant__call_action", """{"action":"homeassistant.turn_off","target":{"entity_id":["light.hall","lock.side_gate"]}}""")
        always("homeassistant__call_action", """{"action":"alarm_control_panel.alarm_disarm","target":{"entity_id":"alarm_control_panel.home"},"data":{"code":"1234"}}""")
        always("intent__HassTurnOn", """{"domain":["siren"]}""")
    }

    @Test
    fun `garage doors, doors and gates always ask, but blinds don't`() {
        always("intent__HassTurnOn", """{"domain":["cover"],"device_class":["garage"]}""")
        always("intent__HassSetPosition", """{"name":"Garage","position":100}""")
        always("intent__HassTurnOn", """{"name":"Driveway gate","domain":"cover"}""")
        always("homeassistant__call_action", """{"action":"cover.open_cover","target":{"entity_id":"cover.garage_door"}}""")
        ordinary("intent__HassTurnOn", """{"domain":["cover"],"device_class":["blind"],"area":"Bedroom"}""")
        ordinary("intent__HassSetPosition", """{"name":"Living room blinds","position":40}""")
    }

    @Test
    fun `anything named security, and scripts that sound like it, always ask`() {
        always("intent__HassTurnOff", """{"area":"Security"}""")
        always("intent__HassTurnOff", """{"name":"Security cameras","domain":"switch"}""")
        always("script__open_garage", """{}""")
        always("script__disarm_alarm_night", """{}""")
        ordinary("script__good_morning", """{}""")
    }

    @Test
    fun `climate setpoints outside the range always ask, inside it they're ordinary`() {
        ordinary("climate__HassClimateSetTemperature", """{"temperature":21,"area":"Living room"}""")
        val hot = always("climate__HassClimateSetTemperature", """{"temperature":30,"area":"Living room"}""")
        assertTrue("30" in hot && "16–26" in hot, hot)
        always("climate__HassClimateSetTemperature", """{"temperature":"12.5"}""")
        always("homeassistant__call_action", """{"action":"climate.set_temperature","target":{"entity_id":"climate.hall"},"data":{"temperature":29}}""")
        always("homeassistant__call_action", """{"action":"water_heater.set_temperature","data":{"temperature":70}}""")
        // The user's range: 18–22.
        val narrow = Connector.HomeAssistantRules(climateMin = 18.0, climateMax = 22.0)
        always("climate__HassClimateSetTemperature", """{"temperature":23}""", narrow)
        ordinary("climate__HassClimateSetTemperature", """{"temperature":20}""", narrow)
    }

    @Test
    fun `the user's additions match domains, entity ids, actions and names`() {
        val mine = Connector.HomeAssistantRules(alwaysConfirm = listOf("vacuum", "switch.space_heater", "script.turn_on", "Kids room"))
        always("intent__HassTurnOn", """{"domain":["vacuum"]}""", mine)
        always("homeassistant__call_action", """{"action":"switch.turn_on","target":{"entity_id":"switch.space_heater"}}""", mine)
        always("homeassistant__call_action", """{"action":"script.turn_on","target":{"entity_id":"script.party"}}""", mine)
        always("intent__HassTurnOff", """{"area":"Kids Room","domain":"light"}""", mine)
        ordinary("intent__HassTurnOff", """{"area":"Kitchen","domain":"light"}""", mine)
        ordinary("homeassistant__call_action", """{"action":"switch.turn_on","target":{"entity_id":"switch.space_heater_2"}}""")
    }

    @Test
    fun `ordinary controls and reads aren't flagged, and the card says what they do`() {
        ordinary("intent__HassTurnOn", """{"name":"Kitchen light","domain":["light"]}""")
        ordinary("homeassistant__GetLiveContext", """{}""")
        // "Outdoor" isn't "door": words are matched whole.
        ordinary("intent__HassTurnOff", """{"name":"Outdoor lights"}""")
        val line = HomeAssistantSafety.describe("intent__HassTurnOn", args("""{"name":"Kitchen light"}"""))
        assertTrue("turns on" in line && "Kitchen light" in line, line)
    }

    @Test
    fun `it applies to servers set up as Home Assistant only`() {
        val ha = Connector("home-assistant", kind = "mcp", name = "Home Assistant", description = "", baseUrl = "https://ha.example/api/mcp/assist", template = "home-assistant")
        assertTrue(HomeAssistantSafety.applies(ha))
        assertTrue(HomeAssistantSafety.applies(ha.copy(template = "", homeAssistant = Connector.HomeAssistantRules())))
        assertFalse(HomeAssistantSafety.applies(ha.copy(template = "")))
        assertFalse(HomeAssistantSafety.applies(ha.copy(kind = "http")))
    }

    @Test
    fun `the built-in list is named for the user`() {
        val summary = HomeAssistantSafety.builtInSummary(rules)
        listOf("Locks", "alarm", "garage", "gates", "security", "16–26").forEach { assertTrue(it in summary, it) }
    }
}
