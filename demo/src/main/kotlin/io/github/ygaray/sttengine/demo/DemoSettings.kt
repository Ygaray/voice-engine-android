package io.github.ygaray.sttengine.demo

import io.github.ygaray.sttengine.BackendPreference
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/*
 * The demo's session settings (Phase 31, DEMO-02): the language and backend a human picks on the main
 * screen, and the server URL that makes the server choices usable.
 *
 * Everything the pickers and the URL field DECIDE lives here as pure functions over plain values, so
 * plain JUnit proves it with no Context and no Compose UI test library. The screen only renders the
 * answers; the ViewModel only holds the state and the store only persists it.
 *
 * PUBLIC-MIRROR RULE: `android/` is published, so this file never names a real host. The server URL
 * is typed by the human at run time, held in memory and in app-private storage, and NEVER logged:
 * nothing in this file logs, and every type that can carry the URL redacts its `toString`.
 */

/** The recognition language picker. [wire] is the bare tag handed to the engine (D-05). */
internal enum class DemoLanguage(val wire: String) {
    Auto("auto"),
    En("en"),
    Es("es"),
}

/** The backend picker's order on screen: Auto, Server, On-device (D-06). */
internal val BACKEND_OPTIONS: List<BackendPreference> =
    listOf(BackendPreference.Auto, BackendPreference.Server, BackendPreference.OnDevice)

/** The picker word for a backend preference. These are the `BackendPreference` names, hyphenated. */
internal fun backendLabel(preference: BackendPreference): String = when (preference) {
    BackendPreference.Auto -> "Auto"
    BackendPreference.Server -> "Server"
    BackendPreference.OnDevice -> "On-device"
}

/** The longest server URL accepted, in characters, after trimming. */
internal const val MAX_SERVER_URL_CHARS = 2048

/** The only scheme accepted. Lowercase on purpose: `WSS://` is rejected, not normalised. */
private const val WSS_SCHEME = "wss://"

/** The result of reading the URL field. */
internal sealed interface ServerUrlInput {
    /** Nothing typed (or only whitespace). */
    data object Empty : ServerUrlInput

    /** A usable URL, already trimmed. */
    data class Valid(val url: String) : ServerUrlInput {
        /** Redacted: a stray log line must never carry the URL. */
        override fun toString(): String = "Valid(url=set)"
    }

    /** Typed but unusable; [reason] picks the fixed supporting text. */
    data class Invalid(val reason: InvalidReason) : ServerUrlInput
}

/** Why a typed URL is unusable. One fixed supporting text per reason; none echoes the input. */
internal enum class InvalidReason { TooLong, NotWss, Whitespace, NoHost }

/**
 * Read the URL field. The input is trimmed first and the first failing rule wins:
 *
 * 1. empty is [ServerUrlInput.Empty];
 * 2. longer than [MAX_SERVER_URL_CHARS] is `Invalid(TooLong)`;
 * 3. not starting with lowercase `wss://` (`ws://`, `https://`, garbage, `WSS://`) is `Invalid(NotWss)`;
 * 4. any whitespace inside is `Invalid(Whitespace)`;
 * 5. no usable host or port (`wss://`, `wss:///`, `wss://?x`, `wss://:8444`, an empty or
 *    non-numeric or out-of-range port, an unclosed `[`, a bad host character) is `Invalid(NoHost)`;
 *    see [hasHost] for the exact rule;
 * 6. OkHttp's own URL parser must accept the text (with the leading `wss://` read as `https://`,
 *    which is what the engine's request builder does), otherwise `Invalid(NoHost)`. This is the
 *    check that makes `Valid` imply the request builder cannot throw (D-1415); rules 1-5 only give
 *    the human a precise reason for the common typing mistakes;
 * 7. otherwise [ServerUrlInput.Valid] holding the trimmed text.
 *
 * Total and side-effect free. The supporting text built from the result never echoes the input, and
 * nothing here logs.
 */
internal fun parseServerUrl(raw: String?): ServerUrlInput {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return ServerUrlInput.Empty
    if (text.length > MAX_SERVER_URL_CHARS) return ServerUrlInput.Invalid(InvalidReason.TooLong)
    if (!text.startsWith(WSS_SCHEME)) return ServerUrlInput.Invalid(InvalidReason.NotWss)
    if (text.any { it.isWhitespace() }) return ServerUrlInput.Invalid(InvalidReason.Whitespace)
    if (!hasHost(text.substring(WSS_SCHEME.length))) {
        return ServerUrlInput.Invalid(InvalidReason.NoHost)
    }
    // Last check (D-1415): the real parser, so a shape no hand-written rule lists (a malformed IPv6
    // literal, an empty or over-long host label, invalid IDNA) cannot become `Valid` and then throw
    // in the engine's `Request.Builder().url()` on Start.
    if (("https://" + text.substring(WSS_SCHEME.length)).toHttpUrlOrNull() == null) {
        return ServerUrlInput.Invalid(InvalidReason.NoHost)
    }
    return ServerUrlInput.Valid(text)
}

/**
 * Characters a (non-bracketed) host may never contain. These are the URL delimiters and the code
 * points OkHttp's host parser refuses, plus `%` (a stray percent sign is how `ex%zzample` slips
 * through; OkHttp would accept a well-formed escape, this validator is deliberately stricter) and
 * the space. Control characters are refused separately.
 */
private const val FORBIDDEN_HOST_CHARS = " #%/:<>?@[\\]^|"

/** The highest TCP port. */
private const val MAX_PORT = 65535

/**
 * Whether [afterScheme] starts with a host and, when a port is present, a real port.
 *
 * `Valid` is the gate in front of `ServerBackend`, whose OkHttp request builder THROWS on a string
 * its URL parser rejects, and nothing above the engine catches that (CR-01). `:stt` is frozen, so
 * the guard lives here. What guarantees "Valid implies the builder cannot throw" is NOT this
 * function: it is the final OkHttp parse in [parseServerUrl] (D-1415). This function is the cheap
 * first pass that gives the human a precise `NoHost` for the common typing mistakes, and it stays
 * stricter than OkHttp in places (it refuses `%`, for one). The authority is
 * everything up to the first `/`, `?` or `#`; any `user@` prefix is dropped; the host is what
 * precedes the port colon (or sits inside `[...]` for an IPv6 literal). Then:
 *
 * - the host is non-empty and holds no delimiter, space, `%` or control character; a bracketed
 *   literal must be closed, must hold only hex digits, `:` and `.`, and must contain a `:`;
 * - a port, if there is a colon after the host, is 1 to 5 ASCII digits and in 1..65535 (so a
 *   half-typed `host.example:`, `:abc`, `:0` and `:99999` are all refused).
 */
private fun hasHost(afterScheme: String): Boolean {
    val authority = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
    val hostAndPort = authority.substringAfterLast('@')
    val host: String
    val portText: String? // null = no port given
    if (hostAndPort.startsWith('[')) {
        val close = hostAndPort.indexOf(']')
        if (close < 0) return false // unclosed IPv6 literal
        host = hostAndPort.substring(1, close)
        val rest = hostAndPort.substring(close + 1)
        if (rest.isNotEmpty() && !rest.startsWith(':')) return false
        portText = if (rest.isEmpty()) null else rest.substring(1)
        if (host.isEmpty() || ':' !in host) return false
        if (!host.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) {
            return false
        }
    } else {
        host = hostAndPort.substringBefore(':')
        portText = if (':' in hostAndPort) hostAndPort.substringAfter(':') else null
        if (host.isEmpty()) return false
        if (host.any { it in FORBIDDEN_HOST_CHARS || it.code < 0x20 || it.code == 0x7f }) return false
    }
    if (portText != null) {
        if (portText.isEmpty() || portText.length > 5 || !portText.all { it in '0'..'9' }) return false
        if (portText.toInt() !in 1..MAX_PORT) return false
    }
    return true
}

/**
 * The backend that will actually run: the stored preference when the URL is valid, On-device
 * otherwise. A pure read; the stored preference is never overwritten by the coercion, because editing
 * the URL passes through invalid states keystroke by keystroke (UI-SPEC "Effective selection").
 */
internal fun effectiveBackend(stored: BackendPreference, url: ServerUrlInput): BackendPreference =
    if (url is ServerUrlInput.Valid) stored else BackendPreference.OnDevice

/**
 * Whether a backend segment can be tapped: never while a session is live; Server and Auto need a
 * valid URL, On-device does not.
 */
internal fun backendSegmentEnabled(
    option: BackendPreference,
    url: ServerUrlInput,
    sessionLive: Boolean,
): Boolean = when {
    sessionLive -> false
    option == BackendPreference.OnDevice -> true
    else -> url is ServerUrlInput.Valid
}

/**
 * The human's next-Start choice. [serverUrlRaw] is the text exactly as typed, so a half-typed URL
 * survives rotation and process death. What a session actually used is the engine's own state,
 * read per row, not this.
 */
internal data class DemoSelection(
    val language: DemoLanguage,
    val backend: BackendPreference,
    val serverUrlRaw: String,
) {
    /** Redacted: prints only whether a URL is present. */
    override fun toString(): String =
        "DemoSelection(language=$language, backend=$backend, " +
            "serverUrl=${if (serverUrlRaw.isNotBlank()) "set" else "(none)"})"
}

/** One edit a human can make to the [DemoSelection]. */
internal sealed interface SelectionChange {
    data class Language(val language: DemoLanguage) : SelectionChange
    data class Backend(val backend: BackendPreference) : SelectionChange

    data class ServerUrl(val raw: String) : SelectionChange {
        /** Redacted: a stray log line must never carry what was typed. */
        override fun toString(): String = "ServerUrl(raw=set)"
    }
}

/**
 * Apply [change] to [current], or return [current] untouched while a session is live: a picker or URL
 * change never reconfigures a live session, it applies at the next Start (D-05). The controls are
 * also disabled while live; this is the belt to that braces, against a tap racing the disable.
 */
internal fun applySelectionChange(
    current: DemoSelection,
    change: SelectionChange,
    sessionLive: Boolean,
): DemoSelection {
    if (sessionLive) return current
    return when (change) {
        is SelectionChange.Language -> current.copy(language = change.language)
        is SelectionChange.Backend -> current.copy(backend = change.backend)
        is SelectionChange.ServerUrl -> current.copy(serverUrlRaw = change.raw)
    }
}

/** What a UI Start hands to `DictationViewModel.start`. */
internal data class StartArgs(
    val language: String,
    val prefer: BackendPreference,
    val serverUrl: String?,
) {
    /** Redacted: prints only whether a URL is present. */
    override fun toString(): String =
        "StartArgs(language=$language, prefer=$prefer, " +
            "serverUrl=${if (serverUrl != null) "set" else "(none)"})"
}

/**
 * The arguments for a Start from the screen: the picker's wire tag, the effective backend, and the
 * URL only when that backend can use one. On-device always passes `null`.
 */
internal fun startArgs(
    language: DemoLanguage,
    stored: BackendPreference,
    url: ServerUrlInput,
): StartArgs {
    val prefer = effectiveBackend(stored, url)
    val serverUrl = if (prefer != BackendPreference.OnDevice && url is ServerUrlInput.Valid) {
        url.url
    } else {
        null
    }
    return StartArgs(language = language.wire, prefer = prefer, serverUrl = serverUrl)
}

/** The URL field's placeholder: a single-label example host, never a real one (public mirror). */
internal const val SERVER_URL_PLACEHOLDER = "wss://host.example/"

/**
 * The URL field's supporting text: fixed copy per result. It never contains the input, so a typed
 * private host cannot be echoed back onto the screen or into a dump of it.
 */
internal fun supportingText(input: ServerUrlInput): String = when (input) {
    ServerUrlInput.Empty -> "Needed for Server and Auto. Saved on this device only."
    is ServerUrlInput.Valid -> "Saved on this device only."
    is ServerUrlInput.Invalid -> when (input.reason) {
        InvalidReason.TooLong -> "Too long (max $MAX_SERVER_URL_CHARS characters)."
        InvalidReason.NotWss -> "Must start with wss://"
        InvalidReason.Whitespace -> "Must not contain spaces."
        InvalidReason.NoHost -> "Must include a host after wss://"
    }
}

/** The note beside the backend caption. [loud] means the `error` colour (a coerced choice). */
internal enum class BackendNote(val text: String, val loud: Boolean) {
    /** No usable URL and the stored choice is already On-device: nothing is being overridden. */
    NoUrl("no server URL", false),

    /** No usable URL but the stored choice is Server or Auto: the screen says it is overriding it. */
    Coerced("no server URL — using On-device", true),

    /** A valid URL is set. Never shows the URL itself. */
    UrlSet("server URL set", false),
}

/** Which [BackendNote] applies to the stored choice and the current URL. */
internal fun backendNote(stored: BackendPreference, url: ServerUrlInput): BackendNote = when {
    url is ServerUrlInput.Valid -> BackendNote.UrlSet
    stored == BackendPreference.OnDevice -> BackendNote.NoUrl
    else -> BackendNote.Coerced
}

/** The hint under the pickers: when the controls apply, and why they are locked while live. */
internal fun settingsHint(sessionLive: Boolean): String =
    if (sessionLive) {
        "Locked during a session. Rows show what it used."
    } else {
        "Changes apply at the next Start."
    }
