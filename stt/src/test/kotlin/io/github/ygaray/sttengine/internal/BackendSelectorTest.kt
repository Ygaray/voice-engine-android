package io.github.ygaray.sttengine.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.sttengine.Backend
import io.github.ygaray.sttengine.BackendPreference
import io.github.ygaray.sttengine.EngineState
import io.github.ygaray.sttengine.PreflightHandle
import io.github.ygaray.sttengine.SttConfig
import io.github.ygaray.sttengine.SttError
import io.github.ygaray.sttengine.SttEvent
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

    // --- CR-02: ServerBackend.warnings must reach selector.events end-to-end ------------------------

    @Test
    fun `a ServerBackend warning reaches selector_events as SttEvent_Warning`() = runTest {
        val fakeWs = FakeWebSocketFactory()
        val fakeHandle = FakeRecognizerHandle()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        var serverBackend: ServerBackend? = null
        val selector = BackendSelector(
            appContext = context(),
            config = SttConfig(serverUrl = "wss://example.ts.net:8444/"),
            handleFactory = RecognizerHandleFactory {
                PreflightHandle(fakeHandle, LanguageAvailability.INSTALLED)
            },
            permissions = PermissionChecker { true },
            webSocketFactory = fakeWs,
            mainDispatcher = dispatcher,
            serverBackendFactory = { url ->
                ServerBackend(
                    webSocketFactory = fakeWs,
                    serverUrl = url,
                    language = "en",
                    mainDispatcher = dispatcher,
                    backpressureGate = ServerBackpressureGate(ServerBackpressureGateOptions(soft = 10L)),
                ).also { serverBackend = it }
            },
        )
        val events = mutableListOf<SttEvent>()
        backgroundScope.launch(dispatcher) { selector.events.collect { events += it } }

        val started = launch(dispatcher) { selector.start() }
        fakeWs.fireOpen()
        fakeWs.fireMessage("""{"type":"ready"}""")
        started.join()

        // Directly drive the gated send path over its soft threshold (mirrors ServerBackendTest's
        // own `sendPcmFrame` coverage) — this exercises the FORWARDING wire this test targets,
        // independent of whether a production caller drives sendPcmFrame yet (that pump is deferred,
        // per ServerBackend's own KDoc and API.md's SttWarning section).
        checkNotNull(fakeWs.lastSocket).fakeQueueSize = 100L
        checkNotNull(serverBackend).sendPcmFrame(ByteArray(4))

        val warningEvents = events.filterIsInstance<SttEvent.Warning>()
        assertEquals(
            "expected exactly one Warning event forwarded from ServerBackend.warnings: $events",
            1,
            warningEvents.size,
        )
        assertTrue(warningEvents.single().warning.active)
        assertEquals("backpressure", warningEvents.single().warning.code)
    }

    // --- CR-03: stop() must interrupt an in-flight try-server attempt, not no-op --------------------

    @Test
    fun `stop() during the try-server window abandons the pending socket immediately`() = runTest {
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

        val started = launch(dispatcher) { selector.start() }
        // The fake server never answers at all — start() is genuinely suspended inside the
        // try-server connect() window right now, not merely about to time out.
        assertEquals(EngineState.Connecting, selector.state.value)
        assertEquals(0, checkNotNull(fakeWs.lastSocket).cancelCount)

        val stopped = launch(dispatcher) { selector.stop() }
        stopped.join()

        // CR-03: previously stop() found both activeServer/activeNative null here and did nothing —
        // the pending socket kept running unbounded. It must now be abandoned immediately, not left
        // to the full tryServerTimeoutMs window. (>= 1 rather than == 1: the coroutine-level
        // cancellation this triggers ALSO fires ServerBackend.connect()'s own
        // `invokeOnCancellation { socket.cancel() }`, so more than one cancel() call on the same
        // socket is expected and harmless — the pre-existing plain-timeout path already double-calls
        // it the same way.)
        assertTrue(
            "stop() during try-server must cancel the pending socket immediately, not defer to " +
                "the full tryServerTimeoutMs window",
            checkNotNull(fakeWs.lastSocket).cancelCount >= 1,
        )

        started.join()
    }

    @Test
    fun `native never starts after stop() returns during the try-server window`() = runTest {
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

        val started = launch(dispatcher) { selector.start() }
        val stopped = launch(dispatcher) { selector.stop() }
        stopped.join()
        started.join()

        // Let virtual time run past the full try-server timeout — if stop() had failed to actually
        // interrupt the in-flight attempt (CR-03), this is exactly where the native fallback would
        // start, mutating state/backend after the host's stop() call already returned.
        testScheduler.advanceUntilIdle()

        assertEquals(
            "native must never start once stop() has returned during the try-server window",
            0,
            fakeHandle.startCount,
        )
    }
}
