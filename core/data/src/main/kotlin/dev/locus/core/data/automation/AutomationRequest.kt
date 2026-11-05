package dev.locus.core.data.automation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One request from another app, parsed from its intent's action and extras. Parsing never trusts the
 * extras: everything is checked and bounded here, before anyone is asked (docs/design/android-bridge.md).
 */
sealed interface AutomationRequest {
    /** The caller's own id for this request, echoed back with the result. */
    val requestId: String?

    data class StartChat(val prompt: String, val project: String?, val openChat: Boolean, override val requestId: String?) : AutomationRequest

    data class RunWorkflow(val workflow: String, val params: Map<String, String>, override val requestId: String?) : AutomationRequest

    data class RunTask(val task: String, override val requestId: String?) : AutomationRequest

    /** An event for tasks triggered by another app (docs/design/event-triggers.md). */
    data class Event(val name: String, val text: String?, override val requestId: String?) : AutomationRequest

    /** What the request asks for, in a few words, for the confirmation sheet and the result. */
    val summary: String
        get() = when (this) {
            is StartChat -> "start a chat${project?.let { " in project “$it”" }.orEmpty()}"
            is RunWorkflow -> "run the workflow “$workflow”"
            is RunTask -> "run the scheduled task “$task” now"
            is Event -> "send the event “$name” to tasks waiting for it"
        }

    companion object {
        const val MAX_PROMPT_CHARS = 20_000
        const val MAX_NAME_CHARS = 200
        const val MAX_PARAMS = 50
        const val MAX_PARAM_CHARS = 4_000
        const val MAX_EVENT_TEXT_CHARS = 4_000

        /**
         * [action] and the intent's extras ([extra] reads one by key; [keys] lists them) as a request, or
         * the reason it can't be one. Kept free of Android types so it is tested on the JVM.
         */
        fun parse(action: String?, keys: Set<String>, extra: (String) -> Any?): Parsed {
            fun string(key: String): String? = (extra(key) as? CharSequence)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            val requestId = string(AutomationContract.EXTRA_REQUEST_ID)?.take(MAX_NAME_CHARS)
            return when (action) {
                AutomationContract.ACTION_START_CHAT -> {
                    val prompt = string(AutomationContract.EXTRA_PROMPT) ?: return Parsed.Invalid("${AutomationContract.EXTRA_PROMPT} is missing or empty.")
                    if (prompt.length > MAX_PROMPT_CHARS) return Parsed.Invalid("The prompt is longer than $MAX_PROMPT_CHARS characters.")
                    val project = string(AutomationContract.EXTRA_PROJECT)?.let { it.takeIf { it.length <= MAX_NAME_CHARS } ?: return Parsed.Invalid("The project name is too long.") }
                    val open = when (val v = extra(AutomationContract.EXTRA_SHOW_CHAT)) {
                        null -> false
                        is Boolean -> v
                        is CharSequence -> v.toString().trim().equals("true", ignoreCase = true)
                        else -> return Parsed.Invalid("${AutomationContract.EXTRA_SHOW_CHAT} must be a boolean.")
                    }
                    Parsed.Ok(StartChat(prompt, project, open, requestId))
                }
                AutomationContract.ACTION_RUN_WORKFLOW -> {
                    val workflow = string(AutomationContract.EXTRA_WORKFLOW) ?: return Parsed.Invalid("${AutomationContract.EXTRA_WORKFLOW} is missing or empty.")
                    if (workflow.length > MAX_NAME_CHARS) return Parsed.Invalid("The workflow name is too long.")
                    val params = when (val p = params(keys, extra)) {
                        is ParamsResult.Bad -> return Parsed.Invalid(p.reason)
                        is ParamsResult.Good -> p.values
                    }
                    Parsed.Ok(RunWorkflow(workflow, params, requestId))
                }
                AutomationContract.ACTION_RUN_TASK -> {
                    val task = string(AutomationContract.EXTRA_TASK) ?: return Parsed.Invalid("${AutomationContract.EXTRA_TASK} is missing or empty.")
                    if (task.length > MAX_NAME_CHARS) return Parsed.Invalid("The task name is too long.")
                    Parsed.Ok(RunTask(task, requestId))
                }
                AutomationContract.ACTION_EVENT -> {
                    val name = string(AutomationContract.EXTRA_EVENT) ?: return Parsed.Invalid("${AutomationContract.EXTRA_EVENT} is missing or empty.")
                    if (name.length > MAX_NAME_CHARS) return Parsed.Invalid("The event name is too long.")
                    val text = string(AutomationContract.EXTRA_TEXT)
                    if (text != null && text.length > MAX_EVENT_TEXT_CHARS) return Parsed.Invalid("The event text is longer than $MAX_EVENT_TEXT_CHARS characters.")
                    Parsed.Ok(Event(name, text, requestId))
                }
                else -> Parsed.Invalid("Unknown action ${action?.take(100) ?: "(none)"}.")
            }
        }

        private sealed interface ParamsResult {
            data class Good(val values: Map<String, String>) : ParamsResult
            data class Bad(val reason: String) : ParamsResult
        }

        /** Workflow inputs: the JSON object first, then `dev.locus.param.<name>` extras, which win on a clash. */
        private fun params(keys: Set<String>, extra: (String) -> Any?): ParamsResult {
            val values = linkedMapOf<String, String>()
            (extra(AutomationContract.EXTRA_PARAMS) as? CharSequence)?.toString()?.takeIf { it.isNotBlank() }?.let { raw ->
                val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                    ?: return ParamsResult.Bad("${AutomationContract.EXTRA_PARAMS} must be a JSON object of strings.")
                obj.forEach { (k, v) ->
                    val prim = v as? JsonPrimitive ?: return ParamsResult.Bad("Input “$k” must be a string, number or boolean.")
                    values[k] = prim.content
                }
            }
            keys.filter { it.startsWith(AutomationContract.PARAM_PREFIX) }.sorted().forEach { key ->
                val v = extra(key) ?: return@forEach
                if (v !is CharSequence && v !is Number && v !is Boolean) return ParamsResult.Bad("$key must be a string, number or boolean.")
                values[key.removePrefix(AutomationContract.PARAM_PREFIX)] = v.toString()
            }
            if (values.size > MAX_PARAMS) return ParamsResult.Bad("At most $MAX_PARAMS inputs.")
            values.forEach { (k, v) ->
                if (k.isBlank() || k.length > MAX_NAME_CHARS || !k.all { it.isLetterOrDigit() || it == '_' || it == '-' }) return ParamsResult.Bad("“${k.take(40)}” isn't a valid input name.")
                if (v.length > MAX_PARAM_CHARS) return ParamsResult.Bad("Input “$k” is longer than $MAX_PARAM_CHARS characters.")
            }
            return ParamsResult.Good(values)
        }
    }
}

sealed interface Parsed {
    data class Ok(val request: AutomationRequest) : Parsed
    data class Invalid(val reason: String) : Parsed
}
