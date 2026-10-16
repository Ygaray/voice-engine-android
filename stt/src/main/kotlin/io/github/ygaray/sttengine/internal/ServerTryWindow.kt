package io.github.ygaray.sttengine.internal

/**
 * The bounds of the host-settable try-server window, [io.github.ygaray.sttengine.SttConfig.serverTryTimeoutMs]
 * (TRY-01; D-07 default and clamp, D-14 name and shape).
 *
 * **Why 10 000 ms by default.** Live cold-start captures showed a restarted server opening its
 * socket 5 to 8 seconds after the restart (model load before the `ready` frame, plus the retry
 * backoffs that run inside the same window), so a 5 000 ms window handed the session to the
 * on-device recognizer before the host was ready. 10 000 ms covers that with margin. The cost is
 * deaf time: nothing is captured while the window runs, so whenever the host is off or black-holed a
 * session start can wait up to 10 s before the on-device fallback under `Auto`. A refused or
 * DNS-class failure still fails fast, and a host that wants a shorter wait passes a smaller window.
 * This is reasoning from those captures, not a measurement of every host.
 *
 * **Why the 10 000 ms ceiling, and what the default equalling it means.** The default now equals the
 * ceiling. The coroutine-level window is INTENDED to be the overall bound on the attempt loop, and it
 * normally ends first because it is armed before the first connect attempt. But the WebSocket client
 * is built with OkHttp's default timers (`SttEngineFactory` creates the `OkHttpClient` without explicit
 * timeouts, so the connect timeout is also 10 s), equal to the window, so near the boundary either
 * timer may fire first. The selector's outcome is the same either way: exactly one fallback to the
 * on-device recognizer under `Auto`, the last `server-attempt` trace line is `WindowExpired`, no error
 * is surfaced, and a late failure from an already-abandoned attempt changes nothing; that outcome is
 * pinned in virtual time by `BackendSelectorDefaultWindowTest`. A strict ordering guarantee (window
 * always first) would need explicit OkHttp timeouts configured above the ceiling, which changes the
 * shared client of every server connection and is a separate change.
 *
 * **Why the 1 000 ms floor.** A zero or negative window would skip the server outright while still
 * looking "server-preferred"; one second is the least that gives a reachable host a real chance.
 *
 * Out-of-range values are clamped into `[MIN_MS, MAX_MS]`, never rejected — a host passing a
 * nonsense window gets a working engine, not an exception from a config constructor.
 */
internal object ServerTryWindow {
    const val DEFAULT_MS: Long = 10_000L
    const val MIN_MS: Long = 1_000L
    const val MAX_MS: Long = 10_000L
}
