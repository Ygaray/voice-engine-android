package io.github.ygaray.sttengine

/**
 * What this device's recognition service says about one language's **on-device** model.
 *
 * Returned per language by [checkLanguageAvailability], and the same classification a native
 * `"auto"` session start uses to decide whether it can begin — so a value read here and the outcome
 * of starting a session agree.
 *
 * **Always include an `else` branch in a `when` over this type; [UNKNOWN] may share it.** An explicit
 * [UNKNOWN] branch is not a substitute: listing every entry makes the `when` exhaustive without an
 * `else`, so an entry added later would break the host's build. And render [UNKNOWN] as "unknown",
 * never as "missing": it is a real answer the platform gives, not an error, and a host that folds it
 * into "missing" will tell a user their language is unavailable on a device that can dictate in it.
 */
public enum class LanguageAvailability {

    /**
     * The on-device model is installed. Dictation in this language can start, offline.
     */
    INSTALLED,

    /**
     * The language is supported on this device but its on-device model is not installed yet. A
     * model download can fix it; until then a session in this language fails with
     * [SttErrorCode.NO_OFFLINE_MODEL].
     */
    DOWNLOADABLE,

    /**
     * A download of the on-device model is already scheduled. It may take a long time to finish —
     * hours, for example while the service waits for an unmetered network. Until it completes a
     * session in this language fails with [SttErrorCode.NO_OFFLINE_MODEL]. Query again later rather
     * than requesting another download.
     */
    PENDING,

    /**
     * There is no on-device model for this language on this device, and no download will help. A
     * session in this language fails with [SttErrorCode.UNSUPPORTED_LANGUAGE].
     */
    UNSUPPORTED,

    /**
     * The recognition service could not tell: the query failed, timed out, or came back with
     * nothing usable. A session will still try to start — render this as "unknown", not as
     * "missing".
     */
    UNKNOWN,
}
