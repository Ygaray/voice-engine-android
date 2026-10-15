package io.github.ygaray.sttengine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.sttengine.internal.FakeRecognizerHandle
import io.github.ygaray.sttengine.internal.LanguagePacks
import io.github.ygaray.sttengine.internal.RecognizerHandle
import io.github.ygaray.sttengine.internal.RecognizerHandleFactory
import java.util.Collections
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every rule of the pack preflight, `LanguagePacks.check`, pinned one row per test: which keys are
 * recognized, how every edge state reads, the transient handle's lifecycle, the main-thread hop,
 * cancellation, and parallel calls. The `download -` rows pin `LanguagePacks.requestDownload` the
 * same way: query-then-trigger on one transient handle, no trigger for an installed or pending pack,
 * nothing outside the exact keys reaching the platform, and every failure folding to `false` except
 * the caller's own cancellation.
 *
 * The preflight is an UNCACHED read: each call makes one support query on a fresh transient handle,
 * never registers a listener or starts recognition, and two concurrent calls each own their own
 * handle. `runBlocking` (real threads) is enough: `LanguagePacks` has no timer of its own, so no
 * virtual clock is needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LanguagePacksTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // --- keys -----------------------------------------------------------------------------------

    @Test
    fun `every pack installed reads INSTALLED with one create and one destroy`() = runBlocking {
        val rig = Rig(LanguageAvailability.INSTALLED)

        val result = rig.packs().check(context, setOf("en", "es"))

        assertEquals(
            "row: all installed",
            linkedMapOf("en" to LanguageAvailability.INSTALLED, "es" to LanguageAvailability.INSTALLED),
            result,
        )
        assertEquals("row: all installed — creates", 1, rig.creates)
        assertEquals("row: all installed — destroys", 1, rig.fake.destroyCount)
    }

    @Test
    fun `the caller's key order is echoed`() = runBlocking {
        val result = Rig(LanguageAvailability.INSTALLED).packs().check(context, setOf("es", "en"))

        assertEquals("row: caller order", listOf("es", "en"), result.keys.toList())
    }

    @Test
    fun `each key reads its own allowlist tag's value`() = runBlocking {
        val rig = Rig(
            LanguageAvailability.INSTALLED,
            overrides = mapOf("es-US" to LanguageAvailability.PENDING),
        )

        val result = rig.packs().check(context, setOf("en", "es"))

        assertEquals(
            "row: per-tag projection",
            linkedMapOf("en" to LanguageAvailability.INSTALLED, "es" to LanguageAvailability.PENDING),
            result,
        )
    }

    @Test
    fun `an empty set answers an empty map without touching the platform`() = runBlocking {
        val rig = Rig(LanguageAvailability.INSTALLED)

        val result = rig.packs().check(context, emptySet())

        assertTrue("row: empty set — result", result.isEmpty())
        assertEquals("row: empty set — creates", 0, rig.creates)
    }

    @Test
    fun `only the exact bare keys are recognized and nothing else touches the platform`() = runBlocking {
        val rig = Rig(LanguageAvailability.INSTALLED)
        val keys = setOf("fr", "en-US", "EN", " en", "es-ES", "")

        val result = rig.packs().check(context, keys)

        assertEquals(
            "row: strict keys",
            keys.associateWith { LanguageAvailability.UNSUPPORTED },
            result,
        )
        assertEquals("row: strict keys — order", keys.toList(), result.keys.toList())
        assertEquals("row: strict keys — creates", 0, rig.creates)
    }

    @Test
    fun `a recognized key reads the platform while an unrecognized one reads UNSUPPORTED`() = runBlocking {
        val rig = Rig(LanguageAvailability.DOWNLOADABLE)

        val result = rig.packs().check(context, setOf("en", "fr"))

        assertEquals(
            "row: mixed keys",
            linkedMapOf("en" to LanguageAvailability.DOWNLOADABLE, "fr" to LanguageAvailability.UNSUPPORTED),
            result,
        )
        assertEquals("row: mixed keys — creates", 1, rig.creates)
    }

    // --- edge states ----------------------------------------------------------------------------

    @Test
    fun `a recognizer that cannot be constructed makes every key UNSUPPORTED`() = runBlocking {
        val packs = LanguagePacks(
            handleFactory = RecognizerHandleFactory {
                throw UnsupportedOperationException("On-device recognition is not available")
            },
            mainDispatcher = Dispatchers.Unconfined,
        )

        val result = packs.check(context, setOf("en", "es", "fr"))

        assertEquals(
            "row: factory throws",
            linkedMapOf(
                "en" to LanguageAvailability.UNSUPPORTED,
                "es" to LanguageAvailability.UNSUPPORTED,
                "fr" to LanguageAvailability.UNSUPPORTED,
            ),
            result,
        )
    }

    @Test
    fun `a service that cannot tell reads UNKNOWN`() = runBlocking {
        val result = Rig(LanguageAvailability.UNKNOWN).packs().check(context, setOf("en", "es"))

        assertEquals(
            "row: service answers UNKNOWN (timeout / onError / empty lists)",
            linkedMapOf("en" to LanguageAvailability.UNKNOWN, "es" to LanguageAvailability.UNKNOWN),
            result,
        )
    }

    @Test
    fun `a throwing support query reads UNKNOWN, destroys the handle and throws nothing`() = runBlocking {
        val fake = FakeRecognizerHandle()
        val packs = packsFor(QueryThrowsHandle(fake))

        val result = packs.check(context, setOf("en", "es", "fr"))

        assertEquals(
            "row: query throws",
            linkedMapOf(
                "en" to LanguageAvailability.UNKNOWN,
                "es" to LanguageAvailability.UNKNOWN,
                "fr" to LanguageAvailability.UNSUPPORTED,
            ),
            result,
        )
        assertEquals("row: query throws — destroys", 1, fake.destroyCount)
    }

    // --- the query and the handle ---------------------------------------------------------------

    @Test
    fun `a single-key preflight still asks for the full allowlist`() = runBlocking {
        val rig = Rig(LanguageAvailability.INSTALLED)

        rig.packs().check(context, setOf("es"))

        assertEquals(
            "row: one key — the platform must be asked the same question start() asks",
            listOf(listOf("en-US", "es-US")),
            rig.fake.languageQueries,
        )
    }

    @Test
    fun `the transient handle never registers a listener or starts recognition`() = runBlocking {
        val rig = Rig(LanguageAvailability.INSTALLED)

        rig.packs().check(context, setOf("en", "es"))

        assertEquals("row: no listener", 0, rig.fake.setListenerCount)
        assertEquals("row: no recognition", 0, rig.fake.startCount)
    }

    @Test
    fun `creation and the query both run on the injected main dispatcher`() {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "pack-main") }
        val mainDispatcher = executor.asCoroutineDispatcher()
        try {
            val threads = Collections.synchronizedList(mutableListOf<String>())
            val fake = FakeRecognizerHandle()
            val packs = LanguagePacks(
                handleFactory = RecognizerHandleFactory {
                    threads += "create:" + currentThreadName()
                    ThreadRecordingHandle(fake, threads)
                },
                mainDispatcher = mainDispatcher,
            )

            val result = runBlocking { packs.check(context, setOf("en", "es")) }

            assertEquals(
                "row: main hop — create and query must both run on the main dispatcher",
                listOf("create:pack-main", "query:pack-main"),
                threads.toList(),
            )
            assertEquals(
                "row: main hop — result",
                linkedMapOf("en" to LanguageAvailability.INSTALLED, "es" to LanguageAvailability.INSTALLED),
                result,
            )
            assertEquals("row: main hop — destroys", 1, fake.destroyCount)
        } finally {
            mainDispatcher.close()
        }
    }

    // --- cancellation ---------------------------------------------------------------------------

    @Test
    fun `cancelling the caller completes it as cancelled and still destroys the handle`() = runBlocking {
        val fake = FakeRecognizerHandle()
        val queryStarted = CompletableDeferred<Unit>()
        val packs = packsFor(SuspendForeverHandle(fake, queryStarted))
        var result: Map<String, LanguageAvailability>? = null

        val job = launch { result = packs.check(context, setOf("en", "es")) }
        queryStarted.await()
        job.cancel()
        job.join()

        assertTrue("row: cancel — job cancelled", job.isCancelled)
        assertNull("row: cancel — a cancelled caller must not get a map", result)
        assertEquals("row: cancel — destroys", 1, fake.destroyCount)
    }

    @Test
    fun `cancellation stays primary when destroy also throws`() = runBlocking {
        val fake = FakeRecognizerHandle()
        val queryStarted = CompletableDeferred<Unit>()
        val packs = packsFor(
            SuspendForeverHandle(fake, queryStarted, destroyThrows = IllegalStateException("destroy failed")),
        )
        var thrown: Throwable? = null

        val job = launch {
            try {
                packs.check(context, setOf("en", "es"))
            } catch (t: Throwable) {
                thrown = t
                throw t
            }
        }
        queryStarted.await()
        job.cancel()
        job.join()

        val primary = thrown
        assertTrue("row: cancel + destroy throws — job cancelled", job.isCancelled)
        assertTrue(
            "row: cancel + destroy throws — primary must be the cancellation, was $primary",
            primary is CancellationException,
        )
        // The chain, not just the top: with coroutine debug mode on (JVM assertions, as under
        // Gradle), stack-trace recovery rethrows a COPY of the cancellation whose `cause` is the
        // original — and the suppressed failure rides on the original. Every link must still be a
        // cancellation; the destroy failure may appear only as a suppressed exception.
        val chain = generateSequence(primary) { it.cause }.toList()
        assertTrue(
            "row: cancel + destroy throws — every link must be the cancellation, was $chain",
            chain.all { it is CancellationException && it.message != "destroy failed" },
        )
        val suppressed = chain.flatMap { it.suppressed.toList() }.distinct()
        assertEquals("row: cancel + destroy throws — one suppressed", 1, suppressed.size)
        assertTrue(
            "row: cancel + destroy throws — suppressed is the destroy failure",
            suppressed.single() is IllegalStateException && suppressed.single().message == "destroy failed",
        )
        assertFalse(
            "row: cancel + destroy throws — the destroy failure must not escape on its own",
            suppressed.single() is CancellationException,
        )
        assertEquals("row: cancel + destroy throws — destroy attempted", 1, fake.destroyCount)
    }

    // --- cleanup --------------------------------------------------------------------------------

    @Test
    fun `a destroy that throws after a good query reads UNKNOWN and throws nothing`() = runBlocking {
        val fake = FakeRecognizerHandle()
        val packs = packsFor(DestroyThrowsHandle(fake))

        val result = packs.check(context, setOf("en", "es", "fr"))

        assertEquals(
            "row: destroy throws after success — the computed answer is not trusted",
            linkedMapOf(
                "en" to LanguageAvailability.UNKNOWN,
                "es" to LanguageAvailability.UNKNOWN,
                "fr" to LanguageAvailability.UNSUPPORTED,
            ),
            result,
        )
        assertEquals("row: destroy throws after success — destroy attempted", 1, fake.destroyCount)
    }

    // --- parallelism ----------------------------------------------------------------------------

    @Test
    fun `two concurrent calls each own a fresh handle`() = runBlocking {
        val fakes = mutableListOf<FakeRecognizerHandle>()
        val bothInFlight = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val packs = LanguagePacks(
            handleFactory = RecognizerHandleFactory {
                val fake = FakeRecognizerHandle().also { fakes += it }
                if (fakes.size == 2) bothInFlight.complete(Unit)
                GatedHandle(fake, release)
            },
            mainDispatcher = Dispatchers.Unconfined,
        )

        val first = async { packs.check(context, setOf("en", "es")) }
        val second = async { packs.check(context, setOf("es")) }
        bothInFlight.await()
        release.complete(Unit)

        assertEquals(
            "row: parallel — first",
            linkedMapOf("en" to LanguageAvailability.INSTALLED, "es" to LanguageAvailability.INSTALLED),
            first.await(),
        )
        assertEquals(
            "row: parallel — second",
            linkedMapOf("es" to LanguageAvailability.INSTALLED),
            second.await(),
        )
        assertEquals("row: parallel — creates", 2, fakes.size)
        fakes.forEachIndexed { i, fake ->
            assertEquals("row: parallel — handle $i destroys", 1, fake.destroyCount)
        }
    }

    // ===========================================================================================
    // requestDownload — query-then-trigger on one transient handle
    // ===========================================================================================

    // --- the short-circuit and the trigger ------------------------------------------------------

    @Test
    fun `download - an installed pack reads true without asking the platform`() = runBlocking {
        val rig = Rig(LanguageAvailability.INSTALLED)

        val accepted = rig.packs().requestDownload(context, "es")

        assertTrue("download row: installed → true", accepted)
        assertEquals("download row: installed — no trigger", emptyList<String>(), rig.fake.downloadRequests)
        assertEquals("download row: installed — creates", 1, rig.creates)
        assertEquals("download row: installed — destroys", 1, rig.fake.destroyCount)
    }

    @Test
    fun `download - a pending pack reads true without asking the platform again`() = runBlocking {
        val rig = Rig(LanguageAvailability.PENDING)

        val accepted = rig.packs().requestDownload(context, "es")

        assertTrue("download row: pending → true", accepted)
        assertEquals("download row: pending — no trigger", emptyList<String>(), rig.fake.downloadRequests)
    }

    @Test
    fun `download - an unsupported pack reads false without a trigger`() = runBlocking {
        val rig = Rig(LanguageAvailability.UNSUPPORTED)

        val accepted = rig.packs().requestDownload(context, "es")

        assertFalse("download row: unsupported → false", accepted)
        assertEquals("download row: unsupported — no trigger", emptyList<String>(), rig.fake.downloadRequests)
    }

    @Test
    fun `download - a downloadable pack triggers its exact tag and reports the platform's answer`() = runBlocking {
        val accepting = Rig(LanguageAvailability.DOWNLOADABLE).apply { fake.downloadResult = true }
        assertTrue("download row: downloadable + accepted → true", accepting.packs().requestDownload(context, "es"))
        assertEquals("download row: downloadable — exact tag", listOf("es-US"), accepting.fake.downloadRequests)

        val refusing = Rig(LanguageAvailability.DOWNLOADABLE).apply { fake.downloadResult = false }
        assertFalse("download row: downloadable + refused → false", refusing.packs().requestDownload(context, "es"))
        assertEquals("download row: downloadable refused — tried", listOf("es-US"), refusing.fake.downloadRequests)
    }

    @Test
    fun `download - an undetermined pack still attempts the trigger`() = runBlocking {
        val rig = Rig(LanguageAvailability.UNKNOWN).apply { fake.downloadResult = true }

        val accepted = rig.packs().requestDownload(context, "es")

        assertTrue("download row: unknown → trigger result", accepted)
        assertEquals("download row: unknown — trigger attempted", listOf("es-US"), rig.fake.downloadRequests)
    }

    @Test
    fun `download - en triggers en-US`() = runBlocking {
        val rig = Rig(LanguageAvailability.DOWNLOADABLE).apply { fake.downloadResult = true }

        rig.packs().requestDownload(context, "en")

        assertEquals("download row: en → en-US", listOf("en-US"), rig.fake.downloadRequests)
    }

    // --- keys and construction ------------------------------------------------------------------

    @Test
    fun `download - only the exact bare keys reach the platform`() = runBlocking {
        for (key in listOf("fr", "en-US", "EN", "es-ES", "")) {
            val rig = Rig(LanguageAvailability.DOWNLOADABLE).apply { fake.downloadResult = true }

            val accepted = rig.packs().requestDownload(context, key)

            assertFalse("download row: key \"$key\" → false", accepted)
            assertEquals("download row: key \"$key\" — creates", 0, rig.creates)
            assertEquals("download row: key \"$key\" — no trigger", emptyList<String>(), rig.fake.downloadRequests)
        }
    }

    @Test
    fun `download - a recognizer that cannot be constructed reads false`() = runBlocking {
        val packs = LanguagePacks(
            handleFactory = RecognizerHandleFactory {
                throw UnsupportedOperationException("On-device recognition is not available")
            },
            mainDispatcher = Dispatchers.Unconfined,
        )

        assertFalse("download row: factory throws → false", packs.requestDownload(context, "es"))
    }

    // --- failures on the handle -----------------------------------------------------------------

    @Test
    fun `download - a trigger that throws reads false and still destroys the handle`() = runBlocking {
        val fake = FakeRecognizerHandle()
        val packs = packsFor(DownloadHandle(fake, triggerThrows = IllegalStateException("binder died")))

        val accepted = packs.requestDownload(context, "es")

        assertFalse("download row: trigger throws → false", accepted)
        assertEquals("download row: trigger throws — destroys", 1, fake.destroyCount)
    }

    @Test
    fun `download - a throwing support query reads as undetermined and the trigger is attempted`() = runBlocking {
        val fake = FakeRecognizerHandle().apply { downloadResult = true }
        val packs = packsFor(
            PreflightHandle(fake, LanguageAvailability.INSTALLED, listQueryThrows = IllegalStateException("query failed")),
        )

        val accepted = packs.requestDownload(context, "es")

        assertTrue("download row: query throws → trigger result", accepted)
        assertEquals("download row: query throws — trigger attempted", listOf("es-US"), fake.downloadRequests)
        assertEquals("download row: query throws — destroys", 1, fake.destroyCount)
    }

    @Test
    fun `download - a destroy that throws after an accepted trigger reads false`() = runBlocking {
        val fake = FakeRecognizerHandle().apply { downloadResult = true }
        val packs = packsFor(DownloadHandle(fake, destroyThrows = IllegalStateException("destroy failed")))

        val accepted = packs.requestDownload(context, "es")

        assertFalse("download row: destroy throws after accept → not confirmed", accepted)
        assertEquals("download row: destroy throws — trigger ran", listOf("es-US"), fake.downloadRequests)
        assertEquals("download row: destroy throws — destroy attempted", 1, fake.destroyCount)
    }

    @Test
    fun `download - a destroy that throws on the installed path reads false`() = runBlocking {
        val fake = FakeRecognizerHandle()
        val packs = packsFor(
            DownloadHandle(
                fake,
                availability = LanguageAvailability.INSTALLED,
                destroyThrows = IllegalStateException("destroy failed"),
            ),
        )

        val accepted = packs.requestDownload(context, "es")

        assertFalse("download row: installed + destroy throws → false", accepted)
        assertEquals("download row: installed + destroy throws — no trigger", emptyList<String>(), fake.downloadRequests)
    }

    // --- cancellation ---------------------------------------------------------------------------

    @Test
    fun `download - cancelling the caller completes it as cancelled and still destroys the handle`() = runBlocking {
        val fake = FakeRecognizerHandle()
        val triggerStarted = CompletableDeferred<Unit>()
        val packs = packsFor(DownloadHandle(fake, triggerStarted = triggerStarted, triggerSuspends = true))
        var result: Boolean? = null

        val job = launch { result = packs.requestDownload(context, "es") }
        withTimeout(DEADLINE_MS) { triggerStarted.await() }
        job.cancel()
        job.join()

        assertTrue("download row: cancel — job cancelled", job.isCancelled)
        assertNull("download row: cancel — a cancelled caller must not get false", result)
        assertEquals("download row: cancel — destroys", 1, fake.destroyCount)
    }

    @Test
    fun `download - cancellation stays primary and a throwing destroy is only suppressed`() = runBlocking {
        val fake = FakeRecognizerHandle()
        val triggerStarted = CompletableDeferred<Unit>()
        val packs = packsFor(
            DownloadHandle(
                fake,
                triggerStarted = triggerStarted,
                triggerSuspends = true,
                destroyThrows = IllegalStateException("destroy failed"),
            ),
        )
        var thrown: Throwable? = null

        val job = launch {
            try {
                packs.requestDownload(context, "es")
            } catch (t: Throwable) {
                thrown = t
                throw t
            }
        }
        withTimeout(DEADLINE_MS) { triggerStarted.await() }
        job.cancel()
        job.join()

        val primary = thrown
        assertTrue("download row: cancel + destroy throws — job cancelled", job.isCancelled)
        assertTrue(
            "download row: cancel + destroy throws — primary must be the cancellation, was $primary",
            primary is CancellationException,
        )
        // Walk the chain: under coroutine debug mode the caller sees a COPY whose cause is the
        // original, and the suppressed failure rides on the original (see the check() row above).
        val chain = generateSequence(primary) { it.cause }.toList()
        assertTrue(
            "download row: cancel + destroy throws — every link must be the cancellation, was $chain",
            chain.all { it is CancellationException && it.message != "destroy failed" },
        )
        val suppressed = chain.flatMap { it.suppressed.toList() }.distinct()
        assertEquals("download row: cancel + destroy throws — one suppressed", 1, suppressed.size)
        assertTrue(
            "download row: cancel + destroy throws — suppressed is the destroy failure",
            suppressed.single() is IllegalStateException && suppressed.single().message == "destroy failed",
        )
        assertEquals("download row: cancel + destroy throws — destroy attempted", 1, fake.destroyCount)
    }

    // --- parallelism, the shared query, and the main hop ----------------------------------------

    @Test
    fun `download - two concurrent calls each own a fresh handle and trigger once each`() = runBlocking {
        val fakes = Collections.synchronizedList(mutableListOf<FakeRecognizerHandle>())
        val bothInFlight = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val packs = LanguagePacks(
            handleFactory = RecognizerHandleFactory {
                val fake = FakeRecognizerHandle().apply { downloadResult = true }.also { fakes += it }
                if (fakes.size == 2) bothInFlight.complete(Unit)
                DownloadHandle(fake, triggerGate = release)
            },
            mainDispatcher = Dispatchers.Unconfined,
        )

        val first = async { packs.requestDownload(context, "es") }
        val second = async { packs.requestDownload(context, "es") }
        withTimeout(DEADLINE_MS) { bothInFlight.await() }
        release.complete(Unit)

        assertTrue("download row: parallel — first", first.await())
        assertTrue("download row: parallel — second", second.await())
        assertEquals("download row: parallel — creates", 2, fakes.size)
        fakes.forEachIndexed { i, fake ->
            assertEquals("download row: parallel — handle $i destroys", 1, fake.destroyCount)
            assertEquals("download row: parallel — handle $i triggers", listOf("es-US"), fake.downloadRequests)
        }
    }

    @Test
    fun `download - asks the full allowlist and triggers on the injected main dispatcher`() {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "pack-main") }
        val mainDispatcher = executor.asCoroutineDispatcher()
        try {
            val threads = Collections.synchronizedList(mutableListOf<String>())
            val fake = FakeRecognizerHandle().apply { downloadResult = true }
            val packs = LanguagePacks(
                handleFactory = RecognizerHandleFactory { DownloadHandle(fake, threads = threads) },
                mainDispatcher = mainDispatcher,
            )

            val accepted = runBlocking { packs.requestDownload(context, "es") }

            assertTrue("download row: main hop — result", accepted)
            assertEquals(
                "download row: the shared query — the full allowlist, never just es",
                listOf(listOf("en-US", "es-US")),
                fake.languageQueries,
            )
            assertEquals(
                "download row: main hop — query and trigger both on the main dispatcher",
                listOf("query:pack-main", "trigger:pack-main"),
                threads.toList(),
            )
        } finally {
            mainDispatcher.close()
        }
    }

    // -------------------------------------------------------------------------------------------

    /**
     * A scriptable handle for the download rows. Its list query answers [availability] for every tag
     * (recording the list, like [PreflightHandle]); its trigger records on the fake, then optionally
     * waits on [triggerGate], suspends forever, or throws; its destroy optionally throws.
     */
    private class DownloadHandle(
        private val delegate: FakeRecognizerHandle,
        private val availability: LanguageAvailability = LanguageAvailability.DOWNLOADABLE,
        private val triggerThrows: RuntimeException? = null,
        private val triggerStarted: CompletableDeferred<Unit>? = null,
        private val triggerSuspends: Boolean = false,
        private val triggerGate: CompletableDeferred<Unit>? = null,
        private val destroyThrows: RuntimeException? = null,
        private val threads: MutableList<String>? = null,
    ) : RecognizerHandle by delegate {
        override suspend fun languageAvailability(languages: List<String>): Map<String, LanguageAvailability> {
            threads?.add("query:" + currentThreadName())
            delegate.languageQueries += languages
            return languages.associateWith { availability }
        }

        override suspend fun triggerModelDownload(language: String): Boolean {
            threads?.add("trigger:" + currentThreadName())
            val answer = delegate.triggerModelDownload(language)
            triggerStarted?.complete(Unit)
            triggerGate?.await()
            if (triggerSuspends) awaitCancellation()
            triggerThrows?.let { throw it }
            return answer
        }

        override fun destroy() {
            delegate.destroy()
            destroyThrows?.let { throw it }
        }
    }

    /** A counting factory over one [PreflightHandle], with an eager in-place "main" dispatcher. */
    private class Rig(
        availability: LanguageAvailability,
        overrides: Map<String, LanguageAvailability> = emptyMap(),
    ) {
        val fake = FakeRecognizerHandle()
        var creates = 0
            private set
        private val handle = PreflightHandle(fake, availability, overrides)

        fun packs(dispatcher: CoroutineDispatcher = Dispatchers.Unconfined): LanguagePacks =
            LanguagePacks(
                handleFactory = RecognizerHandleFactory {
                    creates += 1
                    handle
                },
                mainDispatcher = dispatcher,
            )
    }

    private companion object {
        /** The thread's name without coroutine debug mode's ` @coroutine#N` suffix. */
        fun currentThreadName(): String = Thread.currentThread().name.substringBefore(" @")

        /** Upper bound on waiting for a handle to reach a scripted point — a hang fails, not stalls. */
        const val DEADLINE_MS = 5_000L
    }

    private fun packsFor(handle: RecognizerHandle): LanguagePacks =
        LanguagePacks(handleFactory = RecognizerHandleFactory { handle }, mainDispatcher = Dispatchers.Unconfined)

    private class QueryThrowsHandle(delegate: FakeRecognizerHandle) : RecognizerHandle by delegate {
        override suspend fun languageAvailability(languages: List<String>): Map<String, LanguageAvailability> =
            throw IllegalStateException("query failed")
    }

    private class DestroyThrowsHandle(private val delegate: FakeRecognizerHandle) : RecognizerHandle by delegate {
        override suspend fun languageAvailability(languages: List<String>): Map<String, LanguageAvailability> =
            languages.associateWith { LanguageAvailability.INSTALLED }

        override fun destroy() {
            delegate.destroy()
            throw IllegalStateException("destroy failed")
        }
    }

    private class SuspendForeverHandle(
        private val delegate: FakeRecognizerHandle,
        private val queryStarted: CompletableDeferred<Unit>,
        private val destroyThrows: RuntimeException? = null,
    ) : RecognizerHandle by delegate {
        override suspend fun languageAvailability(languages: List<String>): Map<String, LanguageAvailability> {
            queryStarted.complete(Unit)
            awaitCancellation()
        }

        override fun destroy() {
            delegate.destroy()
            destroyThrows?.let { throw it }
        }
    }

    private class ThreadRecordingHandle(
        delegate: FakeRecognizerHandle,
        private val threads: MutableList<String>,
    ) : RecognizerHandle by delegate {
        override suspend fun languageAvailability(languages: List<String>): Map<String, LanguageAvailability> {
            threads += "query:" + currentThreadName()
            return languages.associateWith { LanguageAvailability.INSTALLED }
        }
    }

    private class GatedHandle(
        delegate: FakeRecognizerHandle,
        private val release: CompletableDeferred<Unit>,
    ) : RecognizerHandle by delegate {
        override suspend fun languageAvailability(languages: List<String>): Map<String, LanguageAvailability> {
            release.await()
            return languages.associateWith { LanguageAvailability.INSTALLED }
        }
    }
}
