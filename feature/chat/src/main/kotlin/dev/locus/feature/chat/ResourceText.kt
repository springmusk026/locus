package dev.locus.feature.chat

import android.content.res.Resources

/**
 * Sentences one after the other, the way the language writes them: "Locus stopped. 2 queued messages
 * weren't sent." In English a space between; Chinese sentences end in "。" and take none.
 */
internal fun Resources.joinSentences(parts: List<String>): String =
    parts.reduceOrNull { all, next -> getString(R.string.chat_sentence_join, all, next) }.orEmpty()
