package dev.locus.feature.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.data.chatimport.ChatImporter
import dev.locus.core.data.chatimport.ExportException
import dev.locus.core.data.chatimport.ExportFile
import dev.locus.core.data.chatimport.ExportProblem
import dev.locus.core.data.chatimport.ImportPlan
import dev.locus.core.data.chatimport.ImportProgress
import dev.locus.core.data.chatimport.ImportResult
import dev.locus.core.data.chatimport.ImportScan
import dev.locus.core.data.chatimport.ImportTexts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.FileNotFoundException
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/** Where importing chats from ChatGPT or Claude is (docs/design/import-chats.md). Each step after [Idle] is one sheet. */
sealed interface ImportStep {
    data object Idle : ImportStep

    /** First pass: reading the export to count what's in it. Nothing is written. */
    data class Reading(val progress: ImportProgress) : ImportStep

    /** The file isn't an export, or couldn't be read. */
    data class Failed(val problem: ExportProblem) : ImportStep

    /**
     * What was found, and which chats to import: all of them, or only some years ([years] non-null).
     * [plan] is what Import will do with that choice.
     */
    data class Preview(val scan: ImportScan, val summary: ImportSummary, val years: Set<Int>?, val plan: ImportPlan) : ImportStep

    /** Second pass: writing the chosen chats. [total] is how many will be written. */
    data class Importing(val progress: ImportProgress, val total: Int) : ImportStep
}

/** The preview's facts, worked out once from the scan. */
data class ImportSummary(
    /** Chats per source, most first, each with the years its chats started in. */
    val sources: List<SourceCount>,
    val firstYear: Int?,
    val lastYear: Int?,
    /** Chats per year the chats started in, oldest first. */
    val perYear: List<Pair<Int, Int>>,
    val unreadable: Int,
    val empty: Int,
    val truncated: Boolean,
) {
    companion object {
        fun of(scan: ImportScan, zone: ZoneId): ImportSummary {
            val years = scan.chats.groupingBy { yearOf(it.createdAt, zone) }.eachCount().toSortedMap()
            return ImportSummary(
                sources = scan.chats.groupBy { it.source }.entries.sortedByDescending { it.value.size }.map { (source, chats) ->
                    SourceCount(source.displayName, chats.size, yearOf(chats.minOf { it.createdAt }, zone), yearOf(chats.maxOf { it.createdAt }, zone))
                },
                firstYear = years.keys.firstOrNull(),
                lastYear = years.keys.lastOrNull(),
                perYear = years.map { it.key to it.value },
                unreadable = scan.unreadable,
                empty = scan.empty,
                truncated = scan.truncated,
            )
        }

        fun yearOf(millis: Long, zone: ZoneId): Int = Instant.ofEpochMilli(millis).atZone(zone).year
    }
}

/** One source in the preview: "1,284 ChatGPT chats · 2019 to 2026". */
data class SourceCount(val name: String, val chats: Int, val firstYear: Int, val lastYear: Int)

/** How the last import ended, for the page's banner. */
data class ImportOutcome(val result: ImportResult, val cancelled: Boolean)

data class ImportChatsUiState(val step: ImportStep = ImportStep.Idle, val outcome: ImportOutcome? = null)

@HiltViewModel
class ImportChatsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val importer: ChatImporter,
) : ViewModel() {
    private val _state = MutableStateFlow(ImportChatsUiState())
    val state: StateFlow<ImportChatsUiState> = _state.asStateFlow()

    private var files: List<ExportFile> = emptyList()
    private var job: Job? = null
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** The files picked in the system picker; nothing when the picker was cancelled. */
    fun picked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        files = uris.map(::exportFile)
        read()
    }

    private fun read() {
        job?.cancel()
        _state.update { it.copy(step = ImportStep.Reading(ImportProgress(0, files.totalSize(), 0)), outcome = null) }
        job = viewModelScope.launch {
            try {
                val scan = importer.scan(files) { p -> _state.update { s -> if (s.step is ImportStep.Reading) s.copy(step = ImportStep.Reading(p)) else s } }
                if (scan.chats.isEmpty() && scan.unreadable > 0 && scan.empty == 0) {
                    // A JSON list, but nothing in it is a ChatGPT or Claude conversation.
                    _state.update { it.copy(step = ImportStep.Failed(ExportProblem.NotAnExport)) }
                } else {
                    _state.update { it.copy(step = ImportStep.Preview(scan, ImportSummary.of(scan, zone), years = null, plan = scan.plan())) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ExportException) {
                _state.update { it.copy(step = ImportStep.Failed(e.problem)) }
            } catch (_: Exception) {
                _state.update { it.copy(step = ImportStep.Failed(ExportProblem.Unreadable)) }
            }
        }
    }

    /** All chats (null), or only those that started in [years]. */
    fun choose(years: Set<Int>?) = _state.update { s ->
        val step = s.step as? ImportStep.Preview ?: return@update s
        val plan = if (years == null) step.scan.plan() else step.scan.plan { yearOf(it.createdAt) in years }
        s.copy(step = step.copy(years = years, plan = plan))
    }

    private fun yearOf(millis: Long) = ImportSummary.yearOf(millis, zone)

    /** Second pass: writes the chosen chats, with [texts] in the user's language. */
    fun import(texts: ImportTexts) {
        val preview = _state.value.step as? ImportStep.Preview ?: return
        val ids = preview.plan.ids
        if (ids.isEmpty()) return
        job?.cancel()
        _state.update { it.copy(step = ImportStep.Importing(ImportProgress(0, files.totalSize(), 0), ids.size)) }
        var done = ImportResult(0, 0, 0)
        job = viewModelScope.launch {
            try {
                done = importer.import(files, ids, texts) { p ->
                    _state.update { s -> (s.step as? ImportStep.Importing)?.let { s.copy(step = it.copy(progress = p)) } ?: s }
                }
                _state.update { it.copy(step = ImportStep.Idle, outcome = ImportOutcome(done, cancelled = false)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ExportException) {
                _state.update { it.copy(step = ImportStep.Failed(e.problem)) }
            } catch (_: Exception) {
                _state.update { it.copy(step = ImportStep.Failed(ExportProblem.Unreadable)) }
            }
        }
    }

    /**
     * Stops reading or importing. Chats already written stay (each is whole: one transaction per chat);
     * the banner says how many.
     */
    fun cancel() {
        val step = _state.value.step
        job?.cancel()
        job = null
        val written = (step as? ImportStep.Importing)?.progress?.chats
        _state.update {
            it.copy(
                step = ImportStep.Idle,
                outcome = written?.let { n -> ImportOutcome(ImportResult(n, 0, 0), cancelled = true) } ?: it.outcome,
            )
        }
    }

    /** Closes a preview or an error sheet without importing. */
    fun close() {
        if (_state.value.step is ImportStep.Reading || _state.value.step is ImportStep.Importing) return cancel()
        _state.update { it.copy(step = ImportStep.Idle) }
    }

    fun dismissOutcome() = _state.update { it.copy(outcome = null) }

    private fun exportFile(uri: Uri): ExportFile {
        val resolver = context.contentResolver
        var name = uri.lastPathSegment.orEmpty()
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }?.let { name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                }
            }
        }
        return ExportFile(name, size) { resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString()) }
    }

    private fun List<ExportFile>.totalSize(): Long? = if (any { (it.size ?: 0) <= 0 }) null else sumOf { it.size ?: 0 }
}
