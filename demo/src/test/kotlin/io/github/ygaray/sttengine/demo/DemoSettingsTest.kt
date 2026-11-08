package io.github.ygaray.sttengine.demo

import io.github.ygaray.sttengine.BackendPreference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-seam coverage for the demo's session settings (Phase 31, DEMO-02): every decision the
 * pickers and the URL field make lives in [DemoSettings.kt], so plain JUnit proves it with no
 * Compose UI test library (none may be added). Label fit, IME overlap, locked-state rendering and
 * dark mode are Gate-2 / TESTER backstops.
 *
 * Plain JUnit, no Context. The only host string here is the single-label example host
 * `host.example`: `android/` is published as a public mirror and the release scanner's tree scope
 * has no allowlist, so no real host, port or `.ts.net` string may appear.
 */
class DemoSettingsTest {

    private val url = "wss://host.example/"

    private val DEFAULTS = DemoSelection(DemoLanguage.En, BackendPreference.OnDevice, "")

    // --- Task 1: the selection model and the Start arguments ------------------------------------

    @Test
    fun `language wires are bare tags in picker order`() {
        assertEquals(
            listOf("auto", "en", "es"),
            DemoLanguage.entries.map { it.wire },
        )
        assertEquals(
            listOf(DemoLanguage.Auto, DemoLanguage.En, DemoLanguage.Es),
            DemoLanguage.entries.toList(),
        )
    }

    @Test
    fun `start args carry the picker language and effective backend`() {
        assertEquals(
            StartArgs("es", BackendPreference.Server, url),
            startArgs(DemoLanguage.Es, BackendPreference.Server, ServerUrlInput.Valid(url)),
        )
        // On-device never carries a URL, even when a valid one is stored.
        assertEquals(
            StartArgs("auto", BackendPreference.OnDevice, null),
            startArgs(DemoLanguage.Auto, BackendPreference.OnDevice, ServerUrlInput.Valid(url)),
        )
        assertEquals(
            StartArgs("auto", BackendPreference.Auto, url),
            startArgs(DemoLanguage.Auto, BackendPreference.Auto, ServerUrlInput.Valid(url)),
        )
        // No valid URL coerces to On-device with no URL.
        assertEquals(
            StartArgs("en", BackendPreference.OnDevice, null),
            startArgs(DemoLanguage.En, BackendPreference.Server, ServerUrlInput.Empty),
        )
        assertEquals(
            StartArgs("en", BackendPreference.OnDevice, null),
            startArgs(
                DemoLanguage.En,
                BackendPreference.Auto,
                ServerUrlInput.Invalid(InvalidReason.NotWss),
            ),
        )
    }

    @Test
    fun `effective backend coerces without a valid url and never rewrites the stored choice`() {
        val inputs = listOf(
            ServerUrlInput.Empty,
            ServerUrlInput.Invalid(InvalidReason.NotWss),
            ServerUrlInput.Invalid(InvalidReason.TooLong),
        )
        for (stored in BackendPreference.entries) {
            val selection = DemoSelection(DemoLanguage.En, stored, "ws://nope")
            val before = selection.copy()
            for (input in inputs) {
                assertEquals(BackendPreference.OnDevice, effectiveBackend(stored, input))
            }
            assertEquals(stored, effectiveBackend(stored, ServerUrlInput.Valid(url)))
            // The coercion is a pure read: the selection passed in is unchanged afterwards.
            assertEquals(before, selection)
            assertEquals(stored, selection.backend)
        }
    }

    @Test
    fun `selection is locked while a session is live`() {
        val current = DemoSelection(DemoLanguage.En, BackendPreference.OnDevice, "")
        val changes = listOf(
            SelectionChange.Language(DemoLanguage.Es),
            SelectionChange.Backend(BackendPreference.Server),
            SelectionChange.ServerUrl(url),
        )
        for (change in changes) {
            assertEquals(current, applySelectionChange(current, change, sessionLive = true))
        }
        assertEquals(
            current.copy(language = DemoLanguage.Es),
            applySelectionChange(current, changes[0], sessionLive = false),
        )
        assertEquals(
            current.copy(backend = BackendPreference.Server),
            applySelectionChange(current, changes[1], sessionLive = false),
        )
        assertEquals(
            current.copy(serverUrlRaw = url),
            applySelectionChange(current, changes[2], sessionLive = false),
        )
    }

    @Test
    fun `start args and selection never print the url`() {
        val args = StartArgs("en", BackendPreference.Server, url).toString()
        assertTrue(args, args.contains("serverUrl=set"))
        assertFalse(args, args.contains("host.example"))
        val none = StartArgs("en", BackendPreference.OnDevice, null).toString()
        assertTrue(none, none.contains("serverUrl=(none)"))

        val selection = DemoSelection(DemoLanguage.En, BackendPreference.Server, url).toString()
        assertTrue(selection, selection.contains("serverUrl=set"))
        assertFalse(selection, selection.contains("host.example"))
        val empty = DemoSelection(DemoLanguage.En, BackendPreference.Server, "").toString()
        assertTrue(empty, empty.contains("serverUrl=(none)"))

        // The other carriers of the URL redact too, so a stray log line cannot leak it.
        assertFalse(ServerUrlInput.Valid(url).toString().contains("host.example"))
        assertFalse(SelectionChange.ServerUrl(url).toString().contains("host.example"))
    }

    // --- Task 1: the URL rules (parseServerUrl is part of the tracer slice) ----------------------

    @Test
    fun `url rules in order`() {
        assertEquals(ServerUrlInput.Empty, parseServerUrl(null))
        assertEquals(ServerUrlInput.Empty, parseServerUrl(""))
        assertEquals(ServerUrlInput.Empty, parseServerUrl("   \t\n "))

        // Trim: a newline-wrapped valid URL is the trimmed Valid.
        assertEquals(ServerUrlInput.Valid(url), parseServerUrl("\n  $url \n"))

        // Length: 2048 is valid, 2049 is too long, and the length rule beats the scheme rule.
        val at2048 = "wss://" + "a".repeat(2048 - 6)
        assertEquals(2048, at2048.length)
        assertEquals(ServerUrlInput.Valid(at2048), parseServerUrl(at2048))
        assertEquals(invalid(InvalidReason.TooLong), parseServerUrl(at2048 + "a"))
        assertEquals(invalid(InvalidReason.TooLong), parseServerUrl("x".repeat(3000)))

        // Scheme: lowercase wss:// only.
        for (bad in listOf("ws://host.example/", "https://host.example/", "garbage", "WSS://host.example/")) {
            assertEquals(bad, invalid(InvalidReason.NotWss), parseServerUrl(bad))
        }

        // Whitespace inside, after the scheme rule has passed.
        assertEquals(invalid(InvalidReason.Whitespace), parseServerUrl("wss://host .example/"))
        assertEquals(invalid(InvalidReason.Whitespace), parseServerUrl("wss://host\t.example/"))
        // Rule 3 is evaluated before rule 4.
        assertEquals(invalid(InvalidReason.NotWss), parseServerUrl("ws://host .example/"))

        // Host: nothing, or only slashes, after wss://.
        for (bad in listOf("wss://", "wss:///", "wss://////")) {
            assertEquals(bad, invalid(InvalidReason.NoHost), parseServerUrl(bad))
        }
    }

    /** RF-2 (31-FOUNDATION-NOTE.md section 5, R1-06-M1): a host must exist before any ?, # or port. */
    @Test
    fun `url with no host before a query fragment port or userinfo is rejected`() {
        for (bad in listOf("wss://?x", "wss://#x", "wss://:8444", "wss://:8444/path", "wss://@/", "wss://[]/")) {
            assertEquals(bad, invalid(InvalidReason.NoHost), parseServerUrl(bad))
        }
        for (good in listOf(
            "wss://host.example",
            "wss://host.example:8444/",
            "wss://host.example?x=1",
            "wss://host.example#frag",
            "wss://user@host.example/",
            "wss://[::1]:8444/",
        )) {
            assertEquals(good, ServerUrlInput.Valid(good), parseServerUrl(good))
        }
    }

    // --- Task 2: supporting text, enablement, notes, hint, placeholder -------------------------

    @Test
    fun `supporting text is exact and never echoes`() {
        assertEquals(
            "Needed for Server and Auto. Saved on this device only.",
            supportingText(ServerUrlInput.Empty),
        )
        assertEquals("Saved on this device only.", supportingText(ServerUrlInput.Valid(url)))
        assertEquals(
            "Too long (max 2048 characters).",
            supportingText(invalid(InvalidReason.TooLong)),
        )
        assertEquals("Must start with wss://", supportingText(invalid(InvalidReason.NotWss)))
        assertEquals("Must not contain spaces.", supportingText(invalid(InvalidReason.Whitespace)))
        assertEquals(
            "Must include a host after wss://",
            supportingText(invalid(InvalidReason.NoHost)),
        )

        // For every invalid sample the text contains neither the input nor any run of it longer than
        // 6 characters other than the literal scheme.
        val samples = listOf(
            "ws://secret-looking-host-name/",
            "https://another-host-name.example/path",
            "WSS://UpperCaseHost.example/",
            "wss://spaced out host/",
            "wss://?query-material",
            "wss://:8444",
            "x".repeat(3000),
        )
        for (sample in samples) {
            val text = supportingText(parseServerUrl(sample))
            assertFalse(text, text.contains(sample))
            for (start in 0..sample.length - 7) {
                val window = sample.substring(start, start + 7)
                assertFalse("$text echoes a run of the input", text.contains(window))
            }
        }
    }

    @Test
    fun `segment enablement matrix`() {
        val inputs = listOf(
            ServerUrlInput.Empty,
            ServerUrlInput.Invalid(InvalidReason.NotWss),
            ServerUrlInput.Valid(url),
        )
        for (option in BackendPreference.entries) {
            for (input in inputs) {
                for (live in listOf(false, true)) {
                    val expected = !live &&
                        (option == BackendPreference.OnDevice || input is ServerUrlInput.Valid)
                    assertEquals(
                        "$option $input live=$live",
                        expected,
                        backendSegmentEnabled(option, input, live),
                    )
                }
            }
        }
    }

    @Test
    fun `backend note variants`() {
        val noUrl = listOf(ServerUrlInput.Empty, ServerUrlInput.Invalid(InvalidReason.Whitespace))
        for (input in noUrl) {
            assertEquals(BackendNote.NoUrl, backendNote(BackendPreference.OnDevice, input))
            assertEquals(BackendNote.Coerced, backendNote(BackendPreference.Server, input))
            assertEquals(BackendNote.Coerced, backendNote(BackendPreference.Auto, input))
        }
        for (stored in BackendPreference.entries) {
            assertEquals(BackendNote.UrlSet, backendNote(stored, ServerUrlInput.Valid(url)))
        }
        assertEquals("no server URL", BackendNote.NoUrl.text)
        assertFalse(BackendNote.NoUrl.loud)
        assertEquals("no server URL \u2014 using On-device", BackendNote.Coerced.text)
        assertTrue(BackendNote.Coerced.loud)
        assertEquals("server URL set", BackendNote.UrlSet.text)
        assertFalse(BackendNote.UrlSet.loud)
        // The note never carries the URL.
        assertFalse(BackendNote.entries.any { it.text.contains("host.example") })
    }

    @Test
    fun `settings hint copy`() {
        assertEquals("Changes apply at the next Start.", settingsHint(sessionLive = false))
        assertEquals(
            "Locked during a session. Rows show what it used.",
            settingsHint(sessionLive = true),
        )
    }

    @Test
    fun `placeholder is a single-label example host`() {
        assertEquals("wss://host.example/", SERVER_URL_PLACEHOLDER)
        assertFalse(SERVER_URL_PLACEHOLDER.contains("ts.net"))
        assertEquals(2048, MAX_SERVER_URL_CHARS)
        // The placeholder is itself a valid URL, so what the field hints at parses.
        assertEquals(
            ServerUrlInput.Valid(SERVER_URL_PLACEHOLDER),
            parseServerUrl(SERVER_URL_PLACEHOLDER),
        )
    }

    @Test
    fun `backend order labels and tag suffixes are pinned`() {
        assertEquals(
            listOf(BackendPreference.Auto, BackendPreference.Server, BackendPreference.OnDevice),
            BACKEND_OPTIONS,
        )
        assertEquals(listOf("Auto", "Server", "On-device"), BACKEND_OPTIONS.map(::backendLabel))
        assertEquals(
            listOf("stt-pick-backend-auto", "stt-pick-backend-server", "stt-pick-backend-ondevice"),
            BACKEND_OPTIONS.map(::pickBackendTag),
        )
        assertEquals(
            listOf("stt-pick-lang-auto", "stt-pick-lang-en", "stt-pick-lang-es"),
            DemoLanguage.entries.map(::pickLangTag),
        )
    }

    // --- Task 2: the settings store -------------------------------------------------------------

    @Test
    fun `store read returns defaults on an empty backend`() {
        assertEquals(DEFAULTS, DemoSettingsStore(FakeBackend()).read())
    }

    @Test
    fun `store read never throws on wrong types`() {
        val throwing = object : SettingsBackend {
            override fun getString(key: String): String? = throw ClassCastException(key)
            override fun putStrings(values: Map<String, String>) = Unit
        }
        assertEquals(DEFAULTS, DemoSettingsStore(throwing).read())

        // One bad key does not spoil the others.
        val onlyUrlBad = object : SettingsBackend {
            override fun getString(key: String): String? =
                if (key == KEY_SERVER_URL) throw ClassCastException(key) else if (key == KEY_LANGUAGE) "Es" else "Server"
            override fun putStrings(values: Map<String, String>) = Unit
        }
        assertEquals(
            DemoSelection(DemoLanguage.Es, BackendPreference.Server, ""),
            DemoSettingsStore(onlyUrlBad).read(),
        )
    }

    @Test
    fun `store read maps unknown names to defaults`() {
        val bogus = FakeBackend(KEY_LANGUAGE to "Bogus", KEY_BACKEND to "Bogus")
        assertEquals(DEFAULTS, DemoSettingsStore(bogus).read())
        // Names are matched exactly: a wire tag or a different case is not a name.
        val wire = FakeBackend(KEY_LANGUAGE to "es", KEY_BACKEND to "server")
        assertEquals(DEFAULTS, DemoSettingsStore(wire).read())

        val real = FakeBackend(
            KEY_LANGUAGE to "Es",
            KEY_BACKEND to "Server",
            KEY_SERVER_URL to " wss://host.example/ ",
        )
        assertEquals(
            DemoSelection(DemoLanguage.Es, BackendPreference.Server, "wss://host.example/"),
            DemoSettingsStore(real).read(),
        )
    }

    @Test
    fun `store write uses exact keys and trims the url`() {
        assertEquals("stt-demo-settings", PREFS_NAME)
        val backend = FakeBackend()
        DemoSettingsStore(backend).write(
            DemoSelection(DemoLanguage.Auto, BackendPreference.Auto, " wss://host.example/x "),
        )
        assertEquals(
            mapOf(
                "language" to "Auto",
                "backend" to "Auto",
                "serverUrl" to "wss://host.example/x",
            ),
            backend.values,
        )
    }

    /** RF-3 (31-FOUNDATION-NOTE.md section 5, R1-06-M2): one write is one transaction, never three. */
    @Test
    fun `store write commits all three keys in one transaction`() {
        val backend = FakeBackend()
        DemoSettingsStore(backend).write(
            DemoSelection(DemoLanguage.Es, BackendPreference.Server, url),
        )
        assertEquals(1, backend.transactions.size)
        assertEquals(
            setOf(KEY_LANGUAGE, KEY_BACKEND, KEY_SERVER_URL),
            backend.transactions.single().keys,
        )
    }

    @Test
    fun `store round trips a selection`() {
        val backend = FakeBackend()
        val store = DemoSettingsStore(backend)
        for (language in DemoLanguage.entries) {
            for (preference in BackendPreference.entries) {
                val selection = DemoSelection(language, preference, "wss://half-typed")
                store.write(selection)
                assertEquals(selection, store.read())
            }
        }
    }

    @Test
    fun `settings files never log and never use the synchronous commit`() {
        for (name in listOf("DemoSettings.kt", "DemoSettingsStore.kt")) {
            val src = demoSource(name)
            assertFalse("$name logs", Regex("""\bLog\.|println\(|Timber""").containsMatchIn(src))
        }
        assertFalse(demoSource("DemoSettingsStore.kt").contains("commit()"))
    }

    private class FakeBackend(vararg initial: Pair<String, String>) : SettingsBackend {
        val values: MutableMap<String, String> = mutableMapOf(*initial)
        val transactions = mutableListOf<Map<String, String>>()
        override fun getString(key: String): String? = values[key]
        override fun putStrings(values: Map<String, String>) {
            transactions += values
            this.values.putAll(values)
        }
    }

    // --- Task 1: wiring (source assertions; the ViewModel needs an Application) -----------------

    @Test
    fun `both ui start paths call startFromSelection`() {
        val main = demoSource("MainActivity.kt")
        assertEquals(2, Regex("""viewModel\.startFromSelection\(\)""").findAll(main).count())
        assertFalse(main.contains("viewModel.start("))
    }

    @Test
    fun `startFromSelection snapshots before start launches`() {
        val vm = demoSource("DictationViewModel.kt")
        val from = vm.indexOf("fun startFromSelection(")
        assertTrue("startFromSelection not found", from >= 0)
        val body = vm.substring(from, vm.indexOf("\n    }\n", from))
        val read = body.indexOf("_selection.value")
        val args = body.indexOf("startArgs(")
        val start = body.indexOf("start(")
        assertTrue(read in 0 until args)
        assertTrue(args in 0 until start)
        // No coroutine of its own: the launch is inside start(), after this snapshot.
        assertFalse(body.contains("launch"))
        assertNull(Regex("""_selection\.value""").findAll(body).drop(1).firstOrNull())
    }

    private fun invalid(reason: InvalidReason) = ServerUrlInput.Invalid(reason)

    private fun demoSource(name: String): String {
        val rel = "src/main/kotlin/io/github/ygaray/sttengine/demo/$name"
        val candidates = listOf(File(rel), File("android/demo/$rel"), File("demo/$rel"))
        val f = candidates.firstOrNull { it.isFile }
            ?: error("$name not found from ${File(".").absolutePath}")
        return f.readText()
    }
}
