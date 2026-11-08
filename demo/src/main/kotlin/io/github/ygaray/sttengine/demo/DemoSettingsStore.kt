package io.github.ygaray.sttengine.demo

import android.content.SharedPreferences
import io.github.ygaray.sttengine.BackendPreference

/** The name of the app-private preferences file holding the demo's next-Start choice. */
internal const val PREFS_NAME = "stt-demo-settings"
internal const val KEY_LANGUAGE = "language"
internal const val KEY_BACKEND = "backend"
internal const val KEY_SERVER_URL = "serverUrl"

/**
 * The two operations the store needs from a key-value file, behind an interface so plain JUnit can
 * drive [DemoSettingsStore] with a fake (no Context, no Robolectric).
 */
internal interface SettingsBackend {
    /** The stored string, or `null` when absent. May throw `ClassCastException` on a wrong-typed value. */
    fun getString(key: String): String?

    /** Persist every entry as ONE transaction, so a reader never sees a half-written selection. */
    fun putStrings(values: Map<String, String>)
}

/**
 * [SettingsBackend] over `SharedPreferences`. Writes use `apply()`, never the synchronous variant,
 * because they run on the main thread on every picker tap and keystroke.
 */
internal class SharedPreferencesSettingsBackend(
    private val prefs: SharedPreferences,
) : SettingsBackend {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putStrings(values: Map<String, String>) {
        val editor = prefs.edit()
        values.forEach { (key, value) -> editor.putString(key, value) }
        editor.apply()
    }
}

/**
 * Persists the human's next-Start choice in app-private storage (`android:allowBackup` stays false).
 * The URL is stored as its trimmed raw text so a half-typed URL survives process death. Nothing here
 * logs.
 */
internal class DemoSettingsStore(private val backend: SettingsBackend) {

    /**
     * Never throws. Each key is read on its own: a missing key, a wrong-typed value or an unknown
     * enum name gives that key's default (language `en`, backend `OnDevice`, URL empty) without
     * spoiling the others.
     */
    fun read(): DemoSelection {
        val language = readKey(KEY_LANGUAGE)
            ?.let { name -> DemoLanguage.entries.firstOrNull { it.name == name } }
            ?: DemoLanguage.En
        val backendPreference = readKey(KEY_BACKEND)
            ?.let { name -> BackendPreference.entries.firstOrNull { it.name == name } }
            ?: BackendPreference.OnDevice
        val url = readKey(KEY_SERVER_URL)?.trim().orEmpty()
        return DemoSelection(language, backendPreference, url)
    }

    /** Write all three keys as one transaction; the URL is trimmed. */
    fun write(selection: DemoSelection) {
        backend.putStrings(
            mapOf(
                KEY_LANGUAGE to selection.language.name,
                KEY_BACKEND to selection.backend.name,
                KEY_SERVER_URL to selection.serverUrlRaw.trim(),
            ),
        )
    }

    private fun readKey(key: String): String? =
        try {
            backend.getString(key)
        } catch (e: RuntimeException) {
            // ClassCastException included: corrupt or foreign storage reads as "not set".
            null
        }
}
