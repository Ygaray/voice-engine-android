package io.github.ygaray.sttengine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.sttengine.internal.BackendSelector
import io.github.ygaray.sttengine.internal.SttEngineImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TRY-01 SC3 / Pitfall 14: the host's [SttConfig.serverTryTimeoutMs] must actually reach the
 * selector's attempt loop through the PUBLIC factory — a knob that exists on the config but is never
 * threaded into [BackendSelector] would pass every `SttConfig` unit test and still do nothing.
 *
 * No engine is started here: construction alone proves the wiring, and nothing opens a socket.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SttEngineFactoryTryWindowTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun selectorFor(config: SttConfig): BackendSelector {
        val engine = createSttEngine(context, config)
        assertTrue(
            "a server-eligible config must build a BackendSelector, got ${engine::class.simpleName}",
            engine is BackendSelector,
        )
        return engine as BackendSelector
    }

    @Test
    fun `a host-supplied window reaches the selector`() {
        val selector = selectorFor(
            SttConfig(serverUrl = "wss://host.example:8444/", serverTryTimeoutMs = 7_000L),
        )
        assertEquals(7_000L, selector.tryServerTimeoutMs)
    }

    @Test
    fun `an omitted window yields the 5000 ms default`() {
        val selector = selectorFor(SttConfig(serverUrl = "wss://host.example:8444/"))
        assertEquals(5_000L, selector.tryServerTimeoutMs)
    }

    @Test
    fun `an out-of-range window reaches the selector clamped`() {
        val selector = selectorFor(
            SttConfig(serverUrl = "wss://host.example:8444/", serverTryTimeoutMs = 50_000L),
        )
        assertEquals(10_000L, selector.tryServerTimeoutMs)
    }

    @Test
    fun `Server preference also receives the window`() {
        val selector = selectorFor(
            SttConfig(
                prefer = BackendPreference.Server,
                serverUrl = "wss://host.example:8444/",
                serverTryTimeoutMs = 3_000L,
            ),
        )
        assertEquals(3_000L, selector.tryServerTimeoutMs)
    }

    @Test
    fun `no serverUrl still builds the v1_0 on-device engine`() {
        val engine = createSttEngine(context, SttConfig())
        assertTrue(
            "no serverUrl must keep the v1.0 path, got ${engine::class.simpleName}",
            engine is SttEngineImpl,
        )
    }

    @Test
    fun `OnDevice preference ignores the window and builds the on-device engine`() {
        val engine = createSttEngine(
            context,
            SttConfig(
                prefer = BackendPreference.OnDevice,
                serverUrl = "wss://host.example:8444/",
                serverTryTimeoutMs = 7_000L,
            ),
        )
        assertTrue(engine is SttEngineImpl)
    }
}
