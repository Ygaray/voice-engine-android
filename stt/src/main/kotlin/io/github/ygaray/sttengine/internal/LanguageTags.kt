package io.github.ygaray.sttengine.internal

import java.util.Locale

/**
 * Resolves a possibly region-less BCP-47 language tag to a region-qualified one before it reaches
 * the platform on-device recognizer.
 *
 * ## Why this exists
 *
 * Google's current on-device recognizer (**SODA**) ships only region-qualified offline packs
 * (`en-US`, `en-GB`, `es-US`, …). Handed a **bare** language tag such as `en`, it rejects the
 * session with `ERROR_LANGUAGE_NOT_SUPPORTED` (code 12) — the device log reads
 * `SodaLPDirGenerator: Returning no LP, as MDD does not support locale: en`. Older recognizer
 * generations tolerated the bare form, which is why the failure only surfaces on some devices (it
 * did not reproduce on the phase's original test rig, only on a newer SODA build).
 *
 * [io.github.ygaray.sttengine.SttConfig.language]'s documented v1 default is the bare `"en"`, and a
 * host may legitimately pass any bare tag, so the engine must region-qualify it against the device
 * locale before it reaches both the language preflight and the `RecognizerIntent`. Without this,
 * default-configured dictation is dead on every SODA device.
 *
 * ## Behaviour
 *
 * - A tag that already carries a region (`en-US`, `de-DE`) is returned unchanged.
 * - A bare tag whose language matches the device locale's language borrows the device's full tag
 *   (`en` on an `en-US` device → `en-US`; `en` on an `en-GB` device → `en-GB`).
 * - A bare tag for a *different* language borrows only the device's region (`es` on an `en-US`
 *   device → `es-US`, which SODA supports), a best-effort that is still strictly better than a
 *   region-less tag the recognizer refuses outright.
 * - An unparseable tag, or a device locale with no region to lend, is returned unchanged (nothing
 *   better is knowable — let the platform's own error path speak).
 *
 * Pure and locale-injected so it is deterministically testable; the engine calls it with
 * `Locale.getDefault()`.
 */
internal object LanguageTags {
    fun regionQualified(tag: String, deviceLocale: Locale): String {
        val locale = Locale.forLanguageTag(tag)
        if (locale.language.isEmpty()) return tag        // unparseable — leave untouched
        if (locale.country.isNotEmpty()) return tag      // already region-qualified
        val region = deviceLocale.country
        if (region.isEmpty()) return tag                 // device locale has no region to lend
        return if (locale.language == deviceLocale.language) {
            deviceLocale.toLanguageTag()                 // "en" on en-US device -> "en-US"
        } else {
            // language is a lowercase ISO code, region an uppercase country code — their hyphenated
            // form is already the canonical BCP-47 tag ("es" + "US" -> "es-US"). Built by string
            // rather than the deprecated `Locale(String, String)` constructor.
            "${locale.language}-$region"
        }
    }
}
