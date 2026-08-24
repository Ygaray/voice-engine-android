package io.github.ygaray.sttengine.verifyrecorderonly

import android.app.Activity
import android.os.Bundle
import io.github.ygaray.sttengine.recorder.RecordedFile
import io.github.ygaray.sttengine.recorder.Recorder
import io.github.ygaray.sttengine.recorder.createRecorder

/**
 * A synthetic minimal consumer (plan 08-04): the ENTIRE root set R8's reachability analysis sees
 * for this module's release build. This file imports NOTHING from
 * [io.github.ygaray.sttengine.SttEngine], `createSttEngine`, or any `internal` recognition
 * symbol — only [io.github.ygaray.sttengine.recorder]'s public surface (`createRecorder`,
 * [Recorder], [RecordedFile]).
 *
 * Declared as the manifest's sole (launcher-less) Activity so AAPT's manifest-component keep
 * rule gives R8 a genuine reachability root. A plain, un-instantiated class with no manifest
 * component would be entirely unreachable and R8 would shrink this whole module to nothing — an
 * empty `mapping.txt` that would make `check-r8-mapping.sh`'s assertions vacuously true rather
 * than a real proof, exactly the failure mode `ReleaseAudioSourceSeamTest`'s
 * "assert something expected before asserting absence of something forbidden" discipline (which
 * this harness mirrors at the shell-script level) exists to catch.
 */
public class MinimalRecorderConsumer : Activity() {

    private lateinit var recorder: Recorder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recorder = createRecorder(this)
    }

    /** Exercises the full start/stop round trip so [Recorder.start]/[Recorder.stop] are reachable. */
    public suspend fun record(): RecordedFile {
        recorder.start()
        return recorder.stop()
    }

    /** Exercises [Recorder.delete] so it too is a live, reachable root for R8. */
    public suspend fun deleteRecording(uri: String) {
        recorder.delete(uri)
    }
}
