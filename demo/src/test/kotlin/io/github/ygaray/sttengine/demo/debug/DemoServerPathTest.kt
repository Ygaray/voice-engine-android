package io.github.ygaray.sttengine.demo.debug

import io.github.ygaray.sttengine.Backend
import io.github.ygaray.sttengine.BackendPreference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral coverage for the Phase 21 (D-08) server-path debug surface: [parseServerPathExtras]
 * and [serverPathFeedConfirmed].
 *
 * Plain JUnit, exactly like [DemoControlReceiverDiagnosticsTest] — every function under test is a
 * pure top-level helper, so no Context, no Robolectric and no live view model are needed.
 *
 * Only `wss://host.example:8444/` appears as a host here: `android/` is published as a public
 * mirror, so no real server address may enter any file under it.
 */
class DemoServerPathTest {

    private val url = "wss://host.example:8444/"

    // --- parseServerPathExtras ----------------------------------------------------------------------

    @Test
    fun `no extras means no server path`() {
        assertEquals(ServerPathRequest.None, parseServerPathExtras(null, null))
    }

    @Test
    fun `a blank url with no preference means no server path`() {
        assertEquals(ServerPathRequest.None, parseServerPathExtras(" ", null))
    }

    @Test
    fun `a wss url alone defaults the preference to Auto`() {
        val request = parseServerPathExtras(url, null)
        assertTrue(request is ServerPathRequest.Server)
        request as ServerPathRequest.Server
        assertEquals(url, request.url)
        assertEquals(BackendPreference.Auto, request.prefer)
    }

    @Test
    fun `a wss url with prefer Server is a Server request`() {
        val request = parseServerPathExtras(url, "Server")
        assertTrue(request is ServerPathRequest.Server)
        request as ServerPathRequest.Server
        assertEquals(url, request.url)
        assertEquals(BackendPreference.Server, request.prefer)
    }

    @Test
    fun `a plain ws url is refused`() {
        assertRefused(parseServerPathExtras("ws://host.example:8444/", "Server"))
    }

    @Test
    fun `an https url is refused`() {
        assertRefused(parseServerPathExtras("https://host.example/", null))
    }

    @Test
    fun `a preference without a url is refused`() {
        assertRefused(parseServerPathExtras(null, "Server"))
    }

    @Test
    fun `the preference is case-sensitive`() {
        assertRefused(parseServerPathExtras(url, "server"))
    }

    @Test
    fun `an unknown preference is refused`() {
        assertRefused(parseServerPathExtras(url, "Bogus"))
    }

    @Test
    fun `a refusal reason never echoes the url`() {
        val refused = parseServerPathExtras("ws://host.example:8444/", "Server")
        refused as ServerPathRequest.Refused
        assertFalse(refused.reason.contains("host.example"))
    }

    // --- retainForCorpus (Phase 25, D-17(e)) ----------------------------------------------------------

    @Test
    fun `the retain flag defaults to false on a server request`() {
        val request = parseServerPathExtras(url, null) as ServerPathRequest.Server
        assertFalse(request.retainForCorpus)
    }

    @Test
    fun `the retain flag is carried on a server request`() {
        val request = parseServerPathExtras(url, "Server", true) as ServerPathRequest.Server
        assertTrue(request.retainForCorpus)
        assertEquals(BackendPreference.Server, request.prefer)
        assertEquals(url, request.url)
    }

    @Test
    fun `the retain flag without a url is refused`() {
        assertRefused(parseServerPathExtras(null, null, true))
    }

    @Test
    fun `the retain flag with a blank url is refused`() {
        assertRefused(parseServerPathExtras(" ", null, true))
    }

    @Test
    fun `the retain refusal names both extras and never quotes a url`() {
        val refused = parseServerPathExtras(null, null, true) as ServerPathRequest.Refused
        assertTrue(refused.reason.contains("RETAIN_FOR_CORPUS"))
        assertTrue(refused.reason.contains("SERVER_URL"))
        // The scheme may be named as a hint ("a wss:// SERVER_URL"), but no URL body may follow it.
        assertFalse(Regex("://\\S").containsMatchIn(refused.reason))
    }

    @Test
    fun `the retain flag with a non-wss url is still refused without echoing the url`() {
        val refused = parseServerPathExtras("ws://host.example:8444/", null, true)
        assertRefused(refused)
        assertFalse((refused as ServerPathRequest.Refused).reason.contains("host.example"))
    }

    @Test
    fun `a server request toString shows the retain flag and not the url`() {
        val off = parseServerPathExtras(url, "Server") as ServerPathRequest.Server
        val on = parseServerPathExtras(url, "Server", true) as ServerPathRequest.Server
        assertTrue(off.toString().contains("retain=false"))
        assertTrue(on.toString().contains("retain=true"))
        assertFalse(off.toString().contains("host.example"))
        assertFalse(on.toString().contains("host.example"))
    }

    @Test
    fun `retainOf is false for none, refused and a server request without the flag`() {
        assertFalse(retainOf(ServerPathRequest.None))
        assertFalse(retainOf(ServerPathRequest.Refused("nope")))
        assertFalse(retainOf(parseServerPathExtras(url, "Server")))
    }

    @Test
    fun `retainOf is true only for a server request with the flag`() {
        assertTrue(retainOf(parseServerPathExtras(url, "Server", true)))
    }

    // --- serverPathFeedConfirmed ----------------------------------------------------------------------

    @Test
    fun `server backend with an opened feed is confirmed`() {
        assertTrue(serverPathFeedConfirmed(Backend.Server, 1))
    }

    @Test
    fun `server backend with an unopened feed is not confirmed`() {
        assertFalse(serverPathFeedConfirmed(Backend.Server, 0))
    }

    @Test
    fun `native backend is never a confirmed server-path feed`() {
        assertFalse(serverPathFeedConfirmed(Backend.Native, 3))
    }

    // --- serverPathDiagnostics ------------------------------------------------------------------------

    @Test
    fun `serverPathDiagnostics writes exactly the four server-path keys`() {
        val into = JSONObject()

        serverPathDiagnostics(into, Backend.Server, partialLive = true, partialUpdates = 4, warningCount = 0)

        assertEquals(
            setOf("backend", "partialLive", "partialUpdates", "warningCount"),
            into.keys().asSequence().toSet(),
        )
        assertEquals("Server", into.getString("backend"))
        assertEquals(true, into.getBoolean("partialLive"))
        assertEquals(4, into.getInt("partialUpdates"))
        assertEquals(0, into.getInt("warningCount"))

        // Privacy invariant: never transcript text and never the server URL.
        assertFalse(into.has("text"))
        assertFalse(into.has("partial"))
        assertFalse(into.has("serverUrl"))
    }

    @Test
    fun `serverPathDiagnostics names the native backend`() {
        val into = JSONObject()

        serverPathDiagnostics(into, Backend.Native, partialLive = false, partialUpdates = 0, warningCount = 2)

        assertEquals("Native", into.getString("backend"))
        assertEquals(false, into.getBoolean("partialLive"))
        assertEquals(2, into.getInt("warningCount"))
    }

    private fun assertRefused(request: ServerPathRequest) {
        assertTrue("expected Refused, got $request", request is ServerPathRequest.Refused)
        assertTrue((request as ServerPathRequest.Refused).reason.isNotBlank())
    }
}
