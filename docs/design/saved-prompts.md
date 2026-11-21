# Saved prompts

Status: built 2026-11-21. Roadmap: "Saved prompts with variables" under "From the feature-opportunities research". Idea #2 in [feature opportunities](../research/feature-opportunities-2026-11.md), after Open WebUI's `/` prompts (read through search summaries only, so the syntax below is Locus's own, not a copy). Code: `core/model/SavedPrompt.kt`, `core/agent/prompt/` (`PromptTemplate`, `PromptStore`, `PromptSearch`, `SlashQuery`), `core/data/repository/PromptRepository.kt`, `core/ui/PromptSheets.kt`, `feature/chat/ComposerPrompts.kt` and `LocusComposer.kt`, `feature/library/LibraryPrompts.kt`.

A saved prompt is text the user sends often, kept under a name and put into the composer when picked. It never sends itself: the user reads the text, changes it if they like, and taps Send.

A prompt is not a skill (a how-to the agent may read) and not a workflow (a task with tools, approvals and checks). It is only the words of a message.

## Where they are

- **Library → Skills → Prompts**, between Workflows and Skills: the list in the user's order, New prompt, and per prompt (tap to open) its full text, what it asks for, Move up, Move down, Remove and Edit. When the tab has nothing at all, its empty state offers New prompt beside Import a skill.
- **The composer**, in a chat and on home:
  - Typing `/` into an empty box shows a short list over the text box: up to 4 saved prompts matching what follows the `/`, best first, and `/btw` in a chat. With no prompts saved, a bare `/` offers "Saved prompts: none yet", which opens the picker where one can be made.
  - **+ → Insert a prompt** opens the picker: a search box, every prompt, and New prompt. Its empty state explains prompts and offers New prompt. A prompt made there is saved and the picker comes back with it.

## The `/` list and `/btw`

The `/` list only offers. It shows while the box holds `/` and then at most 40 characters with no space or line break (`SlashQuery`), and it never changes the text unless a row is tapped. So:

- `/btw question` still asks a side question, and `/btw` alone still reopens the last one (`SideCommand`); `/btw` is listed in the `/` list in a chat, and tapping it puts `/btw ` in the box.
- A message that starts with `/` (`/etc/hosts has a typo`) shows the list only until the first space, and sends as typed.
- Send with `/tra` in the box sends `/tra`. Picking is always a tap.

Search ignores case and accents and ranks names that start with the query, then names with a word that does, then names or texts containing it (`PromptSearch`).

## Syntax

Variables are written in double braces. Spaces inside the braces and the case of a name don't matter.

| Write | Becomes |
|---|---|
| `{{clipboard}}` | The clipboard's text |
| `{{date}}` | Today's date, written out ("Saturday, November 21, 2026") |
| `{{time}}` | The time now ("14:05" or "2:05 PM") |
| `{{selection}}` | The text that was in the box when the prompt was picked: text shared to Locus, an "Ask Locus" selection, or what the user typed |
| `{{field:Label}}` | A box in a form, titled Label, that must be filled |
| `{{field:Label\|default}}` | The same, filled with default and allowed to stay as it is (an empty default makes it optional) |
| `\{{` | A literal `{{` |
| `\\{{` | A backslash, then a variable |

- A field's label ends at the first `|`; spaces around the label and the default are dropped. A label is 1 to 40 characters with no braces; a default is up to 500 characters.
- The same label (ignoring case and outer spaces) is one box, filled into every place it appears. If only one occurrence gives a default, the box takes it; two different defaults are an error.
- At most 12 different fields.
- A `}}` with no `{{`, single braces and backslashes anywhere else are plain text.
- Values are inserted as they are and never read as variables: a clipboard holding `{{date}}` stays that text.

Errors (`PromptIssue.Kind`), each shown under the text in the editor with the variable it's about, and Save stays off until there are none:

| Kind | Example |
|---|---|
| Unknown variable | `{{user}}`, `{{fields:x}}`, `{{{date}}}` |
| Unclosed | `{{date` with no `}}` on the same line |
| Empty | `{{}}` |
| Bad field | `{{field}}`, `{{field:}}`, a label with a brace |
| Too long | a label over 40 or a default over 500 characters |
| Conflicting defaults | `{{field:A\|1}} … {{field:a\|2}}` |
| Too many fields | a 13th field |

The store checks the same before writing (`PromptStore.validate`), so a prompt that can't be filled in is never saved. The editor's chips (Clipboard, Date, Time, Shared text, Field) insert a variable at the cursor; Field inserts `{{field:Label}}` with "Label" (translated) selected to type over.

## Picking one

`ComposerPromptsViewModel.pick` reads only what the prompt uses, then:

1. **No fields, and nothing it needs is empty:** the text goes straight into the box, with the cursor at its end and the keyboard up.
2. **Otherwise** a form opens (`PromptFormSheet`, built from the workflow run sheet's inputs, `WorkflowInputFields`): one box per field, plus a "Clipboard text" box when the prompt uses the clipboard and it was empty or held no text, and a "Text to use" box when it uses `{{selection}}` and nothing was shared or typed. Insert stays off until every box without a default is filled.

What the box holds afterwards (`composerTextAfter`): the prompt replaces a `/` search and the text it took as `{{selection}}`. Any other text already in the box stays, after the prompt and a blank line, so nothing typed is lost.

Each insertion carries a serial and lands once, so it isn't applied again when the screen comes back.

### Clipboard

The clipboard is read only when the user picks a prompt that uses `{{clipboard}}`, at that tap, never while listing or searching. Android 12+ shows its "Locus pasted from your clipboard" toast then; that is expected and is the user's cue. The first item's text is used (HTML is reduced to text); anything else counts as empty. It is cut at 20,000 characters, like selected text shared to Locus.

### Date and time

`{{date}}` and `{{time}}` are text the user inserts into their own message, so they read the way the user does, not the English that model-facing text keeps (docs/design/localization.md, "What stays English"):

- the date is Java's full localized date in the language Locus is shown in (the phone's, unless Settings → Language picks another): "Saturday, November 21, 2026", "sábado, 21 de noviembre de 2026", "2026年11月21日星期六";
- the time follows the phone's 12- or 24-hour setting (`DateFormat.getTimeFormat`);
- both use the phone's time zone.

## Storage

One file, `prompts.json`, in agent-home: `{"schema": 1, "prompts": [{"id", "name", "text", "createdAt", "updatedAt"}]}`, in the user's order. No Room table and no settings key.

- **Why not Room:** a short ordered list read whole and written whole, with no queries and no relations. A schema change would also block other work (one PR at a time may change it).
- **Why not the settings DataStore:** settings are read on every turn; prompts aren't settings.
- **Backup:** agent-home is in every backup, so prompts are too (`PromptsBackupTest`).
- Writes go to a temporary file renamed into place. A file that can't be read is set aside as `prompts.json.unreadable` instead of being overwritten. Unknown fields are ignored and new ones get defaults, so a file from a newer Locus still loads.
- At most 200 prompts, names up to 60 characters, text up to 20,000.
- No agent tool reads or writes the file.

## Prompt cache

A picked prompt only fills the composer; what is sent is an ordinary user message the user typed or edited. The system prompt, the tool list and the cached prefix don't change, and nothing about prompts is sent to the model.

## Starter prompts

None ship. Starters would have to be written in the user's language when first created and then be user content, and the empty states already explain what to do. A later change can add a few generic ones that the user can delete.

## Limits

- Field boxes are text only; the workflow input types (number, choice, boolean) aren't offered for prompts yet.
- Reordering is Move up and Move down, not drag and drop.
- Not yet tried on the phone.
