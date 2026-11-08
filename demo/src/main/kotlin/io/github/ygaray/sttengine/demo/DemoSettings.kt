package io.github.ygaray.sttengine.demo

import io.github.ygaray.sttengine.BackendPreference

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
 * 5. no host before the first `/`, `?`, `#` or port colon (`wss://`, `wss:///`, `wss://?x`,
 *    `wss://:8444`) is `Invalid(NoHost)`;
 * 6. otherwise [ServerUrlInput.Valid] holding the trimmed text.
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
    return ServerUrlInput.Valid(text)
}

/**
 * Whether [afterScheme] starts with a non-empty host: the authority is everything up to the first
 * `/`, `?` or `#`; any `user@` prefix is dropped; the host is what precedes the port colon (or sits
 * inside `[...]` for an IPv6 literal).
 */
private fun hasHost(afterScheme: String): Boolean {
    val authority = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
    val hostAndPort = authority.substringAfterLast('@')
    val host = if (hostAndPort.startsWith('[')) {
        hostAndPort.removePrefix("[").substringBefore(']')
    } else {
        hostAndPort.substringBefore(':')
    }
    return host.isNotEmpty()
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
