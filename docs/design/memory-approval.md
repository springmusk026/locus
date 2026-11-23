# Approving memory writes, and tidying memory up

Status: built 2026-11-21 ([feature opportunities #3](../research/feature-opportunities-2026-11.md)). Not yet tried on the phone or against a live model. Code: `MemoryApproval` (core/model `Settings.kt`, sealed in `Integrity.kt`), `MemoryWrites` and the memory tools (core/agent `home/HomeTools.kt`), `MemoryChange` (core/agent `Tool.kt`), `MemoryTidy` (core/agent `home/MemoryTidy.kt`), `MemoryTidyRepository` (core/data), `MemoryChangeRows` (core/ui `ApprovalCard.kt`), Settings → Agent → Memory (`AgentSettings.kt`), Library → Memory → Tidy up (`MemoryTidyUi.kt`).

Memory is the one thing the agent writes that every later chat reads: the index goes into each new chat's system prompt. Until now `memory_save` and `memory_forget` ran on their own unless the run had read outside content ([taint](taint.md)). Some people want to see every change first (RikkaHub [#1927](https://github.com/rikkahub/rikkahub/issues/1927) asks for exactly this). And a memory that grows by small saves collects duplicates and notes that went stale (RikkaHub 3.1 added a "dreaming" pass that consolidates on its own).

## Ask before saving to memory

Settings → Agent → Memory → **Ask before saving to memory**:

| Choice | `memory_save` / `memory_forget` |
|---|---|
| **After outside content** (default) | As before: they run on their own (their tool policy is Allow by default) and ask once the run has read outside content (taint). |
| **Always** | They ask every time, like a tool set to Ask, whatever the tool's own Allow says. A tainted run still adds its line and flag. |

The same holds for a project's memory: in a project chat both tools write the project's own memory (see [projects](projects.md#memory-scope)), and the rule is the same.

**Only the policy changes.** The tools declare the same specs, the system prompt is frozen as before, and nothing in the transcript changes: a request built with Always is byte-for-byte the request built without it (`MemoryApprovalTest`, "the setting leaves the tool list and the request unchanged"). The switch is read for each call (`MemoryWrites.alwaysAsk`), so it applies from the next call on, in chats that are already open, without touching their cached prefix.

How it's built:

- The tools answer `Tool.asksFirst` with the setting, so the loop turns their Allow into Ask (the same hook connected-folder writes use). Deny, read-only runs and the chat's and project's limits apply first, as for any call.
- **No "don't ask again".** `ApprovalScopes.derive` has no scope for memory tools, so none is offered, and the card says why: "Memory changes can't be remembered: each one is shown in full for you to check."
- **The reviewer can't clear it alone.** With Always, `Tool.reviewerClearance` is `USER_ONLY`: the safety reviewer may still block a save or add its note, but its "allow" becomes a line on the card. The user chose to see every change.
- `memory_forget` of a topic that isn't there now fails in `precheck` ("No such topic.", the same text as before), so nobody is asked to approve a call that can only fail.

What happens where nobody can be asked follows the existing paths; nothing new:

- **Scheduled and other read-only background runs** already refuse every memory write (they aren't observations), with or without the setting.
- **Helpers**' approvals go to the parent chat's card, as all their asks do.
- **Learn after tasks** (the reflection pass) has no one to ask; an asking call is refused there. With Always it would therefore save nothing, so it isn't run at all, which saves its request. The setting's note says so.
- **Voice mode** never answers a memory change by voice (2026-11-21): it reads only the one-line summary ("Remember “coffee”"), not the note, so it says the card needs a look on the screen and leaves it pending; its card shows the whole change ([voice mode](voice-mode.md#approvals-by-voice)). **Telegram** only gets background approvals, and background runs can't write memory.

### The card

`Tool.preview` gives the card a `MemoryChange` (in `ApprovalPreview.memory`) beside the English rows, so it is shown in the app's language (core/ui `MemoryChangeRows`):

- **Memory**: General memory, or Project “Garden planner” (the project's name from `MemoryWrites.projectName`).
- **Topic**, as Library names it.
- **Change**: New note, Replaces the saved note (caution), or Forgets this note (critical).
- **Replaces**: the summary of the note being replaced.
- **Summary**: the line that goes into every new chat's memory index, whole.
- **Note**: the whole note, selectable, on a code surface.

What the card shows is exactly what is written: the summary's first line cut to 150 characters, the note trimmed, and, in a tainted run, both cleaned of hidden characters ([taint](taint.md#hidden-characters)) before they are shown, as `execute` writes them.

### Why the card can't edit the note

An approval answers yes or no to the call the model made: `ApprovalGate.answer` returns an `ApprovalAnswer` (Deny, Allow, Allow for this chat, Always, Allow site), and every gate (the chat, the browser's card, notifications, Telegram, voice) is built on that. Letting the card change the arguments would mean a new answer type carrying edited JSON through every gate, and a tool result that says the user changed the text, or the model would believe it saved what it wrote. That is more than this feature needs, so the card offers **Allow** and **Deny** only. To change a note, deny it with a reason that says what to save instead ([deny with a reason](remembered-approvals.md#deny-with-a-reason)), or edit it afterwards in Library → Memory (which makes it the user's, see [memory provenance](taint.md#memory-provenance)).

### Sealed

The setting is a permission: a program running as Locus's uid (Linux, see [linux isolation](linux-isolation.md)) mustn't be able to switch Always off. It is part of the policy seal (`PolicySeal`), only when it isn't the default, so seals made before it existed stay valid. Switching it off outside Locus breaks the seal, and the user gets the usual tampered-permissions alert; as with every other permission, the fallback goes no further than the defaults (it doesn't force Always on people who never chose it). One gap is inherent: when Always is the *only* non-default permission, resetting it gives settings with nothing granted at all, which the seal treats as defaults and doesn't flag.

## Tidy up

Library → Memory → **Tidy up**. One helper-model request reads the notes and proposes changes; the user ticks the ones to make; only those are applied. It never runs on its own.

1. **Availability.** The button uses the same helper model as titles and suggestions (`HelperModel.providerFor` for the default provider). With no provider it says "Needs a model" and links to Settings → Models; when the helper can't answer now (an on-device model, too slow for this, or a provider without its key or sign-in) it says so and links to Settings → Helper model & extras. The row is disabled then.
2. **The request** (`MemoryTidy.prompt`, English): every memory with notes, general first and then each project's, each note with an id (`n1`…), its topic, summary, the date it was saved and its text, as data the model is told not to follow. Today's date is in it, so stale notes can be told. A note longer than 4,000 characters is sent cut short and marked, and may only be proposed for deletion; the notes stop at about 60,000 characters (newest first), and the review says how many were left out. The answer is JSON: `merge` (ids, topic, summary, the merged note in full, reason) or `delete` (id, reason). Up to 6,000 output tokens, 3 minutes.
3. **Reading the answer** (`MemoryTidy.parse`) tolerates prose and code fences and a bare list. A merge needs two or more distinct notes of one memory, none cut short, and a non-empty note; it keeps a source's name unless the model gave a new one, and never takes the name of a note it isn't merging. A delete needs one note. A note is used by one proposal at most. Text the model wrote is cleaned of hidden characters. Anything else is dropped and counted ("1 suggestion was left out…"). An answer with no list at all is an error; nothing changes.
4. **The review** (a sheet): each proposal with a checkbox, its reason, its memory when there is more than one, and for a merge the summary and the whole merged note. Nothing is ticked at first. **Apply (n)** applies the ticked ones; closing the sheet cancels a request still running and applies nothing.
5. **Applying** (`MemoryTidy.apply`) goes through `MemoryStore.saveMemory` and `forgetMemory`, the calls Library's own Edit and Forget use, so the search index, the memory index and history are kept up the same way. A proposal whose notes changed since they were read (an edit, a save, a forget) is skipped whole, and the message says so. Replaced and forgotten versions go to agent-home's history, as every memory change does; Library has no undo for memory edits today, so neither does this.
6. **Provenance.** A merged note is saved as `tidy` ("Merged in a tidy-up you accepted"), at the time it was applied, linked to the chat of its newest source, with every source's `taintedBy` (a note saved while reading a web page stays flagged after a merge, so a tidy-up can't launder it) and the names it was merged from (`MemoryProvenance.mergedFrom`, shown as "Merged from coffee, coffee prefs").
7. **Cost.** The request's tokens are recorded under its own source, `memory_tidy`, so Insights shows what tidying cost ("Memory tidy-ups"). Task budgets are per chat task and don't apply to it.

States: the row (ready, no model, helper unavailable, checking), the sheet (working, error with Try again, nothing to tidy, proposals, applying), and the message after applying.

## New notes from imported chats

"Suggest memories from imported chats" reuses this review with a third proposal kind, `MemoryTidy.Proposal.New` (a note to add, with the imported chat it came from), applied by the same `MemoryTidy.apply`: never over an existing note, saved as `import` and flagged as outside content. See [import chats](import-chats.md#suggest-memories-from-imported-chats).

## Reading a flagged note

Approving a save keeps the note out of memory until the user has seen it; it doesn't make the text the user's. A note saved after reading outside content (or suggested from an imported chat) stays flagged once saved, and later runs treat it as outside content: `memory_read` and `memory_search` return it marked with where it came from, reading it taints the run ("a memory note from an imported chat"), and new chats' memory index marks its line "(from outside content)". Existing chats keep the prompt they were created with. Editing the note in Library makes it the user's and clears the flag; a tidy-up merge keeps it. See [taint](taint.md#memory-notes-from-outside-content).

## Not done

- **Editing a note on the approval card** (above).
- **A periodic tidy-up.** The research note said "periodic"; this runs only when the user taps it: a scheduled one would spend tokens unasked, and its result would wait for review anyway.
- **Undo** for applied changes, which Library doesn't have for memory edits either. The old versions are in history.

## Tests

`MemoryApprovalTest` (core/agent): default Allow untainted, Ask when tainted, Ask always with Always (with the change on the card, no "don't ask again", a denied save writes nothing, a replacing save names the note), forget, a forget of nothing that asks no one, project memory writes the same way with the project's name, the reviewer can't clear it alone, read-only runs refuse, and the request is unchanged by the setting. `MemoryTidyTest` (core/agent): the prompt, cut-short notes, parsing with prose and fences, malformed answers and dropped items, names and hidden characters, applying a subset with provenance, nothing on an empty acceptance, and skipping a note changed since. `MemoryTidyRepositoryTest` (core/data): availability, one request with its usage recorded, a failed or unreadable answer, no notes, and the tools reading the saved setting. `MemoryTidyViewModelTest` (feature/library): only accepted proposals apply, cancel applies nothing, errors retry. `MemoryApprovalSealTest` (core/model), `SettingsViewModelTest` (persistence). Screenshots: the memory approval card (new, replacing in a project, forget), Settings → Agent with Always, Library → Memory with Tidy up (ready and without a model), the helper-unavailable row, and the review sheet (proposals, nothing to tidy, working, error).
