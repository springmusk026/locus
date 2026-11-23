package dev.locus.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.repository.ProjectRepository
import dev.locus.core.linux.LinuxEnvironment
import dev.locus.core.linux.LinuxStatus
import dev.locus.core.model.ProjectRule
import dev.locus.core.model.RuleProblem
import dev.locus.core.model.RuleTrigger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** A rule as the editor holds it: the timeout as typed. */
data class RuleDraft(
    /** Null for a new rule. */
    val id: String? = null,
    val name: String = "",
    val trigger: RuleTrigger = RuleTrigger.AFTER_EDIT,
    val pattern: String = "",
    val command: String = "",
    val timeout: String = ProjectRule.DEFAULT_TIMEOUT.toString(),
    val message: String = "",
    val enabled: Boolean = true,
    /** The stored rule was suspended (changed outside Locus): saving it resumes it. */
    val suspended: Boolean = false,
    val createdAt: Long = 0,
) {
    fun toRule(id: String, now: Long) = ProjectRule(
        id = id,
        name = name.trim(),
        trigger = trigger,
        pattern = pattern.trim(),
        command = if (trigger.runsCommand) command.trim() else "",
        timeoutSeconds = timeout.trim().toIntOrNull() ?: -1,
        message = if (trigger.runsCommand) "" else message.trim(),
        enabled = enabled,
        suspended = false,
        createdAt = if (createdAt > 0) createdAt else now,
    )

    /** What's wrong with it, or null when it can be saved. */
    val problem: RuleProblem? get() = toRule(id ?: "new", 1).problem()

    companion object {
        fun of(r: ProjectRule) = RuleDraft(r.id, r.name, r.trigger, r.pattern, r.command, r.timeoutSeconds.toString(), r.message, r.enabled, r.suspended, r.createdAt)
    }
}

data class ProjectRulesState(
    val loading: Boolean = true,
    /** The project is gone (deleted elsewhere). */
    val missing: Boolean = false,
    val projectName: String = "",
    /** Every rule, suspended ones included, in the order they run. */
    val rules: List<ProjectRule> = emptyList(),
    /** Linux is installed and ready: rules that run commands are active. */
    val linuxReady: Boolean = false,
    /** The open editor, or null. */
    val editing: RuleDraft? = null,
) {
    val suspended: Int get() = rules.count { it.suspended }
    val full: Boolean get() = rules.size >= ProjectRule.MAX_PER_PROJECT
}

/**
 * A project's rules (docs/design/rules.md). Only this screen writes them, into the sealed settings;
 * the agent has no tool for them.
 */
@HiltViewModel(assistedFactory = ProjectRulesViewModel.Factory::class)
class ProjectRulesViewModel @AssistedInject constructor(
    @Assisted private val projectId: String,
    private val projects: ProjectRepository,
    private val linux: LinuxEnvironment,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(projectId: String): ProjectRulesViewModel
    }

    private val _state = MutableStateFlow(ProjectRulesState())
    val state: StateFlow<ProjectRulesState> = _state

    init {
        viewModelScope.launch {
            combine(projects.observe(projectId), projects.rules(projectId), linux.status) { project, rules, status -> Triple(project, rules, status) }
                .collect { (project, rules, status) ->
                    _state.update {
                        it.copy(
                            loading = false,
                            missing = project == null,
                            projectName = project?.name.orEmpty(),
                            rules = rules,
                            linuxReady = status == LinuxStatus.Ready,
                        )
                    }
                }
        }
    }

    fun add() = _state.update { if (it.full) it else it.copy(editing = RuleDraft()) }

    fun edit(rule: ProjectRule) = _state.update { it.copy(editing = RuleDraft.of(rule)) }

    fun close() = _state.update { it.copy(editing = null) }

    fun change(transform: RuleDraft.() -> RuleDraft) = _state.update { s -> s.copy(editing = s.editing?.transform()) }

    /** Saves the open rule (a suspended one is resumed: the user has now seen it). */
    fun save() = viewModelScope.launch {
        val draft = _state.value.editing ?: return@launch
        if (draft.problem != null) return@launch
        val current = projects.rules(projectId).first()
        val rule = draft.toRule(draft.id ?: UUID.randomUUID().toString(), System.currentTimeMillis())
        val next = if (current.any { it.id == rule.id }) current.map { if (it.id == rule.id) rule else it } else current + rule
        projects.setRules(projectId, next.take(ProjectRule.MAX_PER_PROJECT))
        _state.update { it.copy(editing = null) }
    }

    fun delete(id: String) = viewModelScope.launch {
        projects.setRules(projectId, projects.rules(projectId).first().filterNot { it.id == id })
        _state.update { it.copy(editing = null) }
    }

    /** The user's switch; a suspended rule stays suspended until it's opened and resumed. */
    fun setEnabled(id: String, enabled: Boolean) = viewModelScope.launch {
        projects.setRules(projectId, projects.rules(projectId).first().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }
}
