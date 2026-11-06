# Temporary chats

Status: built 2026-11-06 (comparable-projects research, item 6). Not yet tried on the phone. Code: `Conversation.temporary` (core/model), `ConversationRepository.create(temporary = true)`, `ChatPolicy.temporaryDenial` (core/agent), `TemporaryChats` (core/data), the home composer's + sheet and `TemporaryChatUi.kt` (feature/chat).

A chat for a quick question or something private: it doesn't use or shape memory, it isn't in the chat list, and it's gone when the user leaves it.

## What other assistants do

| | Memory | History and search | Kept | Can be turned into a normal chat |
|---|---|---|---|---|
| ChatGPT Temporary Chat | Not used and not created by default; a "personalized" one may read saved memory but still creates none | Not in history | Copy kept up to 30 days for safety | Yes, saving it makes it a regular chat |
| Claude incognito chat | Not used, not added | Not in history; not found by past-chat search | 30 days on Anthropic's side | No; can't be reopened once closed; not offered inside projects |
| Gemini Temporary Chat | Not used for personalization; Gems and connected apps that need activity are off | Not in Recent chats or activity | Up to 72 hours, to answer and handle feedback | No |

Sources: [ChatGPT Temporary Chat FAQ](https://help.openai.com/en/articles/8914046-temporary-chat-faq); [Claude: using incognito chats](https://support.claude.com/en/articles/12260368) and [Claude memory help](https://support.claude.com/en/articles/11817273-using-claude-s-chat-search-and-memory-to-build-on-previous-context); [Google: Temporary Chats in Gemini](https://blog.google/products/gemini/temporary-chats-privacy-controls/). The server-side retention in each is for the provider's own safety review. Locus has no server, so nothing is kept on its side.

## What Locus does, and why

- **No memory, either way.** The prompt leaves out the memory index and the recent chats. `memory_read`, `memory_search`, `memory_save`, `memory_forget`, `chat_history_search` and `chat_history_read` are refused. So are `skill_create`, `connector_create` and `agent_create`: they teach Locus something every later chat sees. The after-task reflection that saves lessons never runs. This is Claude's choice and ChatGPT's default. ChatGPT's "personalized" variant, which reads memory, is left out: one rule is easier to trust.
- **Persona, custom instructions and skills stay.** `USER.md`, `IDENTITY.md` and custom instructions are settings the user wrote rather than things learned, and skills are capabilities. The chat behaves like any other chat, minus memory.
- **Not listed, not searchable.** It isn't in the chat list, search, launcher shortcuts, Direct Share, the widget or the recent-chats line of other chats' prompts. These are the same places a helper's chat is kept out of.
- **Deleted when the user leaves it.** When the chat screen closes (back, or opening another chat), it's deleted. A task still running finishes first, and then the chat goes; if the task is still running 24 hours after the user left, it's stopped and the chat is deleted. At start-up, temporary chats left over from the last run (the process was killed, updated or restored from a backup) are deleted, except one that a restored screen has open again. Like Claude, it can't be reopened; with no server copy, there's no 30-day or 72-hour window either.
- **Fixed at creation.** "Temporary" is chosen before the chat exists and never changes, like a project or the chat-only flag. There's no "save this chat": that would turn a chat whose prompt says "no memory" into one the user expects to remember (and ChatGPT's own save is the part people find confusing). Forking a temporary chat into a new chat gives another temporary chat. It can't be moved into a project, which would bring in the project's memory, and it isn't offered inside one (as in Claude).
- **Files stay.** What it writes goes to the workspace like any chat's and stays there. The UI says so. Approvals, permissions, chat modes, the reviewer and taint work as usual.
- **Cost stays.** Its requests still count in Insights and in the monthly spend; nothing else of it does.

## Keeping the cache intact

A chat's tool list and system prompt are its cached prefix (docs/design/tool-discovery.md). Both are settled when the chat is created:

- **Same tools.** A temporary chat declares exactly the tools a normal chat with the same creation-time choices declares (`ToolCatalog.forChat` doesn't look at the flag). Memory tools are refused when called, by `ChatPolicy.temporaryDenial` inside `ChatPolicy.denial`, the same run-time refusal that plan mode and switched-off tools use. A helper started by the chat inherits the refusal through `RunScope.restrict`. Three refused steps in a row stop the task (`ToolLoopGuard`), as for any refused tool.
- **The prompt is set once.** `buildPrompt(temporary = true)` builds the snapshot without the memory index (`AgentHome.snapshot(withMemory = false)`), leaves out recent chats, and adds `ChatPolicy.TEMPORARY_PROMPT_NOTE`. Nothing is added mid-chat.

## Storage

No schema change. A temporary chat is a row in `conversations` with `hidden = 1` and no `parentConversationId`. `hidden` already means "left out of the chat list, search and history", for helper chats, which always have a parent. `Conversation.temporary` is `hidden && parentConversationId == null`, and every place that treats a hidden chat as a helper already checks the parent too (SubagentRunner, Library's memory provenance, chat shortcuts). Two DAO queries were added: `ConversationDao.temporaryChats()` and `UsageEventDao.detach()`. The "seen the explanation" flag is a setting (`AppSettings.temporaryChatExplained`).

## Deleting

`TemporaryChats.delete` removes the chat and its helpers' chats:

1. Each assistant reply's usage (tokens, model, time) is copied into `usage_events` with no chat, as `chat` or `helper`. Forked copies are skipped, as Insights does. Side requests already logged for the chat (titles, compaction, the reviewer) are detached from it rather than cascaded away. `UsageLog` also keeps a request whose chat was deleted while it ran, unlinked.
2. Its tool calls are removed from the activity log (`audit_log` has no foreign key, so they would otherwise stay in Activity and Insights' tool counts).
3. The chat row goes, with its messages, branches and tracked file changes (cascade), and its "Allow for this chat" approvals.
4. The long outputs it saved under `.locus/out/` (named in its tool results) are deleted. Other files in the workspace stay.

Who calls it: `ChatViewModel` registers the open screen (`opened`) and its end (`left`, from `onCleared`, so a rotation doesn't count). The application runs `sweep()` 10 seconds after start-up.

## UI

- **Entry point:** the home composer's + sheet has a **Temporary chat** switch above Plan mode. While it's on, the bar shows a **Temporary** pill and the box says "Ask in a temporary chat". It applies to the chat the ask bar starts next and resets afterwards, like the other draft settings.
- **First time:** turning it on the first time replaces the + sheet with **Temporary chat**: no memory; deleted when you leave (a running task finishes first, for up to 24 hours); files stay; same safety, cost still counts. **Got it** closes it; **Start a normal chat instead** turns the switch off. The pill opens the same sheet later.
- **In the chat:** a **Temporary** line under the title (eye-off icon and the word, in the same style as the project line, so the header keeps its height), where a project would be, opens the sheet (without the switch). An empty temporary chat says "A temporary chat" with a one-line summary above the suggestions.

## Not done

- A backup made while a temporary chat exists includes it (the database is copied whole). The start-up sweep deletes it after a restore.
- The chat's browser session and "What was sent" requests stay in memory until the process ends; neither is written to disk.

## Tests

- `TemporaryChatsTest` (core/data): creation (hidden, out of the list, prompt without memory or recent chats, with the note); the tool list and the request's `tools` identical to a normal chat's; memory, memory search and past-chat search refused while files are written; no reflection request; no project; deletion on leave with usage kept unlinked; normal chats untouched; a second open screen; a running task finishing first and the time limit; the start-up sweep; a fork staying temporary.
- `PolicyPrecedenceTest` (core/agent): the refused set, only for temporary chats, and every name a real tool.
- Screenshots: `chat-temporary-empty`, `chat-temporary`, `composer-home-temporary`, `sheet-more-home`, `sheet-temporary-home`, `sheet-temporary-chat`.
