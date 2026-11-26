package dev.locus.feature.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.locus.core.screenshot.Step
import dev.locus.core.screenshot.screenAfter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A message the user sends (a steer while the agent works) brings the transcript down only when they were at
 * the end; reading further up, they keep their place and get "jump to latest" (#321).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TranscriptFollowTest {
    private val rows = mutableStateOf((1..60).map { "Row $it" })
    private val lastUser: MutableState<String?> = mutableStateOf("u1")
    private lateinit var list: LazyListState
    private lateinit var follower: TranscriptFollower
    private lateinit var scope: CoroutineScope

    private fun transcript() = @androidx.compose.runtime.Composable {
        list = rememberLazyListState()
        scope = rememberCoroutineScope()
        follower = rememberTranscriptFollower(
            list = list,
            ready = true,
            contentVersion = rows.value.size,
            streamLength = { 0 },
            lastUserKey = lastUser.value,
        )
        Box(Modifier.height(400.dp)) {
            LazyColumn(state = list) {
                items(rows.value) { Text(it, Modifier.fillMaxWidth().height(48.dp)) }
            }
        }
    }

    /** The user sends a message: it joins the end of the transcript. */
    private val send = Step.Act {
        rows.value = rows.value + "You: also the receipts"
        lastUser.value = "u2"
    }

    @Test
    fun `sending from far up keeps the place and offers jump to latest`() {
        screenAfter(
            listOf(
                // The user drags back to the start of the chat and lets go there.
                Step.Act { scope.launch { list.scrollToItem(0); follower.onUserScrollSettled() } },
                send,
            ),
            transcript(),
        )
        assertEquals(0, list.firstVisibleItemIndex)
        assertTrue(follower.hasUnseen)
    }

    @Test
    fun `sending from the end keeps following`() {
        screenAfter(listOf(send), transcript())
        assertEquals(rows.value.size - 1, list.layoutInfo.visibleItemsInfo.last().index)
        assertTrue(follower.following)
    }
}
