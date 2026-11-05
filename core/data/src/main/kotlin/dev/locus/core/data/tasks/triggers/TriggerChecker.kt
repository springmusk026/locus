package dev.locus.core.data.tasks.triggers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.data.tasks.TaskRepository
import dev.locus.core.datastore.SecretStore
import dev.locus.core.model.TaskTrigger
import dev.locus.core.model.TriggerHealth
import dev.locus.core.model.TriggerSource
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One check of a task's trigger source (docs/design/event-triggers.md): ask the source what's new since
 * its cursor, keep the new cursor and how the check went on the task, and hand what it found to the
 * [TriggerEngine]. A check that can't reach its source leaves the cursor alone, so nothing is lost; it
 * is tried again at the next check.
 */
class TriggerChecker(
    private val tasks: TaskRepository,
    private val sources: (TriggerSource) -> EventSource?,
) {
    internal var clock: () -> Long = System::currentTimeMillis

    /** One check per task at a time: the periodic check and an extra one could otherwise both use the same cursor. */
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun check(taskId: String) = locks.getOrPut(taskId) { Mutex() }.withLock {
        val task = tasks.get(taskId) ?: return@withLock
        val trigger = TaskTrigger.fromJson(task.triggerJson) ?: return@withLock
        if (!task.enabled) return@withLock
        val source = sources(trigger.source) ?: return@withLock
        val state = TriggerState.fromJson(task.triggerStateJson)
        val now = clock()
        val poll = try {
            source.poll(taskId, trigger, state, now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Poll.Problem(TriggerHealth.ERROR, "The check failed: ${e.message?.take(160) ?: e::class.java.simpleName}.")
        }
        when (poll) {
            is Poll.Problem -> tasks.setTriggerState(taskId, state.copy(health = poll.health, message = poll.message, checkedAt = now))
            is Poll.Events -> {
                // The cursor moves before the events are handled: a crash in between loses them rather than running them twice.
                tasks.setTriggerState(taskId, poll.state.copy(health = TriggerHealth.OK, message = null, checkedAt = now))
                if (poll.events.isNotEmpty()) tasks.triggers.deliver(taskId, poll.events)
                poll.checkAgainAt?.let { at -> tasks.checkSoon(taskId, at - now) }
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object TriggerModule {
    @Provides
    @Singleton
    fun calendarReader(@ApplicationContext context: Context): CalendarReader = AndroidCalendarReader(context)
}

/** [CalendarReader] on the phone's calendar provider. */
class AndroidCalendarReader(private val context: Context) : CalendarReader {
    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override fun instances(from: Long, to: Long): List<CalendarInstance> {
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.ALL_DAY,
        )
        val out = mutableListOf<CalendarInstance>()
        // Instances.query expands recurring events into their occurrences in [from, to].
        CalendarContract.Instances.query(context.contentResolver, projection, from, to)?.use { c ->
            while (c.moveToNext() && out.size < 200) {
                out += CalendarInstance(
                    eventId = c.getLong(0),
                    begin = c.getLong(1),
                    end = c.getLong(2),
                    title = c.getString(3).orEmpty(),
                    description = c.getString(4),
                    location = c.getString(5),
                    calendar = c.getString(6),
                    allDay = c.getInt(7) != 0,
                )
            }
        }
        return out
    }

    override fun calendars(): List<String> {
        if (!hasPermission()) return emptyList()
        val names = mutableListOf<String>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),
            "${CalendarContract.Calendars.VISIBLE} = 1",
            null,
            null,
        )?.use { c -> while (c.moveToNext()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { names += it } }
        return names.distinct().sortedBy { it.lowercase() }
    }
}

/** The checker with the phone's sources. */
@Singleton
class TriggerSources @Inject constructor(
    private val tasks: TaskRepository,
    private val http: HttpClient,
    private val secrets: SecretResolver,
    private val vault: SecretStore,
    private val calendar: CalendarReader,
) {
    val checker = TriggerChecker(tasks) { source ->
        when (source) {
            TriggerSource.CALENDAR -> CalendarEvents(calendar)
            TriggerSource.NTFY -> NtfyEvents(http, { id -> vault.get(SecretStore.triggerToken(id)) }, secrets)
            TriggerSource.MAIL -> MailEvents(secrets)
            TriggerSource.APP -> null
        }
    }
}
