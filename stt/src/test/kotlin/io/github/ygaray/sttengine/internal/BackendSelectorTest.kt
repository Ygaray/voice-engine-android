package io.github.ygaray.sttengine.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.sttengine.Backend
import io.github.ygaray.sttengine.BackendPreference
import io.github.ygaray.sttengine.EngineState
import io.github.ygaray.sttengine.PreflightHandle
import io.github.ygaray.sttengine.SttConfig
import io.github.ygaray.sttengine.SttError
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // --- Task 2: SEL-01's swallow conditions + SEL-02's prefer forcing --------------------------

    @Test
    fun `a try-server connect that times out falls back to native silently`() = runTest {
        val fakeWs = FakeWebSocketFactory()
        val fakeHandle = FakeRecognizerHandle()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val selector = BackendSelector(
            appContext = context(),
            config = SttConfig(serverUrl = "wss://example.ts.net:8444/", prefer = BackendPreference.Auto),
            handleFactory = RecognizerHandleFactory {
                PreflightHandle(fakeHandle, LanguageAvailability.INSTALLED)
            },
            permissions = PermissionChecker { true },
            webSocketFactory = fakeWs,
            mainDispatcher = dispatcher,
            tryServerTimeoutMs = 100L,
        )
        val errors = mutableListOf<SttError>()
        backgroundScope.launch(dispatcher) { selector.errors.collect { errors += it } }

        val started = launch(dispatcher) { selector.start() }
        // The fake server never answers at all — no fireOpen/fireMessage — so the only way out of
        // connect() is the coroutine-level timeout expiring.
        testScheduler.advanceUntilIdle()
        fakeHandle.reachListening()
        started.join()

        assertEquals(Backend.Native, selector.backend.value)
        assertEquals(EngineState.Listening, selector.state.value)
        assertTrue("no error should be emitted on a swallowed timeout", errors.isEmpty())
        assertEquals(1, fakeHandle.startCount)
    }

    @Test
    fun `a fake server that closes with code 1013 busy during try-server falls back to native silently`() =
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
            val errors = mutableListOf<SttError>()
            backgroundScope.launch(dispatcher) { selector.errors.collect { errors += it } }

            val started = launch(dispatcher) { selector.start() }
            fakeWs.fireOpen()
            fakeWs.fireClosing(1013, "busy")
            fakeHandle.reachListening()
            started.join()

            assertEquals(Backend.Native, selector.backend.value)
            assertEquals(EngineState.Listening, selector.state.value)
            assertTrue("no error should be emitted on a swallowed busy close", errors.isEmpty())
            assertEquals(1, fakeHandle.startCount)
        }

    @Test
    fun `prefer=OnDevice never invokes the fake WebSocket factory at all`() = runTest {
        val fakeWs = FakeWebSocketFactory()
        val fakeHandle = FakeRecognizerHandle()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val selector = BackendSelector(
            appContext = context(),
            config = SttConfig(
                serverUrl = "wss://example.ts.net:8444/",
                prefer = BackendPreference.OnDevice,
            ),
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

    @Test
    fun `state is Connecting during the try-server window, before Ready arrives`() = runTest {
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
        // Before Ready ever arrives — CR-04: the try-server connect attempt is genuinely in flight.
        assertEquals(EngineState.Connecting, selector.state.value)

        fakeWs.fireOpen()
        fakeWs.fireMessage("""{"type":"ready"}""")
        started.join()

        assertEquals(EngineState.Listening, selector.state.value)
    }

    @Test
    fun `prefer=Server with a try-server failure surfaces exactly one typed error and never touches native`() =
        runTest {
            val fakeWs = FakeWebSocketFactory()
            val fakeHandle = FakeRecognizerHandle()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val selector = BackendSelector(
                appContext = context(),
                config = SttConfig(
                    serverUrl = "wss://example.ts.net:8444/",
                    prefer = BackendPreference.Server,
                ),
                handleFactory = RecognizerHandleFactory {
                    PreflightHandle(fakeHandle, LanguageAvailability.INSTALLED)
                },
                permissions = PermissionChecker { true },
                webSocketFactory = fakeWs,
                mainDispatcher = dispatcher,
            )
            val errors = mutableListOf<SttError>()
            backgroundScope.launch(dispatcher) { selector.errors.collect { errors += it } }

            val started = launch(dispatcher) { selector.start() }
            fakeWs.fireOpen()
            fakeWs.fireFailure(IOException("connection refused"))
            started.join()

            assertEquals(1, errors.size)
            assertEquals(EngineState.Error, selector.state.value)
            // Server-only: no fallback attempt at all.
            assertEquals(0, fakeHandle.startCount)
        }
}
