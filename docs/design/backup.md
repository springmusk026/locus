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
   - Any failure deletes the stage; nothing current has been touched. The vault, if present, is held in memory only.
4. **Versions.**
   - A newer file format, or a database newer than the app's (`PRAGMA user_version` of the open database), is refused: "update Locus first".
   - An older database is restored as-is. Room's auto-migrations (v1 → current) upgrade it when it next opens, as for any app update.
5. **Preview and confirm.** Created date, app version, database version and each part with its size. The text says what is replaced (database, settings, agent-home; the workspace only if included; keys only if included) and what is kept. Confirming is disabled while a chat, helper or scheduled run is active.
6. **Apply.**
   - The vault (if included) is written through `SecretStore`.
   - Scheduled-task work is cancelled, a `CONFIRMED` marker is written, and the app restarts (`Intent.makeRestartActivityTask` and process exit).
   - On the next start, `LocusApplication.attachBaseContext` calls `PendingRestore.apply()` before any ContentProvider, Room or DataStore opens the files. Each part is moved aside and the staged one renamed into place. The old database's `-wal` and `-shm` are deleted first, or SQLite would replay them.
   - A stage without `CONFIRMED` is a cancelled preview and is deleted (so is a `READY` stage left by 0.1.2–0.1.3, which could never apply it and may be long out of date). Every step can be repeated, so a crash mid-way finishes on the next start.
   - After Hilt starts, enabled scheduled tasks are re-registered with WorkManager from the restored database.

## Tests

`core/data/src/test/.../backup/BackupTest.kt` covers:
- a round trip (backup, then restore, gives the same files, settings, database bytes, vault and timestamps);
- the vault and workspace left out by default;
- a wrong password, and passwords with composed or decomposed accents;
- a changed ciphertext byte, a cut-short file and a re-encoded header are rejected;
- a newer schema is refused and an older one accepted; a newer format is refused; the manifest must match the database;
- unsafe zip paths, and an unconfirmed stage that is discarded.
