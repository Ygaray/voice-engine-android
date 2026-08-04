package io.github.ygaray.sttengine.demo

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.RecognitionService
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Stable anchors for the diagnostic row, one per field, so an automated run can read a value instead
 * of screenshotting it.
 */
internal object DiagnosticsTags {
    const val SERVICES = "stt-diag-services"
    const val SELECTED = "stt-diag-selected"
    const val SEGMENT = "stt-diag-segment"
    const val RESTARTS = "stt-diag-restarts"
    const val RUNG = "stt-diag-rung"
    const val DEAF_WINDOW = "stt-diag-deaf"
}

/**
 * The demo's diagnostic instrument (D-28).
 *
 * This is not developer garnish. Speech recognition on Android fails in device-specific ways that
 * all present identically from the outside — "nothing happens when I speak" is the same symptom for
 * a missing service, an uninstalled language pack, a recognizer that reaches ready and dies, and a
 * permission that was never granted. This row is what turns that one symptom into one line of
 * answer, which is why it ships from day one and is always on screen rather than hidden behind a
 * developer toggle.
 *
 * ## A correction to D-28, applied rather than worked around
 *
 * D-28 names a `SpeechRecognizer` method for listing recognition services. **That method does not
 * exist** — an exhaustive scan of the local SDK API index finds no member of any class with that
 * name. The intent behind it (surface which service actually backs recognition on this device)
 * stands and is implemented here by the real mechanism: [android.content.pm.PackageManager
 * .queryIntentServices] over an intent whose action is [RecognitionService.SERVICE_INTERFACE].
 *
 * Two different questions get two different rows, because conflating them is how a diagnosis goes
 * wrong: the *installed* services are what the package manager can see, and the *selected* service
 * is the one the system will actually bind, read from the `voice_recognition_service` secure
 * setting. A device can have three installed and none selected.
 */
@Composable
internal fun DiagnosticsPanel(viewModel: DictationViewModel, modifier: Modifier = Modifier) {
    val services by viewModel.recognizerServices.collectAsStateWithLifecycle()
    val selected by viewModel.selectedRecognizer.collectAsStateWithLifecycle()
    val segmentId by viewModel.lastSegmentId.collectAsStateWithLifecycle()
    val restarts by viewModel.restartCount.collectAsStateWithLifecycle()
    val rung by viewModel.activeRung.collectAsStateWithLifecycle()
    val deafWindow by viewModel.lastDeafWindowMs.collectAsStateWithLifecycle()
    val diagnosticLogging by viewModel.diagnosticLogging.collectAsStateWithLifecycle()

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            DiagnosticRow(
                label = "service",
                value = services.joinToString(separator = "  ").ifEmpty { NO_SERVICES },
                tag = DiagnosticsTags.SERVICES,
            )
            DiagnosticRow(
                label = "selected",
                value = selected ?: NO_SELECTION,
                tag = DiagnosticsTags.SELECTED,
            )
            DiagnosticRow(
                label = "segment",
                value = segmentId?.toString() ?: "—",
                tag = DiagnosticsTags.SEGMENT,
            )
            DiagnosticRow(
                label = "restarts",
                value = restarts?.toString() ?: logOffPlaceholder(diagnosticLogging),
                tag = DiagnosticsTags.RESTARTS,
            )
            DiagnosticRow(
                label = "rung",
                value = rung ?: logOffPlaceholder(diagnosticLogging),
                tag = DiagnosticsTags.RUNG,
            )
            DiagnosticRow(
                label = "deaf ms",
                value = deafWindow?.toString() ?: logOffPlaceholder(diagnosticLogging),
                tag = DiagnosticsTags.DEAF_WINDOW,
            )
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String, tag: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            modifier = Modifier.width(72.dp),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline,
        )
        Text(
            text = value,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(tag),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * The three fields that only the session diagnostic can answer say WHY they are blank.
 *
 * Restart count and rung are the engine's own internals. They are deliberately not on the public
 * contract — the frozen surface is the durable asset and a demo is not a reason to grow it — so the
 * only honest source for them is the engine's opt-in JSONL trace, which is off by default because it
 * records the user's dictated speech. A dash with no explanation would read as "the engine is not
 * restarting", which is the opposite of the truth.
 */
private fun logOffPlaceholder(diagnosticLogging: Boolean): String =
    if (diagnosticLogging) "…" else "n/a (session log off)"

/** What an empty service list means. It is almost never "this device cannot do speech recognition". */
private const val NO_SERVICES = "none discovered — check the <queries> element in the manifest"

private const val NO_SELECTION = "unset (voice_recognition_service)"

// --- device queries -------------------------------------------------------------------------------

/**
 * Every recognition service the package manager will admit to, as `package/class` strings.
 *
 * **An empty list here means the manifest, not the device.** Since Android 11, package-visibility
 * filtering hides services from apps that have not declared a matching `<queries>` element, and this
 * query returns `[]` with no error when that declaration is missing. :stt's manifest carries the
 * declaration and this app inherits it by manifest merge — which is exactly why the library declares
 * it rather than the app: every consumer gets the visibility without having to know it needed to ask.
 */
internal fun queryRecognizerServices(context: Context): List<String> =
    runCatching {
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        context.packageManager.queryIntentServices(intent, 0).map { resolveInfo ->
            val service = resolveInfo.serviceInfo
            "${service.packageName}/${service.name.substringAfterLast('.')}"
        }
    }.getOrDefault(emptyList())

/**
 * The service the system will actually bind — a different question from which ones are installed.
 *
 * Read as a raw secure-setting key rather than through a constant because the platform does not
 * expose one publicly. `null` here with a non-empty service list above is a real and diagnosable
 * state: services exist, none is selected.
 */
internal fun querySelectedRecognizer(context: Context): String? =
    runCatching {
        Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
    }.getOrNull()?.takeIf { it.isNotBlank() }
