package io.github.ygaray.sttengine

/**
 * What happened to one [submitCorrection] call. Always one of these four values; the call never
 * throws on a network, TLS, timeout or malformed-URL failure.
 *
 * **Always include an `else` branch in a `when` over this type.** Listing every entry makes the
 * `when` exhaustive without an `else`, so an entry added later would break the host's build.
 *
 * The two outcomes a host can act on differently are [UNKNOWN_SESSION] (the id does not name a
 * record; retrying the same id will not help) and [UNREACHABLE] (the outcome is unknown; a retry may
 * help, and may also append a duplicate revision).
 */
public enum class CorrectionSubmitResult {

    /**
     * The server appended a revision for this record. The correction is stored; nothing was
     * overwritten.
     */
    ACCEPTED,

    /**
     * The server answered 404: there is no such record. That covers an id that was never retained, a
     * record whose retention failed, a server older than the correction route, and an id of the
     * wrong shape. Retrying the same id will not help.
     */
    UNKNOWN_SESSION,

    /**
     * The outcome is unknown: there was no answer, or the server had a problem of its own (a
     * timeout, an IO or TLS failure, a 408, or a 5xx). The correction MAY or MAY NOT have been
     * stored. A retry can append a second, identical revision, which the corpus tolerates.
     */
    UNREACHABLE,

    /**
     * The server or the client refused the input, and sending the same input again will not
     * succeed: a 4xx other than 404, a blank corrected text or id, a body over the server's size
     * cap, a revision limit, or a base URL that is not a URL.
     */
    REJECTED,
}
