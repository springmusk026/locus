package dev.locus.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Compaction's token estimate for scripts without spaces, and cuts that don't split a character (docs/design/compaction.md). */
class CompactionCjkTest {
    private fun hasLoneSurrogate(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c) && (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1]))) return true
            if (Character.isLowSurrogate(c)) return true
            i += if (Character.isHighSurrogate(c)) 2 else 1
        }
        return false
    }

    @Test
    fun `Chinese, Japanese and Korean count about a token a character`() {
        // Real tokenizers give 0.7 to 1.5 tokens a character: 1,470 to 3,150 here.
        assertTrue(Compaction.estimateTokens("设置闹钟和提醒。".repeat(300)) in 1_470..3_150, "${Compaction.estimateTokens("设置闹钟和提醒。".repeat(300))}")
        assertEquals(2_100, Compaction.estimateTokens("设".repeat(2_100)))
        assertTrue(Compaction.estimateTokens("こんにちは世界".repeat(300)) in 1_470..3_150)
        assertTrue(Compaction.estimateTokens("안녕하세요".repeat(420)) in 1_470..3_150)
        // Other text is unchanged: four characters a token, rounded up.
        assertEquals(3, Compaction.estimateTokens("abcdefghi"))
        assertEquals(0, Compaction.estimateTokens(""))
        assertEquals(4, Compaction.estimateTokens("a".repeat(16)))
        assertEquals(3, Compaction.estimateTokens("hello 设"))
        // An emoji is several bytes to a tokenizer: a token each.
        assertEquals(100, Compaction.estimateTokens("😀".repeat(100)))
    }

    @Test
    fun `a long Chinese message is cut to the token budget, not to four characters a token`() {
        val kept = Compaction.keep(listOf("设".repeat(5_000)), maxTokens = 1_000).single()
        assertTrue("characters cut" in kept, kept)
        assertTrue(kept.count { it == '设' } in 900..1_000, "${kept.count { it == '设' }}")
        assertTrue(Compaction.estimateTokens(kept) <= 1_020, "${Compaction.estimateTokens(kept)}")
    }

    @Test
    fun `cutting a kept message never leaves half an emoji`() {
        // An odd-length start makes the cut fall inside a pair.
        for (prefix in listOf("a", "ab", "")) {
            for (maxTokens in 20..40) {
                val kept = Compaction.keep(listOf(prefix + "😀".repeat(500)), maxTokens).single()
                assertTrue("characters cut" in kept, kept)
                assertFalse(hasLoneSurrogate(kept), "prefix '$prefix', budget $maxTokens")
            }
        }
        val chinese = Compaction.keep(listOf("𠀀".repeat(500)), maxTokens = 30).single()
        assertFalse(hasLoneSurrogate(chinese))
        assertTrue(chinese.count { Character.isHighSurrogate(it) } <= 30, chinese)
    }

    @Test
    fun `short messages and ASCII cuts are as before`() {
        assertEquals(listOf("hello"), Compaction.keep(listOf("hello"), maxTokens = 10))
        val kept = Compaction.keep(listOf("a".repeat(400)), maxTokens = 50).single()
        assertEquals("a".repeat(100) + "\n…[200 characters cut]…\n" + "a".repeat(100), kept)
    }
}
