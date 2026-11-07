# Connected folders

Status: built 2026-11-06. Roadmap: "Connect a folder" under "From the integrations research". Research: integrations item 2, "Connect a folder through SAF".

A connected folder is a folder outside the workspace that the user grants Locus through Android's folder picker. Examples are an Obsidian or Logseq vault, a Syncthing folder, or Documents/Notes. The picker is the Storage Access Framework's `ACTION_OPEN_DOCUMENT_TREE`, and Locus keeps a persisted grant. The agent's existing file tools then work in the folder.

## Paths

A connected folder appears to the agent as `folders/<name>/…`, at the top of the chat's folder.

- **No new tools.** `read_file`, `list_files`, `find_files`, `search_files`, `write_file`, `edit_file`, `multi_edit`, `make_directory`, `copy_path`, `move_path` and `delete_path` accept these paths. The declared tool list stays the same for every chat.
- **Cache impact:** `list_files` gained one sentence in its description ("Folders the user connected … are under folders/<name>/"). That changes the tool definitions once, for every chat, on the update that ships this. After that nothing varies per chat or per folder: which folders exist is only seen through `list_files` results, never in the prompt or the tool list.
- **The prefix is virtual.** `folders/<name>` maps to a connected folder only when `<name>` is a connected folder's name. Any other `folders/…` path is an ordinary workspace path.
  - If the workspace also has a real `folders/<name>`, the connected folder shadows it for the file tools. Linux still sees the real one.
  - Names are `[A-Za-z0-9][A-Za-z0-9._-]{0,39}` and unique ignoring case. They are chosen when connecting and fixed after that, so change records and trash entries stay valid. To rename, disconnect and connect again.
- **No escape.** The file layer (`ConnectedFiles`) is the only place these paths are parsed:
  - Any `..` segment is refused, and so are backslashes and NULs.
  - `Workspace.resolve` refuses any path whose canonical form is a connected folder's path. `folders/../folders/vault` can't sneak past the check through the workspace.
  - In the SAF tree, every document is found by listing its parent's children from the granted tree's own document. The layer never builds a document id from a path, so it can't name anything outside the grant.
- **Every other tool is refused.** `Workspace.resolve` throws a clear message for a connected path. That covers Linux, code tools, `send_file`, browser uploads and everything else. The message names the file tools that work and says `copy_path` brings files into the workspace.

### Projects

A project's root is its own folder, and a vault isn't inside it. So connected folders are global, and a project links to them:

- Chats outside projects see every connected folder.
- A project chat sees only the folders linked to its project (`ConnectedFolder.projectIds`, set with chips in the folder's sheet). Other names give "isn't linked to this project".
- `folders/<name>` works the same in both, at the top of the chat's folder, so a skill or instruction that names `folders/vault/…` works in either.

Why: a project is a scope (docs/design/projects.md). A vault the user connected for their notes shouldn't leak into a coding project unless they say so. Storing the links on the folder, rather than on the project row, keeps Room unchanged.

## Storage

`AppSettings.connectedFolders` holds the list: id, name, tree URI, source label, read-only, hidden writable, project ids, added at.

- **No Room schema change.**
- **Sealed** with the permissions (`PolicySeal`): a folder's read-write switch and project links decide what the agent may change and which chats see it. The payload is added only when the list is non-empty, so existing seals still match.
- **On a broken seal**, folders stay connected (the grant is Android's), but every folder becomes read-only, hidden folders become read-only, and project links are cleared.

## File layer

`DocTree` (core/agent) is the provider interface: `root`, `list`, `open`, `write`, `createFile`, `createDirectory`, `delete`, `rename`, `uriOf`.

- `SafDocTree` (core/data) implements it with `DocumentsContract`. It makes one `buildChildDocumentsUriUsingTree` query per folder, with id, name, MIME type, size, modified and flags. It doesn't use `DocumentFile`, which asks again for each child's name and type.
- Tests use a directory-backed tree (`FakeFolderGrants` in core/testing, and a fake in core/agent's tests).

`ConnectedFiles` (core/agent) adds the rules on top:

- **Performance.** Listings are cached for 3 s (`LISTING_TTL_MS`), at most 256 folders. Document ids found on the way are cached for 3 s in `SafDocTree`.
  - Every change Locus makes drops the cache of the folder it touched.
  - Checks before a write (does it exist, has it changed since the chat read it) always look fresh, so another app's change is never overwritten from a stale listing.
  - `find_files` and `search_files` stop after 5,000 entries (`MAX_WALK`) and say so.
  - Search skips files over 5 MB and binary files.
  - The tools read and write at most 10 MB per file.
  - A deleted folder's trash copy, and a copy across places, stop at 2,000 files or 200 MB.
- **Consistency with other apps** (Syncthing, Obsidian):
  - Edits need a read first, as in the workspace. `FileReads` keys a connected file by `connected:<folder id>/<path>` with its modified time, size and SHA-256.
  - `write_file` over a file that changed since the read is refused.
  - `edit_file` on such a file applies only exact matches.
- **Undo.** Before a change, the earlier version is copied into the workspace trash with `Workspace.keep`. The `TrashEntry` gets a `folderId` and `originalPath = folders/<name>/…`.
  - `ChangeRepository` reads current state through `Workspace.storedItem`, which is the workspace file or the connected item. The changes card, diffs, per-file undo, "changed since" and "read it again" work unchanged.
  - Restoring from Workspace → Trash puts the copy back through the provider. Whatever is there now goes to the trash first.
- **Names SAF or FAT refuses** are refused up front: `" * / : < > ? \ |`, control characters, `.` and `..`, a trailing dot or space, and more than 255 bytes. FAT's character list comes from AOSP `FileUtils.isValidFatFilenameChar`.
  - A provider that answers `createDocument` with a different name means a conflict. It may append " (1)", or a name differing only in case may exist. Locus removes what was made and reports the conflict, so a file is never written under a name the agent didn't ask for.
  - Before creating anything, Locus looks the name up and writes to the existing file, so normal overwrites don't create duplicates.
- **New files** get the MIME type that matches their extension, or `application/octet-stream`. That stops providers from appending an extension.
- **Writes** use mode `"wt"`, falling back to `"w"` for providers that don't know it.

## Approvals and safety

| Action | Rule |
|---|---|
| Reads (`read_file`, `list_files`, `find_files`, `search_files`, copying out) | Allowed by default, like workspace reads. Taint rules are as for the workspace: the content is the user's own private data (`Taint.PRIVATE_READS`), so the disclosure gate applies to addresses built afterwards |
| Writes (`write_file`, `edit_file`, `multi_edit`, `make_directory`, `copy_path` into a folder, `move_path`) | Ask, **even when the tool is set to Allow**: the new `Tool.asksFirst` treats the call as Ask. "Always for this folder" still works through the existing FOLDER scope (the file's folder, e.g. `folders/vault/daily`; a file at the top gives the whole vault). Deny still wins |
| Delete (`delete_path`), moving something out of a folder, or a move that overwrites | Always ask (`mustConfirm`). No remembered rule applies and none is offered. Only the user answers: the safety reviewer may block it or add its note, never approve it (`Tool.reviewerClearance` is `USER_ONLY`) |
| The safety reviewer on other writes | It may approve one only when the user turned on "Let it approve outward-facing actions" (`REVIEWER_IF_OUTWARD_ALLOWED`): these are the user's own files outside Locus. Otherwise its allow becomes a note on the card, which still offers "Always for this folder". `ConnectedFoldersTest.the reviewer clears connected-folder writes only with the outward opt-in…` |
| A read-only folder | Every change is refused in `precheck`, before anyone is asked |
| Hidden files and folders (any segment starting with `.`, e.g. `.obsidian`, `.git`, `.trash`) | Read-only unless the folder's "Edit hidden folders" is on |
| `run_command` | A `cwd` in a connected folder is refused before asking. A command that names `folders/<name>` gets a note in its result: Linux can't see connected folders, use `copy_path` |

- Disconnecting removes every FOLDER rule under `folders/<name>`, so a different folder connected later under the same name doesn't inherit "Always" answers.
- The workspace's own `DocumentsProvider` doesn't expose connected folders. Locus never re-shares a grant to other apps through its provider. Open with… and Share in the viewer hand over the original provider's document URI with a read grant.

## Linux

proot can't mount SAF: a document tree isn't a path. So Linux never sees connected folders. This is said in:

- the connect sheet (an Info banner),
- the empty "Connected folders" note,
- the top bar of a connected folder ("not visible to Linux"),
- `list_files folders`,
- every refusal from `Workspace.resolve`,
- `run_command`'s note.

The way across is `copy_path` into the workspace and back.

## UI

The workspace's top level has a **Connected folders** section under the Linux card:

- **Rows.** Each row has the name, the source ("Phone storage › Documents/Obsidian/Vault") and a status line. Statuses:
  - "Can edit · asks first"
  - "Read-only"
  - "Read-only (storage app)"
  - "Access lost · reconnect it"
  - "Folder not found"
  - "Checking…"
- **Tapping a row** browses the folder with the existing viewers. Files are copied to the cache for viewing, up to 50 MB.
- **Each row's settings button** opens its sheet:
  - "Locus can edit files"
  - "Edit hidden folders"
  - project chips
  - Reconnect, or "Pick the folder again"
  - Disconnect, confirmed in a sheet
- **Connect a folder** opens the system picker, then a naming sheet. The name is suggested from the folder's name, with "Locus finds it at folders/<name>". The sheet also has "Locus can edit files" (off and disabled when the provider only grants reading) and the Linux note.
- **Inside a connected folder:**
  - Import is replaced by the folder's settings.
  - An empty folder has its own empty state.
  - A folder that can't be listed shows an error state with Try again and Reconnect, instead of an empty list.

All of it uses existing design-system components: ListRow, IconTile, StatusLine, AddRow, SectionLabel/Note, LocusSheet, SwitchRow, LocusChip (filter), Banner, ConfirmSheet, CriticalOutlinedButton, ErrorState and EmptyState. No new pattern was needed.

## States handled

| State | What happens |
|---|---|
| Permission revoked (Settings → Apps, cleared data) or the folder moved | `PERMISSION_LOST`: the row says "Access lost · reconnect it". Tools say "Locus no longer has access … reconnect it in Workspace → Connected folders". Reconnect re-picks and keeps the name, rules and trash |
| The folder was deleted, or its SD card removed | `GONE`: "Folder not found", and the tools say it's gone |
| The provider doesn't allow writes | Connected read-only (`takePersistableUriPermission` with write fails, or the top lacks `FLAG_DIR_SUPPORTS_CREATE`). Per-file `FLAG_SUPPORTS_WRITE` and `FLAG_SUPPORTS_DELETE` are checked before writing or deleting |
| Huge folders | Listings are cached and capped (500 entries per `list_files`, 5,000 per walk). Trash copies and copies are capped |
| Non-text files | `read_file` reports binary with its size. Edits refuse binary. Search skips binary. The viewer opens them as usual |
| Filename conflicts, and characters SAF rejects | See "File layer" |
| A provider without tree support | It doesn't appear in the picker. With no picker at all, Locus says "This phone has no folder picker" |
| The same folder picked twice | Refused with its existing name |

## Which providers work (researched 2026-11-06)

| Provider | Tree picker | Writes, create, delete, rename | Notes |
|---|---|---|---|
| Phone storage (ExternalStorageProvider) | Yes, except on Android 11+: the storage root, `Download`, `Android/data` and `Android/obb` can't be picked ([docs](https://developer.android.com/training/data-storage/shared/documents-files)) | All four | Pick a subfolder such as `Documents/Obsidian` |
| SD card / USB (ExternalStorageProvider) | Yes. On 11+ an SD card's root can't be picked when it's a "reliable" volume. USB OTG: unverified | All four | FAT/exFAT refuses `" * / : < > ? \ |` and control characters ([AOSP FileUtils](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/FileUtils.java)). Case-insensitive names (general FAT behaviour, not in Android docs). A card can be removed |
| Nextcloud app | Yes: its `RootCursor` sets `FLAG_SUPPORTS_IS_CHILD` ([source](https://github.com/nextcloud/android/blob/master/app/src/main/java/org/nextcloud/providers/cursors/RootCursor.kt)) | Create, delete, rename, copy and move ([provider](https://github.com/nextcloud/android/blob/master/app/src/main/java/com/owncloud/android/providers/documentstorage/DocumentsStorageProvider.kt)) | Every call is a server round trip, so it's slow and fails offline. `renameDocument` keeps the id. A delete it refuses can fail silently (the next listing shows the file again) |
| Google Drive | **No**: only single files (`OPEN_DOCUMENT`) ([issue 135636079](https://issuetracker.google.com/issues/135636079)) | — | Use Drive's own sync, or a synced local folder |
| Dropbox | **No** SAF tree support (Dropbox forum, old threads; current state unverified) | — | |
| ownCloud | Likely no: its root flags lack `IS_CHILD` ([source](https://github.com/owncloud/android/blob/master/owncloudApp/src/main/java/com/owncloud/android/presentation/documentsprovider/cursors/RootCursor.kt)); not tried on a device | — | |
| OneDrive, Proton Drive | Unverified | Unverified | |
| Termux | Yes (`$HOME`) ([source](https://github.com/termux/termux-app/blob/master/app/src/main/java/com/termux/filepicker/TermuxDocumentsProvider.java)) | Write, create and delete. **No rename** (Locus reports "doesn't support this") | Collisions get " (2)" |
| Syncthing(-Fork) | Not a provider | — | Connect its local folder through phone storage |
| Obsidian | Not a provider | — | Vaults on "device storage" live in `Documents/Obsidian/<vault>`, which can be picked. "App storage" vaults in `Android/data/md.obsidian/` can't be picked on Android 11+ |

Other facts:

- Android keeps at most 128 persisted grants before Android 11 and 512 from 11 on ([Google, issue 149315521](https://issuetracker.google.com/issues/149315521); [CommonsWare](https://commonsware.com/blog/2020/06/13/count-your-saf-uri-permission-grants.html)). Each connected folder uses one, and Disconnect releases it.
- `renameDocument` may return a new URI ([reference](https://developer.android.com/reference/android/provider/DocumentsContract#renameDocument)). `SafDocTree` drops its id cache after every change.

## Not done yet

- Opening the changed note in Obsidian after a write (`obsidian://open`). Whether Obsidian handles these URIs on Android is unverified.
- A small full-text index for large vaults. Search walks and reads files today.
- Marking a user-chosen "clippings" subfolder as untrusted (web clippings inside a vault).
- Tried on a real phone with a real vault, SD card and Nextcloud: not yet. See the PR's device checklist.
