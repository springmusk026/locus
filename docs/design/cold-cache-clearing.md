# Clearing old tool results when the cache is cold

Status: built 2026-11-21. *Not yet tried on the phone or against a live key.* Code: `ColdClearing` (core/agent), applied in `AgentLoop` (each step's request), `Compaction.request` (the summary request), `sideQuestionRequest` (core/data `SideQuestions.kt`) and the reflection pass; the setting is `AppSettings.clearColdToolResults` (Settings → Agent → Long chats). Tests: `ColdClearingTest` (core/agent), `SentSheetTest` and `SheetScreenshots.sentCleared` (feature/chat), `SettingsViewModelTest` (feature/settings).

## Why

A long chat carries every tool result it ever got: file reads, command output, web pages. While the provider's prompt cache is warm that is cheap (cached input costs a tenth or less), and changing any of it would make the next request pay full price for everything after the change. That is why the roadmap ruled out trimming old output mid-chat.

Once the cache has expired, though, the next request pays full price for the whole prefix anyway. Sending a short note instead of an old 40,000-character log then costs nothing in cache hits and saves those tokens on that request and every later one. Anthropic reports large gains from clearing old tool results in long agentic runs ([context management](https://claude.com/blog/context-management)); Claude Code reportedly clears old results after its cache expires (unverified). The research note is [agent algorithms #6](../research/agent-algorithms-2026.md#6-clear-old-tool-results-only-when-the-cache-is-cold-m-medium-high).

## What it does

Only what a request **sends** changes. The store stays append-only, the chat shows every result in full, `chat_history_read` with `include_tool_results` still reads them, and taint, receipts and compaction's checkpoint all read the stored transcript.

It applies to OpenAI-compatible Chat Completions servers (OpenAI, OpenRouter, Gemini, DeepSeek, Groq, Ollama, LM Studio, llama.cpp) and on-device models. It never applies to Anthropic models (see Why), and not yet to the OpenAI Responses API or Sign in with ChatGPT (see [Not done](#not-done-and-unverified)).

### Cold boundaries

A message is a *cold boundary* when it was created more than **24 hours** (`ColdClearing.COLD_AFTER_MS`) after the last assistant reply before it. Only the first message after each such gap counts (the rest, up to the next reply, are past the same gap).

- The gap is measured from the previous reply's `createdAt`, which is when its generation ended. A provider's cache lifetime runs from the start of the request that last read or wrote it, so the real idle time of the cache is always a little longer than the measured gap: the rule errs towards "still warm".
- **Why 24 hours, for every provider it applies to.** OpenAI keeps prompt caches for up to 24 hours (`prompt_cache_retention: "24h"` on models before GPT-5.6; at least 30 minutes on 5.6 and later, [prompt caching](https://developers.openai.com/api/docs/guides/prompt-caching)). DeepSeek-style disk caches can last hours to days. Gemini's implicit caching and local servers keep less. 24 hours is past all of them that Locus talks to. `COLD_AFTER_BY_FORMAT` allows a per-format value; every format uses the default.
- Everything comes from stored timestamps: no new column, no "last request" time to persist, nothing that differs between a running task and a restart.

### What is cleared at a boundary

Boundaries are processed oldest first, and what one clears stays cleared at the later ones. At each boundary, a tool result before it is cleared when all of these hold:

1. it is not among the **last 5** tool results before the boundary (`KEEP_RECENT`; errors and short results count towards the five);
2. it weighs more than **2,000 characters** (`MIN_RESULT_CHARS`), where each image it carries counts as 6,400 characters (`ModelRequirements.IMAGE_TOKENS` × 4);
3. it is not an error (`isError`): the model often needs to see what failed;
4. it is not part of the task in progress at the boundary: results from the boundary's own turn (since the last message that `startsTurn()`, the user's own message or a checkpoint) stay. When the boundary is the user's new message, the task in progress starts there and everything before it is eligible; when it is a tool result that waited a day for approval, nothing of the running task is touched.

A boundary clears only if what it would newly clear saves at least **20,000 tokens** (`MIN_CLEARED_TOKENS`, four characters a token as compaction estimates, after subtracting the notes). Below that it clears nothing: the rewrite isn't worth a smaller saving, and leaving the prefix alone keeps any cache that did survive.

A cleared result keeps its call id, tool name and error flag, so every provider still pairs it with its call. Its content becomes, in English whatever the app's language, with commas every three digits whatever the phone's locale:

```
[Earlier result cleared: 48,210 characters. Run the tool again if you need it.]
[Earlier result cleared: 15,000 characters and 1 image. Run the tool again if you need it.]
```

Its images are dropped, which the note says. A result without images is plain text to every adapter (docs/design/view-image.md: Anthropic and Responses put images inside the result, Chat Completions adds one user message after the tool messages only for results that have images), so the request stays valid in every format.

Messages after the latest boundary are never touched.

### Never on Anthropic

On Anthropic Messages (`ApiFormat.ANTHROPIC_MESSAGES`) the view is always the transcript unchanged.

- On current Claude models (Opus 5.5, Sonnet 5.5, Fable 5.1 and later) thinking blocks are preserved across turns, and the API checks that the history before a thinking block wasn't edited: a client-side change to an earlier `tool_result` invalidates every thinking block after it, which ends in a 400 or in the reasoning being dropped (Anthropic's extended-thinking docs, "preserved thinking").
- Locus already uses Anthropic's server-side compaction there (docs/design/compaction.md), and Anthropic's own `clear_tool_uses` context edit is the server-side route for this, if it's ever wanted.

The format is the **serving** model's, read when each request is built (`ChatModel.format`). After a fallback the request is rebuilt for the backup, so an OpenAI chat falling back to Claude sends Claude every result as stored, and a Claude chat falling back to an OpenAI model gets the cleared view.

OpenRouter's `anthropic/*` models go through Chat Completions, where Locus sends no thinking blocks back (it doesn't replay `reasoning_details`), so the preserved-thinking check doesn't arise and they get the same rule as other Chat Completions models. Gemini's thought signatures belong to the current turn's calls, which are never cleared.

**On-device models** (LiteRT-LM, llama.cpp) get the same rule. Their KV cache lives in memory only; it is reused only while the transcript continues it exactly (`TranscriptPrefix`), and after a day's gap it is long gone. A cleared view makes the first request after the gap rebuild the conversation, which it would have done anyway, and the later ones continue it.

### Where it applies

| Request | Applies | Why |
| --- | --- | --- |
| Each step of a chat's run (`AgentLoop`) | yes | the main saving |
| The summary request at compaction (`Compaction.request`, from the loop and from `AgentRunner.compact`) | yes, with the summarising model's format | it is the next turn's prefix plus one question, and reads the same cache |
| Side questions (`/btw`, `sideQuestionRequest`) | yes, to the stored history only | the question and any "still running" placeholders come after, so the copy is the chat's next step's prefix |
| Reflection after a task | yes | it sends the chat's history with the chat's tools to reuse the chat's cache |
| Helpers (`SubagentEngine`, `PreparedChild.coldClearing`) | yes | a fork's prefix is the parent's transcript; with the same rule it is the same view, and `agent_send` continues a helper days later |
| Title, follow-up suggestions, safety reviewer, onboarding check | no | they send a short excerpt or one message, not the transcript |
| `ModelFit` / fallback fit checks | no | they estimate what a model needs; the uncleared size is the safe overestimate |

### After a compaction checkpoint

A summary of the later part (Summarise from here) keeps the messages before it, so its view counts like a reply
for the gap rule and is never a boundary itself (its summary request read the cache when it was written), and it
keeps the kept part's results cleared as they were last sent (`Part.Checkpoint.clearedResults`); see
[rewind](rewind.md#cold-cache-clearing).

`Compaction.modelContext` already replaces everything before the latest checkpoint with its view, a user message. Clearing runs on that list, so it only ever sees what comes after the checkpoint. The checkpoint view is not an assistant reply, so a gap right after it starts no boundary until a reply follows. The checkpoint itself never contains tool results.

## Byte stability

Each request's view is a pure function of the messages it sends (their roles, timestamps, contents) and of the policy (the setting and the serving format):

- A boundary's decision depends only on the messages before it and the boundary message itself. Appending messages adds boundaries only after the end, and until one appears the view of the existing messages doesn't change. So between two cold gaps every request's messages are a prefix of the next one's, byte for byte, and the cache written by the first request after a gap keeps hitting. `ColdClearingTest` checks this with the real Chat Completions and Responses request builders.
- The view is recomputed from storage on every run and gives the same result after a restart: timestamps are stored as they were created in the run, and nothing else is read.
- The note's text is fixed English with its own digit grouping (`ColdClearing.grouped`), never `String.format` or the locale.

The only request that changes the prefix is the first after a gap, which is cold anyway.

## The setting

Settings → Agent → Long chats → **Clear old tool results after a long pause**, on by default. Turning it off or on changes the view from the next request: in a chat where it clears (or would clear) something, that one request reads the changed part of the prefix without the cache, and later requests hit again. The help text says so ("may then miss the cache once"). It is a plain `AppSettings` field, saved in the settings DataStore file and so in backups.

## What was sent

"What was sent" counts the request's cleared results and the tokens they saved (`ColdClearing.clearedIn`, read back from each note, so it works for every format, on-device too) and shows a row in the request card: "Earlier tool results: 12 cleared · ≈48.2k tokens saved".

Insights isn't changed: it records the tokens each request used (so the saving shows up as smaller requests), and recording the saving itself would need new storage.

## Not done, and unverified

- **Not tried against a live key or on a phone.** The rules are pinned by tests on the request bodies.
- **OpenAI Responses reasoning items.** Locus replays encrypted reasoning items statelessly (`store: false`). OpenAI doesn't document them as bound to the exact earlier input the way Anthropic's thinking blocks are, so editing an earlier `function_call_output` should be accepted, but this hasn't been checked with a live key. Until it is, `policyFor` leaves `OPENAI_RESPONSES` and `CHATGPT` (Sign in with ChatGPT) out, like Anthropic. To turn them on: run a chat on a Responses model past a 25-hour gap with clearing forced, check the request succeeds and reasoning continues, then remove the exclusion and its test.
- **A side question or helper in the gap.** A side question asked 23 hours after the last reply reads (and refreshes) the uncleared prefix; the user's message an hour later is still a boundary, measured from the chat's last reply, and that request misses a cache that was in fact warm. Rare, and costs one cache miss.
- **Keeping cleared results somewhere the model can name.** The research proposal suggested pointing at a saved copy (`.locus/out/…`). Long output is already spilled there when it's written ([tool output](tool-output.md)), and the note tells the model to run the tool again; `chat_history_read` with `include_tool_results` also reaches the original.
- **Clearing at compaction instead of summarising.** Not done: the summary replaces everything anyway.
