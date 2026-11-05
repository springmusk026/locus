package dev.locus.core.data.tasks.triggers

import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.integrations.Integrations
import dev.locus.core.model.TaskTrigger
import dev.locus.core.model.TriggerHealth
import dev.locus.core.model.TriggerSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.io.IOException
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The polled sources against a mock ntfy server and a fake calendar. The same ntfy client also runs
 * against a real server: `docker run -p 8080:80 binwiederhier/ntfy serve` and a topic at
 * http://localhost:8080/… (docs/design/event-triggers.md, "Testing").
 */
class EventSourcesTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun ntfy(reachMe: String? = null, token: String? = null, answer: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        NtfyEvents(
            HttpClient(MockEngine { request -> requests += request; answer(request) }) { expectSuccess = false },
            { token },
            SecretResolver { name -> if (name == Integrations.ntfy.secret) reachMe else null },
        )

    private val topic = TaskTrigger(TriggerSource.NTFY, topicUrl = "https://ntfy.example/backups_4f9a")

    private val stream = listOf(
        """{"id":"open1","time":1762300000,"event":"open","topic":"backups_4f9a"}""",
        """{"id":"Ab12","time":1762300001,"event":"message","topic":"backups_4f9a","title":"Backup failed","message":"homelab: disk full","priority":5,"tags":["warning"]}""",
        """{"id":"keep1","time":1762300002,"event":"keepalive","topic":"backups_4f9a"}""",
        "not json",
        """{"id":"Cd34","time":1762300003,"event":"message","topic":"backups_4f9a","message":"all good"}""",
    ).joinToString("\n")

    @Test
    fun `ntfy - polls since the cursor, with the token, and keeps only messages`() = runTest {
        val source = ntfy(token = "tk_abc") { respond(stream, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/x-ndjson")) }
        val poll = assertIs<Poll.Events>(source.poll("t", topic, TriggerState(cursor = "1762299999"), now = 0))
        val request = requests.single()
        assertEquals("https://ntfy.example/backups_4f9a/json", request.url.toString().substringBefore('?'))
        assertEquals("1", request.url.parameters["poll"])
        assertEquals("1762299999", request.url.parameters["since"])
        assertEquals("Bearer tk_abc", request.headers[HttpHeaders.Authorization])
        assertEquals(listOf("Ab12", "Cd34"), poll.events.map { it.key })
        assertEquals("Cd34", poll.state.cursor)
        val first = poll.events.first()
        assertEquals(5, first.priority)
        assertEquals("Backup failed", first.title)
        assertTrue("Message: homelab: disk full" in first.details && "Tags: warning" in first.details, first.details)
        // A message without a title uses its first line; one without priority gets ntfy's default.
        assertEquals("all good", poll.events.last().title)
        assertEquals(3, poll.events.last().priority)
    }

    @Test
    fun `ntfy - nothing new keeps the cursor, and a first check starts from now`() = runTest {
        val source = ntfy { respond("", HttpStatusCode.OK) }
        val poll = assertIs<Poll.Events>(source.poll("t", topic, TriggerState(), now = 1_762_300_000_000))
        assertEquals("1762300000", requests.single().url.parameters["since"])
        assertEquals("1762300000", poll.state.cursor)
        assertTrue(poll.events.isEmpty())
    }

    @Test
    fun `ntfy - refused, missing, rate-limited and offline say so`() = runTest {
        assertEquals(TriggerHealth.ERROR, assertIs<Poll.Problem>(ntfy { respond("", HttpStatusCode.Unauthorized) }.poll("t", topic, TriggerState(), 0)).health)
        assertEquals(TriggerHealth.ERROR, assertIs<Poll.Problem>(ntfy { respond("", HttpStatusCode.NotFound) }.poll("t", topic, TriggerState(), 0)).health)
        assertEquals(TriggerHealth.OFFLINE, assertIs<Poll.Problem>(ntfy { respond("", HttpStatusCode.TooManyRequests) }.poll("t", topic, TriggerState(), 0)).health)
        val offline = assertIs<Poll.Problem>(ntfy { throw IOException("Unable to resolve host") }.poll("t", topic, TriggerState(), 0))
        assertEquals(TriggerHealth.OFFLINE, offline.health)
        assertTrue("ntfy.example" in offline.message, offline.message)
    }

    @Test
    fun `ntfy - the topic results go to is refused, so a result can't start the task again`() = runTest {
        val source = ntfy(reachMe = "https://ntfy.example/backups_4f9a tk_x") { error("no request expected") }
        val problem = assertIs<Poll.Problem>(source.poll("t", topic, TriggerState(), 0))
        assertEquals(TriggerHealth.ERROR, problem.health)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `ntfy - ids that aren't ntfy's are never used as a cursor`() {
        val events = NtfyEvents.parse("""{"id":"../../x?y","event":"message","message":"m"}""")
        assertTrue(events.isEmpty())
    }

    private class FakeCalendar(var allowed: Boolean = true, val items: List<CalendarInstance> = emptyList()) : CalendarReader {
        var asked: Pair<Long, Long>? = null
        override fun hasPermission() = allowed
        override fun instances(from: Long, to: Long): List<CalendarInstance> {
            asked = from to to
            return items.filter { it.end >= from && it.begin <= to }
        }
        override fun calendars() = listOf("Work")
    }

    private val minute = 60_000L
    private val now = 1_762_300_000_000L

    private fun entry(id: Long, beginsIn: Long, title: String = "Standup", allDay: Boolean = false, calendar: String = "Work") =
        CalendarInstance(id, now + beginsIn * minute, now + (beginsIn + 30) * minute, title, "Room 4", "Office", calendar, allDay)

    @Test
    fun `calendar - due entries fire once, later ones ask for a check at their time`() = runTest {
        val cal = FakeCalendar(items = listOf(entry(1, 5), entry(2, 25), entry(3, 5, allDay = true), entry(4, 5, title = "Lunch")))
        val trigger = TaskTrigger(TriggerSource.CALENDAR, contains = "standup", minutesBefore = 10)
        val poll = assertIs<Poll.Events>(CalendarEvents(cal).poll("t", trigger, TriggerState(), now))
        // Entry 1 begins in 5 min, inside the 10-minute warning; 2 is due in 15 min; 3 is all-day; 4 doesn't match.
        assertEquals(listOf("1:${now + 5 * minute}"), poll.events.map { it.key })
        assertEquals(now + 15 * minute, poll.checkAgainAt)
        assertTrue("Starts:" in poll.events.single().details && "(in 5 min)" in poll.events.single().details, poll.events.single().details)
        // The next check doesn't fire it again.
        val again = assertIs<Poll.Events>(CalendarEvents(cal).poll("t", trigger, poll.state, now + minute))
        assertTrue(again.events.isEmpty())
    }

    @Test
    fun `calendar - an entry long started is left alone, and no access says so`() = runTest {
        val late = FakeCalendar(items = listOf(entry(1, -45)))
        assertTrue(assertIs<Poll.Events>(CalendarEvents(late).poll("t", TaskTrigger(TriggerSource.CALENDAR), TriggerState(), now)).events.isEmpty())
        val denied = assertIs<Poll.Problem>(CalendarEvents(FakeCalendar(allowed = false)).poll("t", TaskTrigger(TriggerSource.CALENDAR), TriggerState(), now))
        assertEquals(TriggerHealth.NO_PERMISSION, denied.health)
    }

    @Test
    fun `calendar - old keys are dropped so the state stays small`() = runTest {
        val old = "9:${now - 3 * 24 * 60 * minute}"
        val poll = assertIs<Poll.Events>(CalendarEvents(FakeCalendar()).poll("t", TaskTrigger(TriggerSource.CALENDAR), TriggerState(handled = listOf(old, "8:${now}")), now))
        assertEquals(listOf("8:$now"), poll.state.handled)
        assertNull(poll.checkAgainAt)
    }
}
