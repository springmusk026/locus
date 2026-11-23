package dev.locus.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.integrations.PublishHost
import dev.locus.core.agent.publish.PublishPage
import dev.locus.core.data.publish.PublishService
import dev.locus.core.data.publish.PublishedPage
import dev.locus.core.markdown.artifact.Artifact
import dev.locus.core.markdown.artifact.ArtifactKind
import dev.locus.core.model.AppLockStatus
import dev.locus.core.model.Part
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64
import javax.inject.Inject

/** What is being published (docs/design/publish.md). [source] keys earlier publishes of the same thing. */
internal sealed interface PublishSource {
    val conversationId: String
    val title: String
    val kind: PublishedPage.Kind
    val source: String

    /** The chat on screen ([items] is its active branch); [date] is its start, already formatted. */
    data class Chat(override val conversationId: String, override val title: String, val date: String?, val items: List<ChatItem>) : PublishSource {
        override val kind get() = PublishedPage.Kind.CHAT
        override val source get() = "chat:$conversationId"
    }

    data class Page(override val conversationId: String, val artifact: Artifact) : PublishSource {
        override val title get() = artifact.title ?: artifact.fileName().substringBeforeLast('.')
        override val kind get() = PublishedPage.Kind.ARTIFACT
        override val source get() = "artifact:$conversationId:${artifact.key}"
    }
}

/** A page built in its own folder, ready to preview and upload. [blocked]: it still holds a vault value. */
internal data class BuiltSite(val folder: File, val html: String, val bytes: Long, val removed: Int, val images: Int, val blocked: Boolean)

internal enum class PublishStep { Destination, Building, Preview, Confirm, Publishing, Done, Failed }

/**
 * The publish sheet's state. [hosts] is null until known; empty means no deploy integration is set up.
 * [update] is the earlier page of the same source to replace, or null for a new page.
 */
internal data class PublishUi(
    val source: PublishSource? = null,
    val step: PublishStep = PublishStep.Destination,
    val hosts: List<PublishHost>? = null,
    val host: PublishHost? = null,
    val includeToolResults: Boolean = false,
    val update: PublishedPage? = null,
    val built: BuiltSite? = null,
    val result: PublishedPage? = null,
    /** A message for the user from the host (English, as the deploy tools word it), or null for a generic one. */
    val error: String? = null,
    /** The step a failure goes back to. */
    val failedAt: PublishStep? = null,
) {
    val open get() = source != null
}

/** Publishing from a locked chat asks for authentication first, every time (docs/design/app-lock.md). */
internal object PublishPolicy {
    fun needsUnlock(status: AppLockStatus?, conversationId: String): Boolean =
        status == null || (status.isLockedChat(conversationId) && status.available)
}

/** Publish to your own host: the steps of the sheet, from a destination to a URL. */
@HiltViewModel
class PublishViewModel @Inject constructor(private val service: PublishService) : ViewModel() {
    private val _ui = MutableStateFlow(PublishUi())
    internal val ui: StateFlow<PublishUi> = _ui.asStateFlow()

    /** Every page published from this phone, newest first. */
    val pages: StateFlow<List<PublishedPage>> = service.pages

    private val hosts = service.hosts.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private var labels = PageLabels()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            hosts.collect { list -> _ui.update { ui -> ui.copy(hosts = list, host = ui.host?.takeIf { list != null && it in list } ?: list?.firstOrNull()) } }
        }
    }

    internal fun open(source: PublishSource, labels: PageLabels) {
        this.labels = labels
        val earlier = service.pages.value.firstOrNull { it.source == source.source }
        val list = hosts.value
        val host = earlier?.publishHost?.takeIf { list != null && it in list } ?: list?.firstOrNull()
        _ui.value = PublishUi(source = source, hosts = list, host = host, update = earlier?.takeIf { it.publishHost == host })
    }

    fun close() {
        job?.cancel()
        _ui.value = PublishUi(hosts = hosts.value)
    }

    fun selectHost(host: PublishHost) = _ui.update { ui ->
        val earlier = service.pages.value.firstOrNull { it.source == ui.source?.source && it.host == host.id }
        ui.copy(host = host, update = earlier)
    }

    fun setIncludeToolResults(include: Boolean) = _ui.update { it.copy(includeToolResults = include) }

    /** Publish to the earlier page's site ([page]) or, with null, to a new one. */
    fun setUpdate(page: PublishedPage?) = _ui.update { it.copy(update = page) }

    /** Destination → Building → Preview. */
    fun build() {
        val ui = _ui.value
        val source = ui.source ?: return
        if (ui.host == null) return
        _ui.update { it.copy(step = PublishStep.Building, error = null) }
        job = viewModelScope.launch {
            try {
                val built = buildSite(source, ui.includeToolResults)
                _ui.update { it.copy(step = PublishStep.Preview, built = built) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(step = PublishStep.Failed, error = null, failedAt = PublishStep.Destination) }
            }
        }
    }

    /** Preview → Confirm; never while the page still holds a vault value. */
    fun toConfirm() = _ui.update { ui -> if (ui.built?.blocked == false) ui.copy(step = PublishStep.Confirm) else ui }

    fun back() = _ui.update { ui ->
        when (ui.step) {
            PublishStep.Confirm -> ui.copy(step = PublishStep.Preview)
            PublishStep.Preview, PublishStep.Failed -> ui.copy(step = ui.failedAt?.takeIf { ui.step == PublishStep.Failed } ?: PublishStep.Destination, error = null, failedAt = null)
            else -> ui
        }
    }

    /** Confirm → Publishing → Done or Failed. The screen has already asked for authentication when [PublishPolicy] says so. */
    fun publish() {
        val ui = _ui.value
        val source = ui.source ?: return
        val host = ui.host ?: return
        val built = ui.built ?: return
        if (built.blocked || ui.step != PublishStep.Confirm) return
        _ui.update { it.copy(step = PublishStep.Publishing) }
        job = viewModelScope.launch {
            try {
                val page = service.publish(host, built.folder, source.title, source.kind, source.source, ui.update)
                _ui.update { it.copy(step = PublishStep.Done, result = page) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(step = PublishStep.Failed, error = (e as? ToolException)?.message, failedAt = PublishStep.Confirm) }
            }
        }
    }

    /** Takes [page] off its host and out of the list; [onDone] gets null, or the host's message (or "") on failure. */
    fun unpublish(page: PublishedPage, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val failure = try {
                service.unpublish(page)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                (e as? ToolException)?.message.orEmpty()
            }
            _ui.update { ui -> if (ui.update?.id == page.id) ui.copy(update = null) else ui }
            onDone(failure)
        }
    }

    /** Drops [page] from the list only (it was removed on the provider's site). */
    fun forget(page: PublishedPage) {
        viewModelScope.launch { service.forget(page) }
        _ui.update { ui -> if (ui.update?.id == page.id) ui.copy(update = null) else ui }
    }

    private suspend fun buildSite(source: PublishSource, includeToolResults: Boolean): BuiltSite = withContext(Dispatchers.IO) {
        val vault = service.vaultValues()
        val scrubber = PublishPage.Scrubber(vault)
        val folder = service.newSiteFolder()
        val (html, removed, images) = when (source) {
            is PublishSource.Chat -> {
                val page = ChatPage.build(
                    source.title, source.date, source.items, labels, includeToolResults, scrubber,
                    image = ::dataUrl, lang = java.util.Locale.getDefault().toLanguageTag(),
                )
                Triple(page.html, page.removed, page.images)
            }
            is PublishSource.Page -> {
                val scrubbed = scrubber.scrub(source.artifact.content)
                Triple(PublishPage.artifactDocument(scrubbed.text, source.artifact.kind == ArtifactKind.Svg, source.title), scrubbed.removed, 0)
            }
        }
        val index = File(folder, "index.html").apply { writeText(html) }
        BuiltSite(folder, html, index.length(), removed, images, blocked = PublishPage.leaksVaultValue(listOf(html), vault))
    }

    internal companion object {
        /** A picture on the page: at most 3 MB each, as a `data:` URL, so the page loads nothing from elsewhere. */
        fun dataUrl(a: Part.Attachment): String? {
            val file = File(a.path)
            if (!file.isFile || file.length() > MAX_IMAGE_BYTES) return null
            val type = a.mimeType.lowercase().takeIf { it in IMAGE_TYPES } ?: return null
            return "data:$type;base64," + Base64.getEncoder().encodeToString(file.readBytes())
        }

        private const val MAX_IMAGE_BYTES = 3L * 1024 * 1024
        private val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
    }
}
