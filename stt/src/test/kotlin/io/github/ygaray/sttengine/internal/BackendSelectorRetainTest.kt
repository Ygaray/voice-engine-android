package io.github.ygaray.sttengine.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.sttengine.LanguageAvailability
import io.github.ygaray.sttengine.PreflightHandle
import io.github.ygaray.sttengine.SttConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CORR-06 through the selector's DEFAULT `serverBackendFactory` (no factory override): the host's
 * `SttConfig.retainForCorpus` must reach the first config frame on the wire, and a default config
 * must leave that frame string-equal to the frozen legacy builder's output. A knob that exists on
 * the config but is never threaded into the default factory would pass every `SttConfig` test and
 * still retain nothing, which is the same Pitfall `SttEngineFactoryTryWindowTest` guards for the
 * try window.
 *
 * The session is never committed (no `ready`), so no microphone source is ever opened: the test
 * reads the first frame after `fireOpen()` and then ends the try window with `stop()`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class BackendSelectorRetainTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    /** The first text frame the selector's default factory put on the socket after `onOpen`. */
    private fun TestScope.firstFrameFor(config: SttConfig): String {
        val main = StandardTestDispatcher(testScheduler)
        val fakeWs = FakeWebSocketFactory()
        val fakeHandle = FakeRecognizerHandle()
        val selector = BackendSelector(
            appContext = context(),
            config = config,
            handleFactory = RecognizerHandleFactory {
                PreflightHandle(fakeHandle, LanguageAvailability.INSTALLED)
            },
            permissions = PermissionChecker { true },
            webSocketFactory = fakeWs,
            mainDispatcher = main,
            tryServerTimeoutMs = 5_000L,
            // serverBackendFactory deliberately NOT overridden: the DEFAULT factory is under test.
        )
        launch(main) { selector.start() }
        runCurrent()
        assertEquals("attempt 1 must be in flight", 1, fakeWs.createCount)
        fakeWs.fireOpen()
        runCurrent()
        val socket = checkNotNull(fakeWs.lastSocket)
        val first = synchronized(socket.sendLog) { socket.sendLog.first() }
        assertTrue("expected a text frame, got $first", first.startsWith("text:"))

        // End the try window so the test leaves nothing suspended.
        launch(main) { selector.stop() }
        runCurrent()
        return first.removePrefix("text:")
    }

    @Test
    fun `retainForCorpus true reaches the first config frame through the default factory`() = runTest {
        val frame = firstFrameFor(SttConfig(serverUrl = "wss://example.ts.net:8444/", retainForCorpus = true))

        val json = JSONObject(frame)
        assertTrue("config frame must carry the opt-in: $frame", json.has("retainForCorpus"))
        assertEquals(true, json.getBoolean("retainForCorpus"))
        assertEquals("en", json.getString("language"))
    }

    @Test
    fun `the default config leaves the first frame string-equal to the frozen legacy builder`() = runTest {
        val frame = firstFrameFor(SttConfig(serverUrl = "wss://example.ts.net:8444/"))

        assertEquals(io.github.ygaray.sttengine.legacy.buildConfigFrame("en"), frame)
        assertTrue(!JSONObject(frame).has("retainForCorpus"))
    }

    @Test
    fun `lastSessionId is null before any session`() = runTest {
        val selector = BackendSelector(
            appContext = context(),
            config = SttConfig(serverUrl = "wss://example.ts.net:8444/", retainForCorpus = true),
            handleFactory = RecognizerHandleFactory {
                PreflightHandle(FakeRecognizerHandle(), LanguageAvailability.INSTALLED)
            },
            permissions = PermissionChecker { true },
            webSocketFactory = FakeWebSocketFactory(),
            mainDispatcher = StandardTestDispatcher(testScheduler),
        )
        assertNull(selector.lastSessionId)
    }
}
