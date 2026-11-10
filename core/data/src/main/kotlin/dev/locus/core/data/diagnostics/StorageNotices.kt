package dev.locus.core.data.diagnostics

import dev.locus.core.data.R
import dev.locus.core.datastore.StoreNotices
import dev.locus.core.linux.EngineText

/**
 * The one-time notices [DiagnosticsRepository.notices] returns, for the screen. They are stored in
 * English as they were written ([StoreNotices]); the known ones are shown in the app's language.
 */
object StorageNotices {
    /** [notice] in the app's language when it's a known one; any other (an older version's) as it was stored. */
    fun text(notice: String): EngineText = when (notice) {
        StoreNotices.VAULT_RESET -> EngineText.Res(R.string.data_notice_vault_reset)
        StoreNotices.SETTINGS_RESET -> EngineText.Res(R.string.data_notice_settings_reset)
        StoreNotices.KEYS_RECOVERED -> EngineText.Res(R.string.data_notice_keys_recovered)
        else -> EngineText.Plain(notice)
    }

    /** Whether [text] translates [notice]; each known notice ends with Settings → About & privacy → Diagnostics. */
    fun isKnown(notice: String): Boolean = text(notice) !is EngineText.Plain
}
