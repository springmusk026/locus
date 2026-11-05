package dev.locus.core.data.backup

import java.io.File

/**
 * For tests of the archive itself, whose databases are header-only fakes (the magic and
 * `user_version`) that real SQLite rightly rejects. RestoredDatabaseCheckTest runs the real check.
 */
internal val NO_DATABASE_CHECK: (File) -> List<String> = { emptyList() }
