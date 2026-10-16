package io.github.ygaray.sttengine.internal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression pin for the contract the server's session-slot change rests on: the server claims the
 * session slot on the first frame and closes a frame-less connection with 1008 after 30 s, so every
 * `:stt` client must send its first frame immediately on open (teamlead ack 2026-10-16, plan 26-13).
 *
 * This test pins the Kotlin half: [ServerBackend] sends the config frame as the FIRST frame, from
 * inside the open callback's already-queued work, with no `delay`, timer or virtual-time advance
 * between the open callback and the send. A refactor that moves the first frame behind a delay or a
 * ready-wait makes this test fail.
 *
 * Robolectric, for the same reason as [ServerBackendTest]: the config frame is built with
 * `org.json.JSONObject`, a stub on the plain-JVM `android.jar`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class ServerBackendFirstFrameTest {

    @Test
    fun `the first frame is the config frame, sent on open with no delay and virtual time at zero`() = runTest {
        // StandardTestDispatcher: nothing runs unless the test runs it, so the only work executed
        // after the open callback is what the callback itself queued (runCurrent never advances time).
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeWs = FakeWebSocketFactory()
        val backend = ServerBackend(
            webSocketFactory = fakeWs,
            serverUrl = "wss://example.ts.net:8444/",
            language = "en",
            mainDispatcher = dispatcher,
        )

        backgroundScope.launch(dispatcher) { backend.connect() }
        runCurrent()
        val socket = fakeWs.lastSocket!!
        assertTrue("nothing may be sent before the socket opens", socket.sendLog.isEmpty())

        val timeBeforeOpen = testScheduler.currentTime
        fakeWs.fireOpen()
        runCurrent() // only the work the open callback already queued; NO advanceTimeBy, NO delay
        val timeAfterSend = testScheduler.currentTime

        assertEquals("exactly one frame after open", 1, socket.sendLog.size)
        assertTrue("the first frame is a text frame, never binary PCM", socket.sendLog[0].startsWith("text:"))
        assertTrue("no binary frame precedes the config frame", socket.sentBinary.isEmpty())
        val config = JSONObject(socket.sent.single())
        assertTrue("the first frame carries a version", config.has("version"))
        assertEquals("en", config.getString("language"))
        assertEquals(16_000, config.getInt("sampleRate"))
        assertEquals("the send is reached with virtual time unchanged", timeBeforeOpen, timeAfterSend)
    }
}
