# Memory search, provenance and "memory read"

How `memory_search` finds topics, how Library shows where a note came from, and how a chat shows which notes the agent read.
Written 2026-11-02. Implements [algorithms #9](../research/agent-algorithms-2026.md#9-ranked-memory-search-with-provenance-s-for-fts5-and-provenance-m-for-embeddings-medium) without embeddings.

## Before

`MemoryStore.searchMemory` had several problems:

- It lower-cased the query, split it on `\W+` and dropped words of two characters or fewer.
- It counted raw substring hits in name, summary and content, and returned the first line containing a word.
- On the JVM, `\W` is ASCII-only, so a Nepali or Japanese query split into nothing and returned nothing.
- Two-character CJK words (東京, 咖啡) were dropped regardless.
- Topic file names came from `AgentHome.slug` (`[^a-z0-9]+` → `-`), so every non-Latin topic name became `note`, and each one overwrote the last.

## Research

| Question | Finding | Source |
|---|---|---|
| Which SQLite runs | The app uses `androidx.sqlite:sqlite-bundled` 2.7.1. Its JVM build (used by the tests) reports **3.50.1** (`2025-06-06 … b77dc5e0`), the same source id as the Android `.so` in [search.md](search.md). It has `ENABLE_FTS5` and `THREADSAFE=2`. | `sqlite_version()`, `sqlite_source_id()`, `pragma_compile_options` from `BundledSQLiteDriver` in a JVM test |
| Trigram tokenizer | Added in SQLite 3.34; its `remove_diacritics` option came in 3.45. The tokenizer is part of FTS5, so any FTS5 build has it. Checked: `CREATE VIRTUAL TABLE … tokenize='trigram remove_diacritics 1'` works on the bundled build. `"東京に"` matches inside `私は東京に住んでいます`, `"पाल"` matches inside `नेपालमा`, and `"cafe"` matches `Café`. | [FTS5 §4.3.5](https://www.sqlite.org/fts5.html#the_trigram_tokenizer), `MemorySearchTest` |
| Short substrings | Trigram MATCH needs at least 3 characters. `LIKE '%京%'` on a trigram table still works: shorter patterns fall back to a scan of the table, which is fine for a personal memory of hundreds of notes. | FTS5 §4.3.5, probe |
| unicode61 on Devanagari | Letters, digits **and marks** make tokens, so vowel signs (U+093E…, Mc/Mn) stay inside words. `remove_diacritics` only folds Latin diacritics. `"नेपाल"*` finds `नेपालमा`. A CJK run stays one token, so `"東京"*` does **not** find `私は東京に…`. That is why the trigram table is there. | FTS5 §4.3.1, probe |
| Ranking | `bm25(t, w1, w2, …)` takes per-column weights; lower is better. | [FTS5 §5.1.1](https://www.sqlite.org/fts5.html#the_bm25_function) |
| Merging two indexes | Reciprocal rank fusion, Σ w / (k + rank) with k = 60, needs no score calibration between lists and beats the individual rankers. | Cormack, Clarke, Büttcher, "Reciprocal Rank Fusion outperforms Condorcet and individual rank learning methods", SIGIR 2009 |

## Decisions

- **Its own SQLite file per memory, not a Room table.** The notes are files in agent-home, and the index is derived from them.
  - Global memory and each project's memory get a file in `noBackupFilesDir/memory-index/` (`memory.db`, `projects_<id>_memory.db`).
  - Backups (which zip agent-home) never carry a live database. A restore just makes the next search re-index.
  - There is no Room migration; the database stays at v17.
  - `core/agent` depends only on the `androidx.sqlite` driver API. The app passes `BundledSQLiteDriver`, so FTS5 is there on every Android version (see search.md). The tests use the same bundled build with desktop natives, so they run the engine that ships.
- **Two FTS5 tables over the same rows**, keyed by a `topic` table (`name`, `fingerprint`):
  - `words`: `fts5(name, summary, content, tokenize='unicode61 remove_diacritics 2')`, ranked `bm25(words, 10, 4, 1)`. The topic name counts most, then its index line, then the note.
  - `grams`: the same columns with `tokenize='trigram remove_diacritics 1'`.
- **Queries are built, never passed through.** `MemoryQuery` splits the input the way unicode61 does (letters, digits and marks of any script, after NFC), lower-cases it, keeps at most 16 words, and quotes each one. From those words:
  - `words` gets every word as a prefix, joined with OR. Single Latin letters are dropped; single CJK characters are kept. Two-letter words like `go` and `JS` count now.
  - `grams` gets every word of 3 or more characters, matched anywhere.
  - Words of 1–2 characters in scripts without spaces (anything non-Latin) are also matched with `LIKE '%w%'` on `grams`. They hold only letters and marks, so no `%` or `_` needs escaping.
- **Fusion.** Each list is weighted: `words` 1.0, trigram and substring matches 0.6. Infix matches are noisier in spaced scripts ("art" in "start"), so a whole-word hit always outranks one. Recall is the goal: the model reads the top 8 and picks.
- **Snippets.** `snippet(…, '**', '**', '…', 12)` comes from the first list that matched. Substring-only hits get the first line that contains a query word.
- **Output for the model**:
  ```
  - coffee — How the user takes coffee
    Flat white with **oat** milk, no sugar.
  - home-server (general memory) — Pi 5 on the LAN
  ```
  Topic lines start at the margin; snippet lines are indented. The "memory read" extraction relies on that (below).
- **Sync.** The index stays current in four ways:
  - `saveMemory` (the agent, the after-task reflection, and Library's Edit) re-indexes the topic.
  - `forgetMemory` removes it.
  - Before every search, `syncIndex` compares each file's `lastModified:length:hash(summary)` with the stored fingerprint, re-reads what changed and drops what's gone. This is one directory listing, and it covers restores and files changed outside Locus.
  - `LocusApplication.onCreate` calls `openMemoryIndex()` on IO, so a missing index is rebuilt at start.
- **Failure.** Every index call is guarded:
  - Any SQLite error closes and deletes the file, and the search falls back to scanning the notes, with the same word rules and Unicode-correct folding.
  - The next call rebuilds the index.
  - After three failures in a row, the index stays off for the process.
  - A `user_version` other than the current schema means "start over".
- **Topic names.** `AgentHome.memorySlug` keeps letters, digits and marks of every script (NFC, lower case, at most 60 code points, never cutting a surrogate pair), so "नेपाली खाना" is saved as `नेपाली-खाना.md`.
  - For ASCII names it gives exactly what `slug` gave.
  - A note saved under the older name (`caf.md` for "café") is still read, edited and forgotten through that file.
  - Skills, connectors and workflows keep `slug`.

## Provenance in Library

The record has existed since #72 (`memory/.provenance.json`, see [taint](taint.md#memory-provenance)). Library → Memory now maps it with `memoryOriginView`, which is pure and tested:

- **Who**: "Saved by you", "Saved by Locus in a chat" (or "Saved by Locus"), or "Saved by Locus after a task" (reflection).
- **When**: a locale date and time ("2 Nov 2026, 14:05"), beside the note's relative modified time.
- **Where**: "From “chat title”". Tapping it opens the chat. A hidden helper chat links to the chat that started it. A deleted chat is named as deleted, not linked. Titles come from `ConversationDao` in `LibraryRepository` (`Library.memoryChats`).
- **Warning**: "Saved while reading outside content (a web page, an email…)". Tool names are mapped to kinds a person recognises, and the note asks the user to check it and then Edit and Save to confirm it.
- **Collapsed row tag**: "· by Locus", "· after a task", plus "· check" for a tainted note.

## Memory read in the chat

- The steps card computes `MemoryReads.inCalls` from the turn's stored tool calls. A successful `memory_read` contributes its `topic` argument (as a `memorySlug`). A `memory_search` result contributes its topic lines, with "(general memory)" marking topics from global memory in a project chat. Results in the old `- topic: line` format are read too.
- **Nothing is added to the transcript or the request.** The list is derived for display only, so the prompt cache and the model's view are unchanged.
- The list shows under the steps, even when the card is collapsed. Each topic has **Forget**, which opens a sheet with a critical action. Forget removes the topic from the memory the chat uses (`MemoryView.forgetRead`):
  - general memory when the search marked it so;
  - otherwise the chat's own memory first, then general memory, which is the order `memory_read` resolves names in.
- The card asks `memoryStillSaved` when it appears, so a topic already forgotten (here, in Library, or in another chat) says "Forgotten" instead of offering Forget.

## Embeddings (follow-up)

Not built. Hybrid ranking would add a third list (cosine similarity over note embeddings) to the same fusion. What was checked:

| Model | Licence | Size on device | Notes |
|---|---|---|---|
| EmbeddingGemma 300M ([LiteRT build](https://huggingface.co/litert-community/embeddinggemma-300m)) | Gemma Terms of Use. The Hugging Face download is gated behind accepting them. | 179–220 MB `.tflite` (int4/int8 mixed), by sequence length 256–2048 | Google's figures on an S25 Ultra at sequence length 256: CPU (XNNPACK, 4 threads) 66 ms per embedding and 110 MB peak RAM; NPU 7.8 ms. Runs on LiteRT, or through the AI Edge RAG library (Maven). Multilingual (100+ languages). |
| all-MiniLM-L6-v2 / multilingual MiniLM / multilingual-e5-small | Apache-2.0 / Apache-2.0 / MIT | about 23 MB (int8, English-only) to about 120 MB (multilingual) | Need an ONNX or TFLite conversion and their own tokenizer. Not measured here. |

Why not now:

- The useful (multilingual) models are 120–220 MB, too large to bundle in an F-Droid APK.
- EmbeddingGemma's download is gated, so it can't be fetched without the user accepting Google's terms on Hugging Face.
- Locus ships LiteRT-LM (`liblitertlm_jni.so`) for the on-device chat models. It is unverified whether LiteRT-LM can run an embedding `.tflite`, or whether the plain LiteRT interpreter would have to be added as well.

The practical path is an optional download, like the on-device chat models (`core/ondevice`):

1. Embed each topic on save in a background job while charging, and store the vectors in the same index file (a BLOB per topic, keyed by fingerprint).
2. Embed the query at search time, and add the cosine ranking to `MemorySearchIndex.fuse` at weight about 1.0.

A personal memory has hundreds of notes, so brute-force cosine is enough; no vector index is needed.

## Where it lives

| | |
|---|---|
| `core/agent/home/MemoryIndex.kt` | `MemoryQuery` (parsing, folding), `MemorySearchIndex` (SQLite, sync, search, fusion), the scan fallback, snippets |
| `core/agent/home/AgentHome.kt` | `MemoryStore` (sync on save/forget, `syncIndex`, `searchMemory`), `MemoryView.forgetRead/hasRead`, `memorySlug`, one index per memory |
| `core/agent/home/MemoryReads.kt` | the `memory_search` output format and the facts-read extraction |
| `core/data` | `DataModule` (driver, index folder), `LibraryRepository` (`memoryChats`, `forgetMemoryRead`, `memoryStillSaved`) |
| `feature/library/MemoryOrigin.kt` | the provenance display mapping |
| `feature/chat/MemoryReadUi.kt` | the steps card's "Memory read" list and Forget sheet |

Tests: `MemorySearchTest` (ranking, prefixes, infixes, accents, Devanagari, CJK, sync on save/edit/forget, rebuild after deletion or damage, outside changes, project indexes, the scan fallback, legacy names, slugs, fusion), `MemoryReadsTest`, `MemoryReadItemsTest`, `MemoryOriginTest`.
