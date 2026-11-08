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
