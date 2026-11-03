package dev.locus.core.linux

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * The background services the agent started, kept on disk so they outlive the app process. A
 * service's proot process dies with Locus (`--kill-on-exit`, and Android ends an app's children), so
 * after a restart the in-memory list is empty while the agent may still think its server is up. With
 * these records, `linux_service_list` reports such a service as stopped, with how to start it again,
 * instead of silently saying there are none.
 *
 * A record also keeps how the service ended while Locus was still running ([exitCode],
 * [killedByAndroid]), so after a restart a crash still reads as a crash rather than as "a restart
 * ended it". Services start again on their own only when the user turned on [Record.autostart] for
 * one on the health page (docs/design/linux-health.md); the agent can't set it.
 */
internal class ServiceRecords(private val file: File) {
    /**
     * [folder] is the project folder mounted at /workspace for it, or null for the whole workspace.
     * [exitCode] is set when it ended on its own while Locus ran (null: still running, or a restart
     * ended it); [killedByAndroid] when that end was Android's phantom process limit ([AndroidKill]).
     * [autostart]: the user asked for it to start again whenever Locus opens.
     */
    data class Record(
        val name: String,
        val command: String,
        val cwd: String,
        val startedAt: Long,
        val folder: String? = null,
        val exitCode: Int? = null,
        val killedByAndroid: Boolean = false,
        val autostart: Boolean = false,
    )

    private val lock = Any()

    fun all(): List<Record> = synchronized(lock) { read() }.sortedBy { it.startedAt }

    fun add(record: Record) = synchronized(lock) { write(read().filterNot { it.name == record.name } + record) }

    /** Replaces [name]'s record with [change] of it; false (and nothing written) when there's none. */
    fun update(name: String, change: (Record) -> Record): Boolean = synchronized(lock) {
        val records = read()
        if (records.none { it.name == name }) return@synchronized false
        write(records.map { if (it.name == name) change(it) else it })
        true
    }

    /** True if there was a record for [name]. */
    fun remove(name: String): Boolean = synchronized(lock) {
        val records = read()
        val kept = records.filterNot { it.name == name }
        if (kept.size != records.size) write(kept)
        kept.size != records.size
    }

    fun clear() = synchronized(lock) { file.delete() }

    private fun read(): List<Record> {
        if (!file.isFile) return emptyList()
        val array = runCatching { Json.parseToJsonElement(file.readText()) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            Record(
                name = name,
                command = (o["command"] as? JsonPrimitive)?.content.orEmpty(),
                cwd = (o["cwd"] as? JsonPrimitive)?.content ?: "/workspace",
                startedAt = o["startedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                folder = (o["folder"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                exitCode = (o["exitCode"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull,
                killedByAndroid = (o["killedByAndroid"] as? JsonPrimitive)?.booleanOrNull ?: false,
                autostart = (o["autostart"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }
    }

    private fun write(records: List<Record>) {
        file.parentFile?.mkdirs()
        val json = buildJsonArray {
            records.forEach { r ->
                add(buildJsonObject {
                    put("name", r.name)
                    put("command", r.command)
                    put("cwd", r.cwd)
                    put("startedAt", r.startedAt)
                    r.folder?.let { put("folder", it) }
                    r.exitCode?.let { put("exitCode", it) }
                    if (r.killedByAndroid) put("killedByAndroid", true)
                    if (r.autostart) put("autostart", true)
                })
            }
        }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(json.toString())
            tmp.delete()
        }
    }
}
