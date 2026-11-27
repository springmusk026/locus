# Full-text search over chats

Search finds chats by what was said in them: home → search, and the agent's `chat_history_search`.
Written 2026-10-31 for schema v8.

## Before

Search was a `LIKE '%…%'` over `conversations.title` and `messages.partsJson`. The JSON was matched
after a `"text":"` opening so keys didn't match, but tool arguments and results carry `text` fields
too, reasoning and notes for the model were searched, nothing used an index, `kotlin script` only
matched that exact substring, and `chat_history_search` then decoded every found chat's whole history
to cut a snippet.

## Research

| Question | Finding | Source |
|---|---|---|
| Room FTS support | `@Fts3`, `@Fts4` **and `@Fts5`** annotations; options for the tokenizer, external content (`contentEntity`), prefix indexes. The Room 3.0.3 artifact Locus uses has `androidx.room3.Fts5` (`tokenizer`, `tokenizerArgs`, `contentEntity`, `contentRowId`, `prefix`, `detail`, `notIndexed`), checked with `javap` on `room3-common-jvm-3.0.3.jar`. | [Room: defining data, full-text search](https://developer.android.com/training/data-storage/room/defining-data) |
| Is FTS5 on Android's SQLite? | The *platform* SQLite is 3.18 on API 26 (our minSdk) and 3.50 on the newest releases. AOSP's build flags have historically enabled FTS3/FTS4, not FTS5, so FTS5 can't be counted on through `AndroidSQLiteDriver`. Room's docs: "If you use `BundledSQLiteDriver`, Room supports all FTS versions regardless of the Android SDK version." | [android.database.sqlite versions](https://developer.android.com/reference/android/database/sqlite/package-summary), [AOSP external/sqlite Android.bp](https://android.googlesource.com/platform/external/sqlite/+/refs/heads/android10-mainline-networking-release/dist/Android.bp), Room docs above |
| What Locus actually ships | `DatabaseModule` uses `BundledSQLiteDriver` (`androidx.sqlite:sqlite-bundled` 2.7.1). Its `libsqliteJni.so` reports source id `2025-06-06 … b77dc5e0`, i.e. **SQLite 3.50.1**, compiled with `ENABLE_FTS3`, `ENABLE_FTS4`, `ENABLE_FTS5`, `ENABLE_FTS3_PARENTHESIS`; JSON functions are built in since 3.38. So the same engine runs on every Android version Locus supports. | `strings` on the AAR's arm64 `.so` |
| Tokenizer | `unicode61` folds case, and with `remove_diacritics 2` removes diacritics from all Latin characters (`1` misses code points with several marks). Measured on SQLite: letters, digits and marks make tokens; spaces, punctuation, symbols (`+`, `$`), `_` and emoji separate them; a CJK run stays one token. | [FTS5 §4.3.1 unicode61](https://www.sqlite.org/fts5.html) |
| Query syntax and escaping | A string in double quotes is one phrase; an embedded `"` is escaped by doubling it. `"one two thr" *` makes the last token a prefix (the `*` must be **outside** the quotes; inside, the tokenizer eats it). Barewords can't contain punctuation, and `AND`/`OR`/`NOT`/`NEAR` are operators. A bare `*` is a syntax error. | [FTS5 §3.1–3.3](https://www.sqlite.org/fts5.html) |
| Snippets and highlighting | FTS5 has `highlight(t, col, open, close)`, `snippet(t, col, open, close, ellipsis, maxTokens ≤ 64)` and `bm25()`. FTS4 has `snippet()`, `offsets()` and `matchinfo()` but no `highlight()` or built-in ranking. | [FTS5 §5.1](https://www.sqlite.org/fts5.html), [FTS3/4 §7](https://www.sqlite.org/fts3.html) |
| Keeping external content in sync | FTS5 leaves syncing to the app ("triggers"); a row must be removed from the index *before* its content changes, while the old content is still readable; `'rebuild'` re-reads the whole content table; `'integrity-check'` with `rank = 1` compares the index with the content table. Room generates `BEFORE UPDATE/DELETE` triggers (`DELETE FROM fts WHERE rowid = OLD.seq`) and `AFTER UPDATE/INSERT` triggers. | [FTS5 §4.4.3, §6.7, §6.12](https://www.sqlite.org/fts5.html), generated `LocusDatabase_Impl` |
| When Room creates those triggers on upgrade | Not in the AutoMigration: the generated `AutoMigration_7_8` only adds the column and the virtual table. The triggers are created by the open delegate's `onPostMigrate`, after all migrations ran. | generated `LocusDatabase_AutoMigration_7_8_Impl`, `LocusDatabase_Impl` |

Checked on SQLite (the system `sqlite3`, 3.54) with Room's exact DDL and triggers: inserts, an
`UPDATE`, and an `ON DELETE CASCADE` from `conversations` all leave `integrity-check` (rank 1) clean;
`"kotl"*` finds "Kotlin", `"cafe" "sao"` finds "Café … São", `"say ""hi"""` parses, and an empty
phrase (`"..."`, `"..." *`) returns nothing instead of failing.

## Decisions

- **FTS5, not FTS4.** The bundled driver makes it available everywhere, Room 3 supports it, and it
  gives `highlight()`/`snippet()`, proper string escaping and `bm25()` if we ever rank. FTS4 would work
  too (it has `snippet()`), but its query syntax has no escape for `"` inside a phrase.
- **Index a derived plain-text column, not `partsJson`.** `messages.searchText` holds what a person
  would search, computed when the row is written (`SearchText.of`):
  - user messages: names of attached files, then the typed text without Locus's notes for the model
    (`<locus_mode>`, `<locus_result>`);
  - assistant replies: their text;
  - nothing for tool results, tool calls, compaction checkpoints or **reasoning**. Reasoning is left out
    on purpose: it is long, often restates the reply, mentions every option the model considered, and
    is collapsed in the chat, so matches in it would mostly be noise. (It's one line in `SearchText` if
    that turns out wrong.)
  Markdown stays in the stored text (the tokenizer ignores `*`, `#`, `` ` ``); snippets strip it for display.
- **External content.** `message_fts` is `FTS5(searchText, content=messages, content_rowid=seq,
  tokenize='unicode61 remove_diacritics 2')`, declared as a Room `@Fts5` entity with `contentEntity`.
  It stores only the index; the text lives once more than before (`searchText` beside `partsJson`).
  The rowid is `messages.seq`, so joins need no extra column. Messages are append-only, so in practice
  only the insert trigger fires; the delete trigger runs when a chat is deleted (FK cascade).
- **No prefix index** (`prefix=`). A prefix query scans one range of the term b-tree, which is fast at
  personal scale; prefix indexes would grow the index for little gain.
- **Queries are built, never passed through.** `SearchQuery` splits the input the way `unicode61` does
  and quotes every word (`"kotl"*`, with `"` doubled), so FTS operators, `*`, `:`, `^`, parentheses
  and stray quotes are either separators or ordinary words. Words match as prefixes; text in closed
  quotes is an exact phrase; an unclosed quote (still typing) is ignored; all terms must match.
  One-letter words are dropped from message search (they'd match nearly everything); a query of only
  one-letter words searches titles only, and the UI says so.
- **One result per chat, newest match first.** People search their chats to get back to "that
  conversation", so the result is the chat, shown with its newest matching message. Newest first
  rather than bm25: messages are short and similar in length, so bm25 mostly rewards repeating a word;
  recency is what chat apps sort by and what "the chat where we…" usually means; and it matches the
  date-ordered chat list. bm25 is available if this needs revisiting.
- **Titles** are matched in Kotlin with the same rules (case and accents folded, words as prefixes),
  so a chat whose title matches but whose messages don't still appears, dated by its last activity.

## Branches and forks

- **Every branch is indexed and searched.** A regenerated or edited-away message is still something
  the user wrote or read, and branches are "never removed". Results are per chat, so a chat appears
  once however many branches match. When the newest match isn't on the branch the chat shows, the
  result says "On another branch"; the user gets there with the ‹ i/n › switcher.
- **Fork copies are skipped while their source exists.** Forking copies the path into a new chat
  (`copiedFrom` set), so without this every search would find both chats for the shared history. The
  original is the one shown; the fork still appears if something written *after* the fork matches.
  If the source chat is deleted, its copies become findable (`NOT EXISTS` on the source id).
- **Hidden helper chats** (`conversations.hidden`) are excluded in SQL, and again by only accepting
  results for chats in the visible list.

## Migration 7 → 8

An `AutoMigration` with `SearchMigration` as its spec:

1. Room: `ALTER TABLE messages ADD COLUMN searchText TEXT DEFAULT NULL`, `CREATE VIRTUAL TABLE message_fts …`.
2. Spec `onPostMigrate`: `BACKFILL_SEARCH_TEXT` derives `searchText` from `partsJson` with
   `json_each`/`json_extract` (same rules as `SearchText`; rows whose JSON is invalid stay NULL), then
   `INSERT INTO message_fts(message_fts) VALUES('rebuild')`.
3. Room: creates the four sync triggers (after migrations, see above), then validates the schema.

No existing column or row changes apart from the new column. Because the triggers don't exist yet
during step 2, the backfill `UPDATE` doesn't touch the index, and `'rebuild'` builds it once.

Measured on the JVM (`SearchMigrationTest`, system sqlite3): 5,000 messages of ~60 words each
(a third of them tool results) migrate in about 45 ms including process start. The phone will be
slower, but this is far from noticeable at open.

Tests: the migration runs on a database created from the exported `7.json`, and the result is
compared with a fresh `8.json` database (`pragma table_info`, the FTS DDL, the object list); data is
compared before and after; `integrity-check` runs after inserts and cascading deletes; the search SQL
itself (`SEARCH_MESSAGES`, shared by the DAO and the test) is run for branches, helpers and forks. A
test in `core/data` checks that the SQL backfill and `SearchText` produce the same words.

## Where it lives

| | |
|---|---|
| `core/database` | `MessageEntity.searchText`, `MessageFtsEntity`, `SearchMigration`, `MessageDao.search` / `SEARCH_MESSAGES`, `MessageDao.links` |
| `core/data/search` | `SearchText` (what's indexed), `SearchQuery` (parsing, FTS query, title matching), `SearchSnippet` (snippet → text + match ranges), `ChatSearch` (merges index and title hits) |
| `ConversationRepository.search` | the entry point for features and tools |
| `feature/chat` | All chats: results in order, title + highlighted snippet + "You/Locus · when · On another branch" |
| `chat_history_search` | same index; output format unchanged (`- id … · “title” · last active …` and an indented `…snippet…`), now ~40-token snippets from FTS instead of decoding each chat |

## Not done

- Tapping a result opens the chat; it doesn't scroll to the message or switch to its branch.
- CJK text isn't segmented by `unicode61` (a run of kana/kanji is one token), so CJK search matches
  from the start of a run only. The `trigram` tokenizer would fix that at the cost of a larger index
  and no matches for queries under three characters.
- Searching tool output (e.g. a file the agent read) is deliberately out; Activity searches tool calls.

## Activity's queries (#316)

The Activity screen reads `audit_log` through `AuditDao`. Room runs a query again whenever a table it reads is written, and the old single query read `conversations` (for chat titles and helper chats) even with no search text, so every chat write during a run (`updatedAt` moves as text streams) refreshed it, and its `:conversationId IS NULL OR conversationId = :conversationId` couldn't use the `conversationId` index. Now, without search text, `ActivityRepository.observe` picks one of three queries by filter: `observeBrowse` (every chat: the log alone, in `startedAt` order, no sort), `observeBrowseChat` (`conversationId = ?`, through its index) and `observeBrowseChatAndHelpers` (a task receipt's view, the only one that reads `conversations`). With text, `observeFiltered` is used as before and its results are shown at most every 500 ms (`throttleLatest`: the first at once, then the latest). `AuditBrowseSqlTest` checks the plans (`EXPLAIN QUERY PLAN`) on 50,000 rows, that the browse SQL doesn't mention `conversations`, and that it returns what the general query returns.

With text, the search used `observeFiltered`: `LIKE '%x%'` over every row's arguments and result preview, plus chat titles and helper chats read from `conversations`, so it scanned the log and ran again on every chat write. Schema v21 (2026-11-27) replaces it with an index; the rest of this section is that change.

### Research (checked 2026-11-27)

| Question | Finding | Source |
|---|---|---|
| Room's FTS support in the version Locus uses | `libs.versions.toml`: `androidx.room3` 3.0.3. Its `room3-common-jvm-3.0.3.jar` has `@Fts5` (`tokenizer`, `tokenizerArgs`, `contentEntity`, `contentRowId`, `prefix`, `detail`, `notIndexed`) and `FtsOptions.TOKENIZER_TRIGRAM = "trigram"` beside `unicode61`, `porter`, `icu`, `ascii` and `simple` (checked with `javap`). With `contentEntity`, Room generates the four content-sync triggers and creates them after migrations (the generated `LocusDatabase_Impl.onPostMigrate`), as for `message_fts`, and maps the FTS table to its content table for invalidation (`shadowTablesMap["audit_fts"] = "audit_log"`). The release notes: 3.0.0-alpha02 added "FTS5 support to Room via the `@Fts5` annotation", with "new constants for FTS5 tokenizers (`TOKENIZER_ASCII` and `TOKENIZER_TRIGRAM`)"; 3.0.3 is the current stable. | [Room: full-text search](https://developer.android.com/training/data-storage/room/defining-data#fts), [Room 3 release notes](https://developer.android.com/jetpack/androidx/releases/room3), generated code |
| FTS4 or FTS5, framework or bundled SQLite | `DatabaseModule` uses `BundledSQLiteDriver` (`androidx.sqlite:sqlite-bundled` 2.7.1, SQLite 3.50.1 built with FTS5; see the table above), so FTS5 and its `trigram` tokenizer (3.34.0: "Enhanced FTS5 to support trigram indexes") are there on every Android version. The framework SQLite isn't used, and AOSP doesn't promise FTS5. Room: "If you use `BundledSQLiteDriver`, Room supports all FTS versions regardless of the Android SDK version." | [FTS5 §4.3.4 trigram](https://www.sqlite.org/fts5.html#the_trigram_tokenizer), [SQLite 3.34.0 release notes](https://www.sqlite.org/releaselog/3_34_0.html), Room docs above |
| What the tokenizers do with Chinese | `unicode61` keeps a run of Han characters as one token, so `文件` doesn't find `打开文件管理器` (the gap noted under "Not done" for chat search). `trigram` indexes every run of three characters whatever the script, so a quoted phrase of three or more characters matches any substring: measured, `"文件管"` finds `打开文件管理器`. "Substrings consisting of fewer than 3 unicode characters do not match any rows when used with a full-text query" (measured: `"文件"` returns no rows, no error). Phrase queries on a trigram table need `detail=full` ("fts5: phrase queries are not supported (detail!=full)"). | [FTS5 §4.3.4](https://www.sqlite.org/fts5.html#the_trigram_tokenizer), measured with sqlite3 3.54 |
| Case and accents | `trigram` with its default `case_sensitive 0` folds case for all of Unicode; `remove_diacritics` defaults to 0, so accents are kept. SQLite's `LIKE` folds ASCII only ("ÁRBOL" isn't "árbol" to it). So the index finds a superset of what `LIKE` finds, never less. | [FTS5 §4.3.4](https://www.sqlite.org/fts5.html#the_trigram_tokenizer), [LIKE](https://www.sqlite.org/lang_expr.html#like) |
| External content and keeping it in sync | An external-content table stores only the index; the app keeps it in step, and a row's old values must be removed before they change (Room's `BEFORE UPDATE/DELETE` triggers do it). Its rowids must be stable: "The VACUUM command may change the ROWIDs of entries in any tables that do not have an explicit INTEGER PRIMARY KEY", and a backup is a `VACUUM INTO`. `audit_log`'s key was the text `id`, so it needs an INTEGER PRIMARY KEY for the index to point at. | [FTS5 §4.4.3](https://www.sqlite.org/fts5.html#external_content_tables), [VACUUM](https://www.sqlite.org/lang_vacuum.html) |
| Room AutoMigration for that | Refuses it: "New NOT NULL column 'seq' added with no default value specified". A default would give every copied row the same key, so v20 → v21 is a manual `Migration`. | KSP error on this change |
| Backups | A backup is a `VACUUM INTO` copy, shadow tables included; `user_version` comes along. Restoring an older database lets Room migrate it at the next open, so a v20 backup gets the index then; a v21 backup is refused by an older app ("update Locus first"). `quick_check` runs FTS5's own check on every FTS table (SQLite 3.44+), `audit_fts` included. See [backup](backup.md). | [backup.md](backup.md), [virtual tables](https://sqlite.org/vtab.html) |
| Bound list size | Each chat id is bound as one variable; SQLite's default limit is 32,766 since 3.32.0. | [limits](https://www.sqlite.org/limits.html#max_variable_number) |

### Decisions

- **FTS5 with the `trigram` tokenizer, external content.** `audit_fts` is `FTS5(argumentsJson, resultPreview, tokenize='trigram', content=audit_log, content_rowid=seq)`, a Room `@Fts5` entity (`AuditFtsEntity`). Trigrams answer the question Activity asks, "contains this text", including inside a path or a JSON value and in Chinese, which `unicode61` can't. Room creates its sync triggers.
- **Exactly the old results.** The index finds candidates (`audit_fts MATCH '"text"'`, the text as one phrase with `"` doubled); every candidate is then checked with the same `LIKE` as before, so case folding, accents, `%` and `_` behave exactly as they did. Only the matched rows are checked, not the log.
- **Under three characters, row by row.** One or two characters (`文件`, `ab`) have no trigram, so `observeSearchScan` matches them with `LIKE` over the log in time order, as before, but without reading `conversations`. Searching CJK text by a two-character word is common, so this matters: it is as fast as before, not faster.
- **Titles and helpers are looked up first, then passed in.** `observeTitleMatches` (`title LIKE`) and, for a receipt, `observeHelperChats` read `conversations`. Their results go through `distinctUntilChanged`, and only a change starts the search again (`flatMapLatest`); the search query reads `audit_log` and `audit_fts` alone. So a run writing its chat runs those two small queries, not the search. Titles aren't put into the index: they change (a chat is renamed or titled after its first reply), live in another table, and are short; indexing them would tie the index to `conversations` writes again.
- **`audit_log.seq`** INTEGER PRIMARY KEY AUTOINCREMENT, with `id` kept unique (it was the primary key) and an index on `toolName`, so a tool whose label matched is found through it. The candidates are one `UNION` of the index, the tools and the title chats, read by rowid and sorted (a temporary B-tree of the matches, not the log).
- **The 500 ms refresh limit stays.** The search now runs again only when a tool call is recorded, but a run records them in bursts.
- **More than 5,000 chat ids** (title matches plus a chat and its helpers, each bound twice) fall back to the old query, which reads them with a subquery. That doesn't happen in practice, but it can't fail on SQLite's variable limit.

### Cost

Measured with sqlite3 3.54 on a v20 database with 50,000 calls (about 340 bytes of arguments and preview each, 17 MB in all): the migration (copy, indexes, index build) took about 1 s on a laptop; the phone will be slower, once, at the first start after the update. The index takes about 78 MB, roughly 4.5 times the text it covers: trigram indexes are large, since every position starts a token. Previews are capped at 500 characters, but arguments aren't (`write_file` content), so large writes cost the most. A query on 50,000 rows (`"item number 4242 "`) answered in about 6 ms against 12 ms for the `LIKE` scan on the laptop; the difference grows with the log and on slower storage, and the scan no longer reruns on chat writes either way.

### Migration 20 → 21

`ActivitySearchMigration` (manual, registered in `DatabaseModule`): create `_new_audit_log` with `seq`, copy every row ordered by `startedAt` (so `seq` follows time), drop the old table, rename, create the four indexes, create `audit_fts`, then `INSERT INTO audit_fts(audit_fts) VALUES('rebuild')` to index the rows already there. Room then creates the sync triggers and validates the schema against `21.json`.

### Tests

- `ActivitySearchMigrationTest` (sqlite3, the project's migration pattern): rows kept and numbered in time order, old rows found through the index in English, Spanish and Chinese, `integrity-check` clean; the shape matches a fresh v21 database; `id` stays unique; the index follows inserts and a deleted temporary chat; a `VACUUM INTO` snapshot passes `quick_check` and still finds the right rows; v19 → v21 in one go.
- `AuditSearchSqlTest`: on an English, Spanish and Chinese fixture, `AUDIT_SEARCH` (3+ characters) and `AUDIT_SEARCH_SCAN` return exactly what the old `AUDIT_SEARCH_LIKE` returns, for 40 queries (accents, upper-case accented letters, `%`, `_`, quotes, CJK substrings, one and two characters) across every chat, one chat, a chat and its helpers, matched tool labels and the other filters; neither reads `conversations`; on 50,000 rows the plan is `SCAN audit_fts VIRTUAL TABLE`, the `toolName` and `conversationId` indexes and rowid lookups, with no scan of `audit_log`, where the old query walks the whole time index.
- `ActivitySearchTest` (core/data): the repository picks the index for three characters or more and the scan below, passes titles and a receipt's helpers as lists, and twenty chat writes during a search don't start it again, while renaming a chat into the results does.

### Not done

- Two-character CJK words are matched by the scan. A bigram index would cover them at a further size cost.
- The audit log is never pruned, so the index grows with it. Keeping it bounded (an age or size limit for Activity) is a separate decision.
