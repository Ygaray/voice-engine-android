package io.github.ygaray.sttengine.internal

/**
 * The bounds of the host-settable try-server window, [io.github.ygaray.sttengine.SttConfig.serverTryTimeoutMs]
 * (TRY-01; D-07 default and clamp, D-14 name and shape).
 *
 * **Why 5 000 ms by default.** It covers a warm server's ~3 s cold start (model load before the
 * `ready` frame) plus the retry backoffs that run inside the same window. Nothing is captured while
 * the window runs, so a longer default is not free: it is more deaf time at the start of every
 * session whenever the server host is off. 5 000 ms is the smallest round value that reliably lets
 * a cold-starting server win.
 *
 * **Why the 10 000 ms ceiling.** It stays at or under OkHttp's default 10 s connect/read timeouts, so
 * the coroutine-level window is always the binding bound on the attempt loop — never a socket
 * timeout firing underneath it with a different failure shape. A larger window would need explicit
 * OkHttp timeouts configured to match, which is a separate change.
 *
 * **Why the 1 000 ms floor.** A zero or negative window would skip the server outright while still
 * looking "server-preferred"; one second is the least that gives a reachable host a real chance.
 *
 * Out-of-range values are clamped into `[MIN_MS, MAX_MS]`, never rejected — a host passing a
 * nonsense window gets a working engine, not an exception from a config constructor.
 */
internal object ServerTryWindow {
    const val DEFAULT_MS: Long = 5_000L
    const val MIN_MS: Long = 1_000L
    const val MAX_MS: Long = 10_000L
}
