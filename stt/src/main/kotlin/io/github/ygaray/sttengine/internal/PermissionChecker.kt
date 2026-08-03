package io.github.ygaray.sttengine.internal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/**
 * The default [PermissionChecker]: `Context.checkSelfPermission` against `RECORD_AUDIO`.
 *
 * `Context.checkSelfPermission` is API 23+ and costs no androidx dependency —
 * `ContextCompat.checkSelfPermission` would drag `androidx.core` into a library whose whole selling
 * point is one runtime dependency (`android/stt/build.gradle.kts`, D-09).
 *
 * **The library only CHECKS.** Requesting is the host's job, because a library has no Activity to
 * request from (D-11). A denial here is a pre-flight `mic-denied` (D-18's non-`onError` table), not
 * a prompt.
 *
 * @param context Use an application context; this object outlives no Activity and holds the
 *   reference for the process lifetime of the engine.
 */
internal class ContextPermissionChecker(
    private val context: Context,
) : PermissionChecker {

    override fun hasRecordAudio(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}
