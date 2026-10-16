package io.github.ygaray.sttengine.demo

import io.github.ygaray.sttengine.SttErrorCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the `invalid-config` hint (Phase 26, plan 26-07). `invalid-config` is reachable on Android
 * from a server `error` frame (the server backend surfaces the frame's code as sent), so the hint
 * must not blame the client alone. Plain JUnit, no Context.
 *
 * The comment above the shared codes in `ErrorHints.kt` is pinned by `scripts/verify-commit-pause-default.sh
 * --docs`, not here: a comment is not behavior.
 */
class ErrorHintsTest {

    private val hint: String? = ErrorHints.forCode(SttErrorCode.INVALID_CONFIG)

    @Test
    fun `the invalid-config hint exists and is not blank`() {
        assertNotNull(hint)
        assertTrue(hint!!.isNotBlank())
    }

    @Test
    fun `the invalid-config hint no longer blames only a client bug`() {
        assertFalse(hint!!.contains("likely a client bug"))
        assertFalse(hint!!.contains("client bug"))
    }

    @Test
    fun `the invalid-config hint names the server as a possible rejecter`() {
        assertTrue(hint!!.contains("server", ignoreCase = true))
    }

    @Test
    fun `the invalid-config hint points at the settings a rejected config comes from not the server address`() {
        // A wrong address surfaces as server-unreachable, never as a rejected config.
        assertFalse(hint!!.contains("address", ignoreCase = true))
        assertTrue(hint.contains("language", ignoreCase = true))
        assertTrue(hint.contains("engine settings", ignoreCase = true))
    }

    @Test
    fun `the invalid-config hint does not mention a setting that does not exist`() {
        // The spike dropped the commit-silence knob (rescoped), so no such setting exists to blame.
        assertFalse(hint!!.contains("silence", ignoreCase = true))
        assertFalse(hint!!.contains("commitSilenceMs"))
    }

    @Test
    fun `an unknown code still gets no hint rather than a placeholder`() {
        assertNull(ErrorHints.forCode(SttErrorCode("not-a-real-code")))
    }
}
