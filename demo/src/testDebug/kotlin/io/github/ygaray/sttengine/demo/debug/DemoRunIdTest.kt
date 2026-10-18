package io.github.ygaray.sttengine.demo.debug

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral coverage for the per-START run id (Phase 21, W12 review cycle 2, finding 3):
 * [RunIdTracker], [newRunId] and [runIdDiagnostics].
 *
 * Plain JUnit, exactly like [DemoServerPathTest] and [DemoControlReceiverDiagnosticsTest]. Every
 * function under test is a pure helper, so no Context, no Robolectric and no live view model are
 * needed. [dump] builds the `runId` part of a dump the same way `DemoControlReceiver.dumpDiagnostics`
 * does: `runIdDiagnostics(this, runIds.current)`.
 */
class DemoRunIdTest {

    private val uuidV4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    private fun dump(tracker: RunIdTracker): JSONObject =
        JSONObject().apply { runIdDiagnostics(this, tracker.current) }

    @Test
    fun `before any START the dump carries an explicit null runId`() {
        val tracker = RunIdTracker()

        val snapshot = dump(tracker)

        assertNull(tracker.current)
        assertTrue("the key must be present, not just absent", snapshot.has("runId"))
        assertTrue(snapshot.isNull("runId"))
    }

    @Test
    fun `two STARTs produce different run ids`() {
        val tracker = RunIdTracker()

        val first = tracker.onStartAccepted()
        val second = tracker.onStartAccepted()

        assertNotEquals(first, second)
        assertEquals(second, tracker.current)
    }

    @Test
    fun `every dump in one session carries that session's run id, and the next START changes it`() {
        val tracker = RunIdTracker()

        val runA = tracker.onStartAccepted()
        // The driver's polling dumps, the pre-STOP dump and the post-STOP dump: STOP clears nothing.
        val sessionA = List(4) { dump(tracker).getString("runId") }

        val runB = tracker.onStartAccepted()
        val sessionB = List(3) { dump(tracker).getString("runId") }

        assertEquals(List(4) { runA }, sessionA)
        assertEquals(List(3) { runB }, sessionB)
        assertNotEquals(runA, runB)
    }

    @Test
    fun `the dump writes exactly the runId key and nothing else`() {
        val tracker = RunIdTracker { "pinned-id" }
        tracker.onStartAccepted()

        val snapshot = dump(tracker)

        assertEquals(setOf("runId"), snapshot.keys().asSequence().toSet())
        assertEquals("pinned-id", snapshot.getString("runId"))
    }

    @Test
    fun `the default run id is an opaque random v4 UUID`() {
        val ids = List(50) { newRunId() }

        // Opaque: a random UUID carries no device, host, serial or clock information (D-08).
        ids.forEach { assertTrue("not a v4 UUID: $it", uuidV4.matches(it)) }
        assertEquals("50 mints must be 50 distinct ids", 50, ids.toSet().size)
    }

    @Test
    fun `a tracker with the default minter mints UUIDs`() {
        val tracker = RunIdTracker()

        assertTrue(uuidV4.matches(tracker.onStartAccepted()))
    }
}
