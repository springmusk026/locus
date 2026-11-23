package dev.locus.core.data.repository

import dev.locus.core.agent.ChatScope
import dev.locus.core.agent.ToolCallContext
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.tools.ChangeRecorder
import dev.locus.core.agent.tools.CommandChangeTracker
import dev.locus.core.agent.tools.FileChange
import dev.locus.core.agent.tools.ShellSnapshots
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.data.di.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Snapshots a project's folder before a shell command and records what the command changed as the turn's
 * tracked changes (docs/design/shell-snapshots.md). Only in a project chat whose project has "Snapshot before
 * shell commands" on; anywhere else the command runs exactly as before. The command's result is never touched.
 */
@Singleton
class ShellChangeTracker internal constructor(
    private val workspace: Workspace,
    private val snapshots: ShellSnapshots,
    private val recorder: ChangeRecorder,
    private val notes: ShellNotes,
    private val io: CoroutineDispatcher,
    /** Whether the project with this id has the setting on. */
    private val enabled: suspend (String) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) : CommandChangeTracker {
    @Inject constructor(
        workspace: Workspace,
        snapshots: ShellSnapshots,
        changes: ChangeRepository,
        notes: ShellNotes,
        settings: SettingsRepository,
        @IoDispatcher io: CoroutineDispatcher,
    ) : this(workspace, snapshots, changes, notes, io, { id -> id in settings.current().shellSnapshotProjects })

    override suspend fun track(block: suspend () -> ToolResult): ToolResult {
        val ctx = currentCoroutineContext()[ToolCallContext]
        val owner = ctx?.changeOwner
        val scope = ChatScope.current()
        if (owner == null || scope == null) return block()
        val on = try {
            enabled(scope.projectId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (!on) return block()
        // The project's folder as the command sees it at /workspace (ChatScope rides along to the IO thread).
        val folder = withContext(io) { runCatching { workspace.root }.getOrNull() } ?: return block()
        val taken = withContext(io) { snapshots.take(scope.projectId, folder, owner.conversationId, owner.turnId, ctx.callId) }
        if (taken is ShellSnapshots.Outcome.Skipped) {
            notes.add(ShellNote(owner.conversationId, owner.turnId, ctx.callId, taken.reason, clock()))
            return block()
        }
        val manifest = (taken as ShellSnapshots.Outcome.Taken).manifest
        try {
            return block()
        } finally {
            // A stopped command may still have changed files: they're recorded all the same.
            withContext(NonCancellable + io) {
                try {
                    record(folder, manifest, ctx.callId)
                } catch (_: Exception) {
                    // Tracking is a convenience: it never fails the command.
                } finally {
                    snapshots.release(manifest.id)
                    runCatching { snapshots.retain() }
                }
            }
        }
    }

    private suspend fun record(folder: File, manifest: ShellSnapshots.Manifest, callId: String) {
        val (after, skipped) = snapshots.scan(folder)
        if (after == null) {
            notes.add(ShellNote(manifest.conversationId, manifest.turnId, callId, skipped ?: ShellSnapshots.SkipReason.FAILED, clock()))
            return
        }
        val changes = ShellSnapshots.diff(manifest.files, after)
        if (changes.size > snapshots.limits.maxChanges) {
            notes.add(ShellNote(manifest.conversationId, manifest.turnId, callId, ShellSnapshots.SkipReason.TOO_MANY_CHANGES, clock()))
            return
        }
        val owner = dev.locus.core.agent.tools.ChangeOwner(manifest.conversationId, manifest.turnId)
        // Paths relative to the whole workspace, as the file tools' records and the trash name them. Joined, not
        // canonicalised: a path the command turned into a link must not resolve to where the link points.
        val prefix = workspace.stored(folder).takeIf { it != "." }?.let { "$it/" }.orEmpty()
        changes.forEach { c ->
            try {
                recorder.record(FileChange(owner, callId, ShellSnapshots.TOOL, prefix + c.path, existedBefore = c.before != null, beforeTrashId = c.before?.let(ShellSnapshots::ref)))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // One path that can't be recorded (a link stands there now) doesn't stop the rest.
            }
        }
    }
}
