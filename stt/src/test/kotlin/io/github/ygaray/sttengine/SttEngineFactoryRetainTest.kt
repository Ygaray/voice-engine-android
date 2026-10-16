package io.github.ygaray.sttengine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.sttengine.internal.BackendSelector
import io.github.ygaray.sttengine.internal.SttEngineImpl
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CORR-06 / CORR-07 at the public factory: a NATIVE engine ignores `retainForCorpus` with no error
 * and `lastSessionId` stays null; a server-eligible engine reads null before any session. No engine
 * is started here: construction alone proves the routing, and nothing opens a socket.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SttEngineFactoryRetainTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `no serverUrl with retainForCorpus builds the native engine and lastSessionId is null`() {
        val engine = createSttEngine(context, SttConfig(retainForCorpus = true))
        assertTrue(
            "no serverUrl must keep the native path, got ${engine::class.simpleName}",
            engine is SttEngineImpl,
        )
        assertNull(engine.lastSessionId)
    }

    @Test
    fun `OnDevice preference with a serverUrl and retainForCorpus builds the native engine`() {
        val engine = createSttEngine(
            context,
            SttConfig(
                prefer = BackendPreference.OnDevice,
                serverUrl = "wss://host.example:8444/",
                retainForCorpus = true,
            ),
        )
        assertTrue(engine is SttEngineImpl)
        assertNull(engine.lastSessionId)
    }

    @Test
    fun `Auto with a serverUrl and retainForCorpus builds the selector whose lastSessionId is null before any session`() {
        val engine = createSttEngine(
            context,
            SttConfig(serverUrl = "wss://host.example:8444/", retainForCorpus = true),
        )
        assertTrue(
            "a server-eligible config must build a BackendSelector, got ${engine::class.simpleName}",
            engine is BackendSelector,
        )
        assertNull(engine.lastSessionId)
    }
}
