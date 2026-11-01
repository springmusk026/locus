# Encrypted backup and restore

Settings → Backup & restore writes one `.locusbackup` file to a place the user picks with the system picker (Storage Access Framework), and restores from one. Locus has no server, and `allowBackup` stays `false`, so Android's cloud backup never sees the data. Code: `core/data/backup`, `core/database/DatabaseBackup.kt`, `feature/settings/Backup*.kt`.

## What's in a backup

| Part | Source | When |
|---|---|---|
| `manifest.json` | App version, schema version, time, and each part's file count and bytes | Always, first entry |
| `database/locus.db` | `VACUUM INTO` snapshot of Room's database | Always |
| `settings/settings.json` | The settings DataStore file | Always (if it exists) |
| `agent-home/…` | Memory, skills, connectors, helpers, persona files, drafts, history | Always |
| `workspace/…` | The workspace folder (symbolic links skipped) | Toggle, size shown |
| `vault/secrets.json` | Every vault entry, decrypted from the Keystore-wrapped store | Second toggle, **off by default** |

Not included: the Linux root filesystem, on-device models, the workspace trash, caches and WebView data. They are large, rebuildable or device-bound.

The vault's own file can't be copied: it's encrypted under a Keystore key that never leaves this phone. With the toggle on, its entries go into the zip and so are encrypted only by the backup password. The UI says so: anyone with the file and the password gets the keys. With it off, a restore keeps the keys already on the phone.

## File format (version 1)

```
"LOCUSBAK"          8 bytes
header length       4 bytes, big-endian, at most 16 KiB
header              UTF-8 JSON:
                    {"format":1,"kdf":"PBKDF2-HMAC-SHA256","iterations":600000,
                     "salt":"<16 bytes, base64>","cipher":"AES256_GCM_HKDF_1MB",
                     "check":"<HMAC-SHA256(master, "locus-backup v1 password check"), base64>"}
ciphertext          Tink Streaming AEAD of the zip, with associated data =
                    every byte before it (magic, length and header)
```

Keys:

- `master = PBKDF2-HMAC-SHA256(P, salt, iterations, 32 bytes)`, where `P` is the password normalised to NFC, UTF-8 encoded and written as lowercase hex. Hex makes every provider feed PBKDF2 the same bytes: providers differ on how they turn `char`s into bytes, and some older Android ones keep only the low 8 bits.
- Stream key: `HMAC-SHA256(master, "locus-backup v1 stream key")`, used as the 32-byte key of `AesGcmHkdfStreaming` (HKDF-SHA256, AES-256 derived keys, 1 MiB ciphertext segments: Tink's `AES256_GCM_HKDF_1MB`).
- Check: `HMAC-SHA256(master, "locus-backup v1 password check")` lets Locus say "wrong password" rather than "damaged". It gives an attacker nothing new: each guess still costs a full PBKDF2 run, the same as trying to decrypt the first segment.

Readers accept 10,000 to 10,000,000 iterations, so a crafted header can't stall the phone. Writers use 600,000.

## Research and choices (checked 2026-10-31)

**Tink has no password-based encryption.** Tink 1.24.0 (the version in `libs.versions.toml`) has no Argon2, scrypt or PBKDF2: the `tink-android-1.24.0.jar` contains no such classes. Tink keys are random keysets. So the key comes from the platform, and Tink does the encryption.

**Key derivation: PBKDF2-HMAC-SHA256 with 600,000 iterations and a 128-bit random salt.**
- OWASP Password Storage Cheat Sheet: "PBKDF2-HMAC-SHA256: 600,000 iterations". It ranks Argon2id (19 MiB, t=2, p=1) and scrypt (N=2^17, r=8, p=1) higher. ([OWASP](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html))
- NIST SP 800-132 §5.1: "the randomly-generated portion of the salt shall be at least 128 bits". §5.2 sets 1,000 iterations as a minimum, and up to 10,000,000 "for especially critical keys". ([NIST SP 800-132](https://nvlpubs.nist.gov/nistpubs/Legacy/SP/nistspecialpublication800-132.pdf))
- Why not Argon2id or scrypt: neither is in Tink or in Android's `javax.crypto`. Either would need a new crypto dependency (for example Bouncy Castle) or native code. PBKDF2WithHmacSHA256 is a standard `SecretKeyFactory` algorithm, provided on Android by Conscrypt; Android's reference lists it from API 26, Locus's minSdk. ([SecretKeyFactory](https://developer.android.com/reference/javax/crypto/SecretKeyFactory); the API-level table couldn't be read automatically on 2026-10-31, so confirm on an API 26 device.) The trade-off is that PBKDF2 isn't memory-hard, so a GPU attacker guesses faster than against Argon2id. The UI asks for at least 8 characters and suggests a few random words. The format records the KDF, so a later version can switch to Argon2id.

**Encryption: Tink Streaming AEAD, `AES256_GCM_HKDF_1MB`.**
- Tink's Streaming AEAD page: it's for data "too large to be processed in a single step", such as large files. Its guarantees: changes are detected, and segments can't be removed, reordered or moved between ciphertexts. ([Tink Streaming AEAD](https://developers.google.com/tink/streaming-aead))
- Tink recommends AES128_GCM_HKDF_1MB as the default and lists AES256_GCM_HKDF_1MB alongside it. The 256-bit variant matches the existing vault (`AES256_GCM`).
- Streaming means a multi-GB workspace never sits in memory or in a plaintext temp file. Each 1 MiB segment is authenticated before any of its plaintext is released. A file that's cut short fails on its last segment.
- Tink's fork-safety note says AES-GCM-HKDF streaming can reuse a nonce if a process is forked or a VM is cloned mid-encryption. ([Tink fork safety](https://developers.google.com/tink/issues/fork-safety)) That doesn't apply here: every backup has a fresh salt, so a fresh key, and Tink adds a random per-stream salt and nonce prefix.

**Database copy: `VACUUM INTO` on Room's writer connection.**
- SQLite: "The VACUUM INTO command is transactional in the sense that the generated output database is a consistent snapshot of the original database." It also purges deleted content, leaving "no forensic traces" in the backup. It was added in 3.27.0. ([VACUUM](https://www.sqlite.org/lang_vacuum.html), [3.27.0 release notes](https://www.sqlite.org/releaselog/3_27_0.html))
- Locus uses the bundled SQLite 3.50.1 on every Android version (docs/design/search.md), so `VACUUM INTO` is always there.
- A plain file copy is unsafe in WAL mode: "If a database file is separated from its WAL file, then transactions that were previously committed to the database might be lost, or the database file might become corrupted." ([WAL](https://www.sqlite.org/wal.html)) The alternative (checkpoint, close Room, copy) would stop the whole app. `VACUUM INTO` needs neither.
- `DatabaseBackup` runs it through Room 3's `useWriterConnection` outside a transaction (VACUUM fails inside one), so no write lands half-way. The snapshot keeps the header's `user_version`, which is Room's schema version.

## Restore

1. **Pick a file.** The header is read first; anything without the magic is "not a Locus backup" before a password is asked.
2. **Password.** PBKDF2, then the check value: a mismatch is "wrong password".
3. **Verify everything.** The zip is decrypted and unpacked into `noBackupFilesDir/restore/staged`:
   - The manifest must be the first entry.
   - Paths must be relative with no `..` (zip-slip).
   - The plaintext is read to the very end so the final segment is authenticated.
   - The database file's SQLite header must carry the manifest's `user_version`.
   - Any failure deletes the stage; nothing current has been touched. The vault, if present, is held in memory only until the user confirms.
4. **Versions.**
   - A newer file format, or a database newer than the app's (`PRAGMA user_version` of the open database), is refused: "update Locus first".
   - An older database is restored as-is. Room's auto-migrations (v1 → current) upgrade it when it next opens, as for any app update.
5. **Preview and confirm.** Created date, app version, database version and each part with its size. The text says what is replaced (database, settings, agent-home; the workspace only if included; keys only if included) and what is kept. Confirming is disabled while a chat, helper or scheduled run is active.
6. **Apply.**
   - The vault (if included) is staged as `staged/vault.enc` by `SecretStore.writeVaultFile`: the vault's own file format (the same Tink keyset and the same associated data, the file name `secrets.enc`), read back once to check. The current vault isn't touched; the staged one is swapped in with everything else. (Until this was fixed the vault was written straight away, before the restart, so a restore that then rolled back had already replaced the keys.)
   - `noBackupFilesDir/restore-reschedule` is written, scheduled-task work is cancelled, a `CONFIRMED` marker is written, and the app restarts (`Intent.makeRestartActivityTask` and process exit). If anything here fails, the staged vault is deleted and the tasks are scheduled again straight away.
   - On the next start, `LocusApplication.attachBaseContext` calls `PendingRestore.apply()` before any ContentProvider, Room or DataStore opens the files. The swap is all-or-nothing; see "Applying a restore" below.
   - A stage without `CONFIRMED` is a cancelled preview and is deleted (so is a `READY` stage left by 0.1.2–0.1.3, which could never apply it and may be long out of date).
   - After Hilt starts, while `restore-reschedule` exists, enabled scheduled tasks are re-registered with WorkManager from whichever database is in place: the restored one, or the old one after a rollback (its tasks were cancelled too). The file is deleted once that works. If it fails, the reason is written into it, the app says so once ("Scheduled tasks are off", with the reason), and the next start tries again.

## Applying a restore

From the user's point of view a restore either happened completely or not at all. Before this, a rename failing part-way left some parts restored and some not, kept `CONFIRMED`, and retried (and failed) on every start without telling anyone.

**Targets.** The database (with its `-wal`, `-shm` and `-journal`, which belong to the old database: left beside the new one, SQLite would replay them into it), the settings file, `agent-home/` and, if the backup has them, `workspace/` and the vault file (`files/datastore/secrets.enc`). A part the backup doesn't contain isn't touched, so a backup without the vault keeps this phone's keys. Every target's old copy goes to `<target>.restore-old`, next to it: the same filesystem as the target (`/data/data/<package>`), so every move is an atomic `rename`.

**Markers** in `noBackupFilesDir/restore/`, each written as a temporary file renamed into place, so it's whole or absent:

| Marker | Meaning |
|---|---|
| `CONFIRMED` | The user confirmed; nothing has been tried yet. |
| `STARTED` | `CONFIRMED` renamed as the swap's first step: an attempt began. |
| `JOURNAL` | Written before any target moves. One line per target: its name and whether it existed then. |
| `COMMITTED` | Every staged part is in place. |

**Steps.**

1. Rename `CONFIRMED` to `STARTED`.
2. Write `JOURNAL`.
3. Move every target that exists to `.restore-old` (all of them, before anything moves in).
4. Move every staged part into place. If a rename fails, it's copied instead; a half-made copy is removed by the rollback.
5. Write `COMMITTED`. This is the point of no return.
6. Delete the `.restore-old` copies, then `JOURNAL`, then the staging folder.

**At startup**, the markers are read in this order:

| Found | Meaning | Action |
|---|---|---|
| `COMMITTED` | The restore is in place | Step 6 (again, if it stopped); clear any earlier failure; reschedule tasks |
| `JOURNAL`, no `COMMITTED` | A swap stopped part-way | Roll back, record the failure |
| `STARTED` only | An attempt began, nothing moved | Delete the stage, record the failure |
| `CONFIRMED` only | Confirmed, not tried | Steps 1–6 |
| None | Cancelled preview, or a legacy `READY` stage | Delete the stage |

**Rollback**, per journal line, which makes it safe to repeat after a crash in the middle of it:
- `.restore-old` exists: what's at the target came from the backup (perhaps half of it). Delete it and move the old copy back.
- No `.restore-old` and the target didn't exist: anything there now came from the backup. Delete it.
- No `.restore-old` and the target existed: it was never moved aside. Leave it.

A crash before `COMMITTED` always rolls back rather than finishing: a part may have been half-copied, and the stage may be part-moved, so going back is the only direction that's certain to be correct. After `COMMITTED`, only deletions remain, so they're finished.

**A failure** (an exception in steps 1–5, or a stopped swap found at startup) is rolled back straight away. Then:
- The whole staging folder is deleted, `CONFIRMED` with it, so nothing is retried. The stage isn't kept for a retry: the vault and the verified manifest were only in the old process's memory, so trying again means picking the file and entering the password again, which stages it afresh.
- `noBackupFilesDir/restore-failed` records the time, the reason and whether the rollback completed. It's outside the staging folder, so starting a new restore doesn't erase it.
- If a target can't be put back, only `JOURNAL` is kept: the next start tries the rollback again (never the restore). If Room created an empty database there in the meantime, the rollback replaces it with the old one.

Errors (as opposed to exceptions) aren't caught by `PendingRestore`: they're treated like the process dying, and the next start recovers from the markers.

**Telling the user.** `RestoreNoticeViewModel` (app) reads the record through `BackupManager.restoreFailure()` when the app opens and shows a sheet titled "Restore didn't finish": "Restoring the backup didn't finish; your data and saved keys are as they were before. Try again from Settings → Backup & restore." That's true for the keys because the vault is one of the swap's targets. If the rollback hasn't completed yet, it says so and that Locus will try again at the next start. OK deletes the record. A successful restore deletes it too.

## Tests

`core/data/src/test/.../backup/BackupTest.kt` covers:
- a round trip (backup, then restore, gives the same files, settings, database bytes, vault and timestamps);
- the vault and workspace left out by default;
- a wrong password, and passwords with composed or decomposed accents;
- a changed ciphertext byte, a cut-short file and a re-encoded header are rejected;
- a newer schema is refused and an older one accepted; a newer format is refused; the manifest must match the database;
- unsafe zip paths, and an unconfirmed stage that is discarded.

`PendingRestoreTest.kt` covers the real paths from `forApp` and a legacy `READY` stage. `RestoreSwapTest.kt` covers the swap, with a fault-injecting `RestoreFs`, on a phone with every target (and a WAL and shared memory) and on one without settings or a workspace:
- a failure at each step before `COMMITTED` leaves exactly the files from before, deletes the stage and records the failure; one after it still ends restored;
- a simulated process death at each step recovers at the next start (rolled back before `COMMITTED`, finished after), and the start after that changes nothing;
- a process death at each step of a rollback is rolled back again;
- a rollback that can't finish keeps only the journal, and the next start completes it;
- the vault swapped in on commit, put back (or removed, if the phone had none) on a rollback, and left alone by a backup without it;
- the reschedule record staying until tasks are scheduled, whether the restore applied or rolled back, and its reason cleared when seen;
- the copy fallback, and the failure record being set, cleared when seen and cleared by a later successful restore.

`core/datastore/src/test/.../VaultFileTest.kt` checks, with a real Tink AES256-GCM keyset, that a vault file written by `writeVaultFile` is read by a DataStore with the vault's serializer once it's moved into place, and that it can't be read under another file name or keyset.
