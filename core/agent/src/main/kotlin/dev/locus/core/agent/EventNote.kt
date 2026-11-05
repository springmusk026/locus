package dev.locus.core.agent

import dev.locus.core.model.ModeNote

/**
 * The note an event-triggered run carries (docs/design/event-triggers.md): which source fired, and the
 * event's text inside `<untrusted_web_content>` markers. Whoever wrote a calendar entry, an ntfy message
 * or a mail's subject can put anything there, so:
 *
 * - the text is defused, so it can't close the markers early, and cut to [MAX_CHARS];
 * - every value the vault holds, and anything shaped like a credential, is replaced first ([SecretScrubber]),
 *   so a secret that ends up in an event (a token echoed by a server, say) never reaches the model;
 * - it rides as a note, so it never counts as the user's words, and [ModeNote.eventSource] makes the
 *   run tainted from its first call (`Taint.sourcesIn`).
 */
object EventNote {
    const val MAX_CHARS = 6_000

    /** The note for an event from [source] ("ntfy message on ntfy.sh/alerts") with [details], scrubbed with [scrubber]. */
    fun of(source: String, details: String, scrubber: SecretScrubber = SecretScrubber()): String {
        val clean = scrubber.scrub(details).let { if (it.length > MAX_CHARS) it.take(MAX_CHARS) + "\n[cut: the event was longer]" else it }
        return ModeNote.event(source, UntrustedContent.wrap(clean, UntrustedContent.note("an event from $source, written outside Locus")))
    }
}
