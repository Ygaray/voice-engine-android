package io.github.ygaray.sttengine.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.sttengine.Backend
import io.github.ygaray.sttengine.EngineState
import io.github.ygaray.sttengine.PreflightHandle
import io.github.ygaray.sttengine.SttConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `BackendSelector`'s try-server-then-fallback policy (SEL-01/SEL-02/SEL-03), driven entirely
 * through [FakeWebSocketFactory] (server path) and [FakeRecognizerHandle] (native fallback path) —
 * no real socket, no real recognizer. Robolectric + SDK 35 for the same reasons [EngineHarness]
 * documents (a real `Context` for the native fallback's `RecognizerIntents`).
 *
 * This plan's two tasks split the coverage: Task 1 (this file's initial two tests) proves the
 * mechanical try-server-then-fallback wiring reaches `Listening` on both paths end-to-end; Task 2
 * adds SEL-01's swallow conditions and SEL-02's `prefer` forcing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class BackendSelectorTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a fake server that opens and sends ready reaches backend=Server and state=Listening`() =
        runTest {
            val fakeWs = FakeWebSocketFactory()
            val fakeHandle = FakeRecognizerHandle()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val selector = BackendSelector(
                appContext = context(),
                config = SttConfig(serverUrl = "wss://example.ts.net:8444/"),
                handleFactory = RecognizerHandleFactory {
                    PreflightHandle(fakeHandle, LanguageAvailability.INSTALLED)
                },
                permissions = PermissionChecker { true },
                webSocketFactory = fakeWs,
                mainDispatcher = dispatcher,
            )

            val started = launch(dispatcher) { selector.start() }
            fakeWs.fireOpen()
            fakeWs.fireMessage("""{"type":"ready"}""")
            started.join()

            assertEquals(Backend.Server, selector.backend.value)
            assertEquals(EngineState.Listening, selector.state.value)
            // The native fallback was never touched — the server path succeeded outright.
            assertEquals(0, fakeHandle.startCount)
        }

    @Test
    fun `serverUrl=null never touches the fake WebSocket factory — the v1_0 default path runs native`() =
        runTest {
            val fakeWs = FakeWebSocketFactory()
            val fakeHandle = FakeRecognizerHandle()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val selector = BackendSelector(
                appContext = context(),
                config = SttConfig(), // no serverUrl — the v1.0 default, untouched by this phase
                handleFactory = RecognizerHandleFactory {
                    PreflightHandle(fakeHandle, LanguageAvailability.INSTALLED)
                },
                permissions = PermissionChecker { true },
                webSocketFactory = fakeWs,
                mainDispatcher = dispatcher,
            )

            val started = launch(dispatcher) { selector.start() }
            fakeHandle.reachListening()
            started.join()

            assertEquals(0, fakeWs.createCount)
            assertEquals(Backend.Native, selector.backend.value)
            assertEquals(EngineState.Listening, selector.state.value)
        }
}
