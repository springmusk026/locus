package dev.locus.core.agent

import dev.locus.core.agent.integrations.openDataTools
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue

class OpenDataTest {
    @Test
    fun `weather geocodes then formats the forecast`() = runTest {
        val http = HttpClient(MockEngine { req ->
            if (req.url.host.startsWith("geocoding")) respond("""{"results":[{"name":"Kathmandu","latitude":27.7,"longitude":85.32,"country":"Nepal","admin1":"Bagmati Province"}]}""")
            else respond("""{"current":{"time":"2026-10-31T12:00","temperature_2m":20.1,"apparent_temperature":19.5,"weather_code":53,"wind_speed_10m":6.2,"relative_humidity_2m":70},
                "daily":{"time":["2026-10-31"],"weather_code":[61],"temperature_2m_max":[21.2],"temperature_2m_min":[12.0],"precipitation_probability_max":[80]}}""")
        })
        val r = ToolRegistry(openDataTools(http))["weather"]!!.execute(JsonObject(mapOf("place" to JsonPrimitive("Kathmandu"))))
        assertTrue("Kathmandu, Bagmati Province Nepal" in r.content, r.content)
        assertTrue("20.1°C" in r.content && "drizzle" in r.content && "light rain, 12.0–21.2°C, rain chance 80%" in r.content, r.content)
    }
}
