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
