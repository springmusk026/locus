package dev.locus.core.designsystem

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UiTextTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun plainIsItsValue() {
        assertEquals("notes/today.md", UiText.Plain("notes/today.md").asString(context))
    }

    @Test
    fun resourceWithoutArgumentsComesFromTheContext() {
        assertEquals(context.getString(android.R.string.ok), UiText.Res(android.R.string.ok).asString(context))
    }

    @Test
    fun resourceArgumentsAreFormattedInPlace() {
        assertEquals("Saved notes.md to inbox", UiText.Res(SAVED, "notes.md", "inbox").asString(fake))
    }

    @Test
    fun argumentsThatAreUiTextResolveFirst() {
        val inner = UiText.Res(SAVED, "a.txt", UiText.Plain("Downloads"))
        val outer = UiText.Res(FAILED, inner)
        assertEquals("Couldn't finish: Saved a.txt to Downloads", outer.asString(fake))
    }

    @Test
    fun pluralPicksTheFormForTheCount() {
        assertEquals("1 file", UiText.Plural(FILES, 1).asString(fake))
        assertEquals("3 files", UiText.Plural(FILES, 3).asString(fake))
    }

    @Test
    fun pluralWithArgumentsUsesThemInsteadOfTheCount() {
        assertEquals("2 files in inbox", UiText.Plural(FILES_IN, 2, 2, UiText.Plain("inbox")).asString(fake))
    }

    @Test
    fun equalWhenTheIdAndArgumentsAre() {
        assertEquals(UiText.Res(SAVED, "a", 1), UiText.Res(SAVED, listOf("a", 1)))
        assertNotEquals(UiText.Res(SAVED, "a", 1), UiText.Res(SAVED, "a", 2))
        assertEquals(UiText.Plural(FILES, 2), UiText.Plural(FILES, 2))
        assertNotEquals(UiText.Plural(FILES, 2), UiText.Plural(FILES, 3))
    }

    /** Resources with a few made-up ids: the module has no strings of its own to test with. */
    private val fake = object : Resources(context.resources.assets, context.resources.displayMetrics, context.resources.configuration) {
        private val strings = mapOf(SAVED to "Saved %1\$s to %2\$s", FAILED to "Couldn't finish: %1\$s")

        override fun getString(id: Int): String = strings[id] ?: throw NotFoundException("$id")

        override fun getString(id: Int, vararg formatArgs: Any?): String = String.format(Locale.ROOT, getString(id), *formatArgs)

        override fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any?): String {
            val pattern = when (id) {
                FILES -> if (quantity == 1) "%1\$d file" else "%1\$d files"
                FILES_IN -> if (quantity == 1) "%1\$d file in %2\$s" else "%1\$d files in %2\$s"
                else -> throw NotFoundException("$id")
            }
            return String.format(Locale.ROOT, pattern, *formatArgs)
        }
    }

    private companion object {
        const val SAVED = 0x7f990001
        const val FAILED = 0x7f990002
        const val FILES = 0x7f990003
        const val FILES_IN = 0x7f990004
    }
}
