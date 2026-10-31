package dev.locus.core.agent.integrations

import dev.locus.core.agent.ObjectSchema
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.int
import dev.locus.core.agent.str
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** Built-in integrations that need no key: Open-Meteo weather and Wikipedia. */
fun openDataTools(http: HttpClient): List<Tool> = listOf(Weather(http), Wikipedia(http))

private val json = Json { ignoreUnknownKeys = true }
private const val USER_AGENT = "Locus/0.1 (open-source personal agent)"

private suspend fun HttpClient.getJson(url: String, vararg params: Pair<String, Any>): JsonObject {
    val response = get(url) {
        header("User-Agent", USER_AGENT)
        params.forEach { (k, v) -> parameter(k, v) }
    }
    if (response.status.value !in 200..299) throw ToolException("Service returned HTTP ${response.status.value}.")
    return json.parseToJsonElement(response.bodyAsText()) as JsonObject
}

private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.d(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.arr(k: String) = (this[k] as? JsonArray).orEmpty()
private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

/** WMO weather interpretation codes, as listed in the Open-Meteo API docs. */
internal fun wmo(code: Int?): String = when (code) {
    0 -> "clear sky"
    1 -> "mainly clear"
    2 -> "partly cloudy"
    3 -> "overcast"
    45, 48 -> "fog"
    51, 53, 55 -> "drizzle"
    56, 57 -> "freezing drizzle"
    61 -> "light rain"
    63 -> "rain"
    65 -> "heavy rain"
    66, 67 -> "freezing rain"
    71, 73, 75 -> "snow"
    77 -> "snow grains"
    80, 81, 82 -> "rain showers"
    85, 86 -> "snow showers"
    95 -> "thunderstorm"
    96, 99 -> "thunderstorm with hail"
    else -> "unknown"
}

private class Weather(private val http: HttpClient) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "weather",
        "Current weather and the next days' forecast for a place (Open-Meteo, no key needed).",
        ObjectSchema()
            .string("place", "City or place name, e.g. \"Pokhara\".")
            .integer("days", "Forecast days, 1–7. Defaults to 3.")
            .build(),
    )

    override fun describe(args: JsonObject) = "Weather in ${args.str("place")}"
    override suspend fun isObservation(args: JsonObject) = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val place = args.str("place").orEmpty()
        val hit = http.getJson("https://geocoding-api.open-meteo.com/v1/search", "name" to place, "count" to 1)
            .arr("results").firstOrNull() as? JsonObject ?: throw ToolException("Couldn't find a place called “$place”.")
        val days = (args.int("days") ?: 3).coerceIn(1, 7)
        val f = http.getJson(
            "https://api.open-meteo.com/v1/forecast",
            "latitude" to hit.d("latitude")!!, "longitude" to hit.d("longitude")!!,
            "current" to "temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m",
            "daily" to "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max",
            "timezone" to "auto", "forecast_days" to days,
        )
        val now = f["current"] as? JsonObject
        val daily = f["daily"] as? JsonObject
        val text = buildString {
            append("${hit.s("name")}, ${hit.s("admin1") ?: ""} ${hit.s("country") ?: ""}".replace(Regex("\\s+"), " ").trim()).append('\n')
            now?.let {
                append("Now (${it.s("time")}): ${it.d("temperature_2m")}°C, feels like ${it.d("apparent_temperature")}°C, ${wmo(it.d("weather_code")?.toInt())}, ")
                append("wind ${it.d("wind_speed_10m")} km/h, humidity ${it.d("relative_humidity_2m")?.toInt()}%\n")
            }
            val dates = daily?.arr("time").orEmpty()
            dates.indices.forEach { i ->
                fun col(k: String) = (daily?.arr(k)?.getOrNull(i) as? JsonPrimitive)
                append("${(dates[i] as JsonPrimitive).content}: ${wmo(col("weather_code")?.doubleOrNull?.toInt())}, ")
                append("${col("temperature_2m_min")?.content}–${col("temperature_2m_max")?.content}°C, rain chance ${col("precipitation_probability_max")?.content ?: "?"}%\n")
            }
        }
        return ToolResult(text.trim())
    }
}

private class Wikipedia(private val http: HttpClient) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "wikipedia",
        "Look something up on Wikipedia: returns the best matching article's summary and link, plus other matches.",
        ObjectSchema()
            .string("query", "What to look up.")
            .string("language", "Wikipedia language code, e.g. en, ne, de. Defaults to en.", required = false)
            .build(),
    )

    override fun describe(args: JsonObject) = "Wikipedia: ${args.str("query")}"
    override suspend fun isObservation(args: JsonObject) = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val lang = (args.str("language") ?: "en").lowercase().filter { it.isLetter() || it == '-' }.ifEmpty { "en" }
        val search = http.getJson(
            "https://$lang.wikipedia.org/w/api.php",
            "action" to "query", "list" to "search", "srsearch" to args.str("query").orEmpty(), "format" to "json", "srlimit" to 5,
        )
        val hits = ((search["query"] as? JsonObject)?.arr("search")).orEmpty().mapNotNull { (it as? JsonObject)?.s("title") }
        val top = hits.firstOrNull() ?: return ToolResult("No Wikipedia article found.")
        val summary = http.getJson("https://$lang.wikipedia.org/api/rest_v1/page/summary/${java.net.URLEncoder.encode(top.replace(' ', '_'), "UTF-8")}")
        val link = ((summary["content_urls"] as? JsonObject)?.get("desktop") as? JsonObject)?.s("page")
        val others = hits.drop(1).takeIf { it.isNotEmpty() }?.joinToString(prefix = "\nOther matches: ").orEmpty()
        return ToolResult("# ${summary.s("title") ?: top}\n${summary.s("extract").orEmpty()}\n${link.orEmpty()}$others")
    }
}
