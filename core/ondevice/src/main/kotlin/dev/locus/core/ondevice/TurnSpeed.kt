package dev.locus.core.ondevice

/**
 * How fast one on-device reply was produced. [measured] is true when the counts came from LiteRT-LM's
 * own benchmark info; otherwise they are estimates (decode from the number of streamed pieces,
 * prefill from the KV-cache token count) and the UI marks them with "≈".
 */
data class TurnSpeed(
    val backend: Accelerator,
    val prefillTokens: Int?,
    val prefillPerSecond: Double?,
    val decodeTokens: Int?,
    val decodePerSecond: Double?,
    val measured: Boolean,
)

object SpeedMath {

    /** Tokens per second, or null when there is nothing meaningful to divide. */
    fun perSecond(tokens: Int, millis: Long): Double? =
        if (tokens <= 0 || millis <= 0) null else tokens * 1000.0 / millis

    /**
     * From LiteRT-LM's benchmark info, when the runtime filled it in (all-zero means it didn't).
     * Returns null if there is no decode figure, so the caller falls back to an estimate.
     */
    fun fromRuntime(
        backend: Accelerator,
        prefillTokens: Int,
        prefillPerSecond: Double,
        decodeTokens: Int,
        decodePerSecond: Double,
    ): TurnSpeed? {
        if (decodeTokens <= 0 || decodePerSecond <= 0.0 || decodePerSecond.isNaN() || decodePerSecond.isInfinite()) return null
        return TurnSpeed(
            backend = backend,
            prefillTokens = prefillTokens.takeIf { it > 0 },
            prefillPerSecond = prefillPerSecond.takeIf { it > 0.0 && it.isFinite() },
            decodeTokens = decodeTokens,
            decodePerSecond = decodePerSecond,
            measured = true,
        )
    }

    /**
     * Estimated from the stream. Decode: the pieces after the first one, over the time after the first
     * one arrived (LiteRT-LM streams about one token per piece, but may batch some, e.g. tool calls).
     * Prefill: the KV-cache tokens this turn added minus the decoded pieces, over the time to the
     * first piece. Either is null when the inputs don't support it.
     */
    fun estimate(
        backend: Accelerator,
        pieces: Int,
        firstPieceMs: Long,
        totalMs: Long,
        kvBefore: Int?,
        kvAfter: Int?,
    ): TurnSpeed {
        val decodeRate = if (pieces >= MIN_PIECES && firstPieceMs >= 0) perSecond(pieces - 1, totalMs - firstPieceMs) else null
        val prefillTokens = if (kvBefore != null && kvAfter != null) (kvAfter - kvBefore - pieces).takeIf { it > 0 } else null
        val prefillRate = prefillTokens?.let { if (firstPieceMs > 0) perSecond(it, firstPieceMs) else null }
        return TurnSpeed(
            backend = backend,
            prefillTokens = prefillTokens,
            prefillPerSecond = prefillRate,
            decodeTokens = pieces.takeIf { it > 0 },
            decodePerSecond = decodeRate,
            measured = false,
        )
    }

    /** Too few pieces give a meaningless rate (a one-word reply, a single batched tool call). */
    const val MIN_PIECES = 4
}
