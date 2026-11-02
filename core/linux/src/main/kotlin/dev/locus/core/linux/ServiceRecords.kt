package dev.locus.core.linux

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * The background services the agent started, kept on disk so they outlive the app process. A
 * service's proot process dies with Locus (`--kill-on-exit`, and Android ends an app's children), so
 * after a restart the in-memory list is empty while the agent may still think its server is up. With
 * these records, `linux_service_list` reports such a service as stopped, with how to start it again,
 * instead of silently saying there are none. Services aren't restarted automatically: starting one
 * runs a command, and that stays the agent's (and the user's approval's) decision.
 */
internal class ServiceRecords(private val file: File) {
    data class Record(val name: String, val command: String, val cwd: String, val startedAt: Long)

    private val lock = Any()

    fun all(): List<Record> = synchronized(lock) { read() }.sortedBy { it.startedAt }

    fun add(record: Record) = synchronized(lock) { write(read().filterNot { it.name == record.name } + record) }

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
