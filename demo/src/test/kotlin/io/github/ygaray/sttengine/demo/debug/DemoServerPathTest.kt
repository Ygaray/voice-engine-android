package io.github.ygaray.sttengine.demo.debug

import io.github.ygaray.sttengine.Backend
import io.github.ygaray.sttengine.BackendPreference
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

    private fun assertRefused(request: ServerPathRequest) {
        assertTrue("expected Refused, got $request", request is ServerPathRequest.Refused)
        assertTrue((request as ServerPathRequest.Refused).reason.isNotBlank())
    }
}
