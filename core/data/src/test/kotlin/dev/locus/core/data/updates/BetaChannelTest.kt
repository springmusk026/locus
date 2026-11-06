package dev.locus.core.data.updates

import dev.locus.core.testing.TestGraph
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The beta channel (docs/design/updates.md, "Beta channel"): which release each channel picks, and the
 * check over a scripted GitHub. The installed build is 0.1.4 ([dev.locus.core.testing.FakeUpdatePlatform.RELEASE_BUILD]).
 */
class BetaChannelTest {
    private fun release(version: String, prerelease: Boolean = false, draft: Boolean = false) =
        Release(tag = "v$version", prerelease = prerelease, draft = draft)

    // ---- Picking ----

    @Test fun `stable only - pre-releases are left out, the newest full release wins whatever the order`() {
        val list = listOf(release("0.1.6"), release("0.2.0-beta.1", prerelease = true), release("0.1.10"), release("0.1.9"))
        assertEquals("0.1.10", ReleaseChannel.newest(list, beta = false)?.version)
        assertTrue(ReleaseChannel.offers(release("0.1.10"), "0.1.4", beta = false))
        assertFalse(ReleaseChannel.offers(release("0.2.0-beta.1", prerelease = true), "0.1.4", beta = false))
    }

    @Test fun `beta newer than stable - the beta is picked and offered`() {
        val list = listOf(release("0.1.5"), release("0.2.0-beta.2", prerelease = true), release("0.2.0-beta.10", prerelease = true))
        val picked = ReleaseChannel.newest(list, beta = true)
        // By semantic version, not by text: beta.10 is after beta.2.
        assertEquals("0.2.0-beta.10", picked?.version)
        assertTrue(ReleaseChannel.offers(picked!!, "0.1.4", beta = true))
    }

    @Test fun `beta older than stable - the stable release is picked`() {
        val list = listOf(release("0.2.0-beta.3", prerelease = true), release("0.2.0"), release("0.1.9"))
        assertEquals("0.2.0", ReleaseChannel.newest(list, beta = true)?.version)
    }

    @Test fun `a draft is ignored on both channels`() {
        val list = listOf(release("0.9.0", draft = true), release("0.9.1-beta.1", prerelease = true, draft = true), release("0.1.5"))
        assertEquals("0.1.5", ReleaseChannel.newest(list, beta = true)?.version)
        assertEquals("0.1.5", ReleaseChannel.newest(list, beta = false)?.version)
        assertFalse(ReleaseChannel.offers(release("0.9.0", draft = true), "0.1.4", beta = true))
    }

    @Test fun `never a downgrade - a release not newer than the one installed isn't offered`() {
        // On a beta, back on stable: the stable release before it isn't offered...
        assertFalse(ReleaseChannel.offers(release("0.1.9"), "0.2.0-beta.3", beta = false))
        // ...nor an older beta, nor the same version; the full release of that beta is.
        assertFalse(ReleaseChannel.offers(release("0.2.0-beta.2", prerelease = true), "0.2.0-beta.3", beta = true))
        assertFalse(ReleaseChannel.offers(release("0.2.0-beta.3", prerelease = true), "0.2.0-beta.3", beta = true))
        assertTrue(ReleaseChannel.offers(release("0.2.0"), "0.2.0-beta.3", beta = false))
        // Unreadable versions are never picked or offered.
        assertNull(ReleaseChannel.newest(listOf(release("nightly")), beta = true))
        assertFalse(ReleaseChannel.offers(release("nightly"), "0.1.4", beta = true))
    }

    // ---- Checking ----

    private val fixture = javaClass.getResource("/updates/latest-release.json")!!.readText()

    /** The fixture as version [version], a pre-release when [prerelease]. */
    private fun releaseJson(version: String, prerelease: Boolean = false, draft: Boolean = false) =
        fixture.replace("0.1.5", version).replace("\"prerelease\": false", "\"prerelease\": $prerelease").replace("\"draft\": false", "\"draft\": $draft")

    private fun TestScope.graph() = TestGraph(backgroundScope, dispatcher = UnconfinedTestDispatcher(testScheduler))

    /** GitHub with [latest] as "latest" and [list] as the release list. */
    private fun TestGraph.github(latest: String, list: List<String>) {
        http = { req ->
            when (req.url.toString()) {
                ReleaseSource.LATEST_URL -> respond(latest, HttpStatusCode.OK, headersOf(HttpHeaders.ETag, "\"latest\""))
                ReleaseSource.LIST_URL -> respond(list.joinToString(",", "[", "]"), HttpStatusCode.OK, headersOf(HttpHeaders.ETag, "\"list\""))
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
    }

    @Test fun `off by default, the check asks for the latest full release only`() = runTest {
        val g = graph().apply { github(releaseJson("0.1.5"), listOf(releaseJson("0.2.0-beta.1", prerelease = true), releaseJson("0.1.5"))) }
        val updates = g.updates()
        val state = assertIs<UpdateState.Available>(updates.check(manual = true))
        assertEquals("0.1.5", state.release.version)
        assertEquals(ReleaseSource.LATEST_URL, g.requests.single().url.toString())
    }

    @Test fun `with beta on, a newer pre-release is offered and marked as one`() = runTest {
        val g = graph().apply { github(releaseJson("0.1.5"), listOf(releaseJson("0.2.0-beta.1", prerelease = true), releaseJson("0.1.5"), releaseJson("0.3.0", draft = true))) }
        val updates = g.updates()
        updates.setBeta(true)
        val state = assertIs<UpdateState.Available>(updates.check(manual = true))
        assertEquals("0.2.0-beta.1", state.release.version)
        assertTrue(state.release.prerelease)
        assertEquals(ReleaseSource.LIST_URL, g.requests.single().url.toString())
        // The release keeps its assets, so the download still checks SHA256SUMS and the signer.
        assertTrue(state.release.assets.any { it.name == ReleaseFiles.CHECKSUMS })
        assertTrue(g.updateCache.value.beta)
    }

    @Test fun `turning beta off drops the beta in hand and doesn't reuse the list's ETag`() = runTest {
        val g = graph().apply { github(releaseJson("0.1.4"), listOf(releaseJson("0.2.0-beta.1", prerelease = true))) }
        val updates = g.updates()
        updates.setBeta(true)
        assertIs<UpdateState.Available>(updates.check(manual = true))
        updates.setBeta(false)
        assertEquals(UpdateState.Idle, updates.state.value)
        assertNull(g.updateCache.value.release)
        // Stable has nothing newer than 0.1.4: up to date, and the stable request carries no ETag from the list.
        assertEquals(UpdateState.UpToDate("0.1.4"), updates.check(manual = true))
        assertNull(g.requests.last().headers[HttpHeaders.IfNoneMatch])
    }

    @Test fun `a list with nothing newer is up to date`() = runTest {
        // 0.1.4-beta.2 is the newest listed, and older than the 0.1.4 installed.
        val g = graph().apply { github(releaseJson("0.1.3"), listOf(releaseJson("0.1.3"), releaseJson("0.1.4-beta.2", prerelease = true))) }
        val updates = g.updates()
        updates.setBeta(true)
        assertEquals(UpdateState.UpToDate("0.1.4-beta.2"), updates.check(manual = true))
    }
}
