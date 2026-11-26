package dev.locus.core.agent

import dev.locus.core.model.ToolSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How find_tools splits text into terms (docs/design/tool-discovery.md). */
class ToolIndexTermsTest {
    private fun spec(name: String, description: String) = ToolSpec(name, description, ObjectSchema().build())

    @Test
    fun `a camelCase tool is found by its words`() {
        val index = ToolIndex(
            listOf(
                spec("createIssue", "Opens a ticket in the tracker."),
                spec("listPullRequests", "Shows the code changes waiting for review."),
                spec("list_files", "Lists the files in a folder."),
            ),
        )
        assertEquals("createIssue", index.search("create issue", 3).first().name)
        assertEquals("listPullRequests", index.search("list pull requests", 3).first().name)
        assertEquals("listPullRequests", index.search("pull request", 3).first().name)
        // The name as written, and the name in one lower-case word, still find it.
        assertEquals("createIssue", index.search("createIssue", 3).first().name)
        assertEquals("createIssue", index.search("createissue", 3).first().name)
    }

    @Test
    fun `case changes split words, an acronym stays whole, and the joined word is kept too`() {
        assertEquals(listOf("html", "parser", "htmlparser"), ToolIndex.terms("HTMLParser"))
        assertEquals(listOf("oauth2", "token", "oauth2token"), ToolIndex.terms("oauth2Token"))
        assertEquals(listOf("url"), ToolIndex.terms("URL"))
        assertEquals(listOf("send", "email"), ToolIndex.terms("send_email"))
    }

    @Test
    fun `Chinese, Japanese, Korean and Thai are matched by pairs of characters`() {
        val index = ToolIndex(
            listOf(
                spec("device_alarm", "设置闹钟和提醒"),
                spec("device_note", "记录一条笔记"),
                spec("list_files", "Lists the files in a folder."),
            ),
        )
        assertEquals(listOf("device_alarm"), index.search("闹钟", 3).map { it.name })
        assertEquals("device_alarm", index.search("设置闹钟", 3).first().name)
        assertEquals(listOf("设置", "置闹", "闹钟", "钟和", "和提", "提醒"), ToolIndex.terms("设置闹钟和提醒"))
        // A run inside a spaced sentence, with the spaced words around it.
        assertEquals(listOf("set", "闹钟", "alarm"), ToolIndex.terms("set 闹钟 alarm"))
        assertEquals(listOf("เส", "สี", "ีย", "ยง"), ToolIndex.terms("เสียง").take(4))
        val japanese = ToolIndex(listOf(spec("tokyo_weather", "東京の天気を調べる"), spec("list_files", "Lists the files.")))
        assertEquals("tokyo_weather", japanese.search("東京", 2).first().name)
        val korean = ToolIndex(listOf(spec("seoul_weather", "서울의 날씨를 알려줍니다"), spec("list_files", "Lists the files.")))
        assertEquals("seoul_weather", korean.search("날씨", 2).first().name)
    }

    @Test
    fun `endings are trimmed so a word meets its forms`() {
        assertEquals(ToolIndex.terms("run"), ToolIndex.terms("running"))
        assertEquals(ToolIndex.terms("set"), ToolIndex.terms("setting"))
        assertEquals(ToolIndex.terms("set"), ToolIndex.terms("settings"))
        assertEquals(ToolIndex.terms("add"), ToolIndex.terms("adding"))
        assertEquals(ToolIndex.terms("deploy"), ToolIndex.terms("deploying"))
        // The -ing of "string" isn't an ending.
        assertEquals(ToolIndex.terms("string"), ToolIndex.terms("strings"))
        assertTrue(ToolIndex.terms("string").single().startsWith("string"))
    }
}
