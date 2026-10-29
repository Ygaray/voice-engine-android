package io.github.ygaray.sttengine

import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the OkHttp client behind every server session (Phase 28, LIVE-03, D-11): a 10 s WebSocket
 * ping, and nothing else changed — the TLS objects are exactly the ones a fresh `OkHttpClient` uses
 * (T-09-02: platform-default validation, no custom verifier, trust manager or pinner).
 *
 * This is the configuration half only. The real-socket proof that the interval actually fails a
 * silent peer is [io.github.ygaray.sttengine.internal.ServerLivenessRealSocketTest].
 */
class ServerHttpClientTest {

    private val built = buildServerHttpClient()
    private val fresh = OkHttpClient()

    @After
    fun tearDown() {
        built.dispatcher.executorService.shutdown()
        fresh.dispatcher.executorService.shutdown()
    }

    @Test
    fun `the server client pings every 10 seconds`() {
        assertEquals(10_000, built.pingIntervalMillis)
        assertEquals(10_000L, SERVER_PING_INTERVAL_SECONDS * 1_000L)
    }

    @Test
    fun `the server client keeps the default hostname verifier`() {
        assertSame(fresh.hostnameVerifier, built.hostnameVerifier)
    }

    @Test
    fun `the server client pins no certificates`() {
        assertTrue("no pins: ${built.certificatePinner.pins}", built.certificatePinner.pins.isEmpty())
        assertEquals(fresh.certificatePinner, built.certificatePinner)
    }

    @Test
    fun `the server client keeps the platform-default trust manager and socket factory`() {
        assertEquals(fresh.x509TrustManager?.javaClass, built.x509TrustManager?.javaClass)
        assertEquals(fresh.sslSocketFactory.javaClass, built.sslSocketFactory.javaClass)
    }
}
