package io.github.ygaray.sttengine.demo

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.ygaray.sttengine.EngineState
import io.github.ygaray.sttengine.FinalSegment
import io.github.ygaray.sttengine.SttError
import kotlinx.coroutines.launch

/**
 * Stable automation anchors, mirroring the `data-testid` values the web reference demo uses so the
 * same names identify the same things on both backends.
 *
 * These are not decoration. They are what lets an agent read the screen without screen coordinates,
 * and — combined with `testTagsAsResourceId` below — they surface as `resource-id` in a uiautomator
 * dump, which is the only stable handle a layout-independent assertion has.
 */
internal object TestTags {
    const val PARTIAL = "stt-partial"
    const val FINAL = "stt-final"
    const val ERROR = "stt-error"
    const val STATE = "stt-state"
    const val LEVEL = "stt-level"
    const val TRANSCRIPT = "stt-transcript"
    const val START = "stt-start"
    const val STOP = "stt-stop"
}

/**
 * The single dictation screen — the Android counterpart of `web/demo/main.ts`, element for element.
 *
 * It consumes **only** the library's public contract. There is no import from an internal package
 * anywhere in this module, which is the property that makes this a worked example rather than a
 * privileged insider: everything done here is available to any consumer that resolves the published
 * artifact.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DictationScreen(
    viewModel: DictationViewModel,
    onStartRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The engine instance itself is observed: a debug-only diagnostic run mints a new one, and the
    // collectors below have to move with it. In every other run this never changes.
    val engine by viewModel.engine.collectAsStateWithLifecycle()

    // D-07's per-event StateFlows exist precisely so a recomposing host can read the CURRENT value
    // rather than waiting for the next emission. This is the documented consumer of that design.
    val state by engine.state.collectAsStateWithLifecycle()
    val partial by engine.partial.collectAsStateWithLifecycle()

    val committed by viewModel.committed.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val level by viewModel.level.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current

    // The three channel-and-conflation-backed streams. ONE collector each, at the highest point in
    // the tree, keyed on the engine instance.
    //
    // Committed segments are single-consumer by construction — the contract says so, and the reason
    // is that the stream is backed by an unlimited channel so nothing is lost while a lifecycle-aware
    // host is detached. A second collector anywhere in this module would split the user's dictation
    // between two lists with no error to show for it. Detaching here on STOP is safe *because* of
    // that buffer: everything spoken while the app was backgrounded arrives on reattach, in order.
    LaunchedEffect(engine, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { engine.finals.collect(viewModel::onSegmentCommitted) }
            launch { engine.errors.collect(viewModel::onEngineError) }
            launch { engine.level.collect(viewModel::onLevel) }
        }
    }

    val scrollState = rememberScrollState()
    // Follow the tail: a dictation transcript the user has to scroll is a transcript they stop
    // trusting. Keyed on both, because a partial grows between finals.
    LaunchedEffect(committed.size, partial) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            // Publishes every testTag below as an Android `resource-id`, which is what makes the
            // anchors readable from a uiautomator dump over adb.
            .semantics { testTagsAsResourceId = true },
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StateBanner(state = state, level = level)

            error?.let { ErrorBanner(error = it) }

            Transcript(
                committed = committed,
                partial = partial,
                isIdleAndEmpty = state == EngineState.Idle && committed.isEmpty(),
                scrollState = scrollState,
                modifier = Modifier.weight(1f),
            )

            Controls(
                sessionLive = viewModel.sessionLive(state),
                onStart = onStartRequested,
                onStop = viewModel::stop,
            )
        }
    }
}

// --- state + level ------------------------------------------------------------------------------

/**
 * The state banner and the level meter.
 *
 * The banner renders [EngineState.wireName] — `idle` / `connecting` / `listening` / `reconnecting` /
 * `error` — rather than a prettied-up label, so it reads identically to the web demo and so a shared
 * bug report says the same word on both backends.
 */
@Composable
private fun StateBanner(state: EngineState, level: Float) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(stateColor(state)),
        )
        Text(
            text = state.wireName,
            modifier = Modifier.testTag(TestTags.STATE),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.weight(1f))
        LevelMeter(level = level, listening = state == EngineState.Listening)
    }
}

@Composable
private fun stateColor(state: EngineState): Color = when (state) {
    EngineState.Idle -> MaterialTheme.colorScheme.outline
    EngineState.Connecting -> MaterialTheme.colorScheme.tertiary
    EngineState.Listening -> MaterialTheme.colorScheme.primary
    EngineState.Reconnecting -> MaterialTheme.colorScheme.tertiary
    EngineState.Error -> MaterialTheme.colorScheme.error
}

/**
 * A horizontal bar whose fill is the level clamped to `0.0..1.0`, plus the numeric value.
 *
 * The number is rendered as text on purpose: a bar's width is not readable from a uiautomator dump,
 * so without it an automated run could see that the meter exists but never what it said.
 *
 * The meter is live only while listening. It does not snap to zero at a restart — see the decay
 * ticker in [DictationViewModel] for why that matters on this device.
 */
@Composable
private fun LevelMeter(level: Float, listening: Boolean) {
    val clamped = level.coerceIn(0f, 1f)
    Row(
        modifier = Modifier.testTag(TestTags.LEVEL),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = formatLevel(clamped),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .width(96.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                // Dimmed rather than hidden when not listening: the meter's absence would be
                // ambiguous with a meter reading zero.
                .alpha(if (listening) 1f else 0.4f),
        ) {
            if (clamped > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(clamped)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

/** Two decimals, allocation-cheap, locale-independent — a meter readout is not prose. */
private fun formatLevel(value: Float): String {
    val hundredths = (value * 100f + 0.5f).toInt().coerceIn(0, 100)
    return "0.${(hundredths / 10)}${hundredths % 10}".let {
        if (hundredths == 100) "1.00" else it
    }
}

// --- errors ---------------------------------------------------------------------------------------

/**
 * The loud error banner. Always shows the RAW kebab-case code.
 *
 * Format: the code in brackets, the engine's own message, the raw platform integer when there is
 * one, and only then the demo-owned friendly hint. That order is deliberate — a banner that leads
 * with reassuring copy and buries the code is a banner that costs an hour of guessing, and
 * `platformDetail` is frequently the only thing that distinguishes two failures the taxonomy maps to
 * the same code.
 *
 * Rendered as plain text through Compose's `Text`, which does not interpret markup. Recognizer
 * output is untrusted input and never becomes markup or a command here.
 */
@Composable
private fun ErrorBanner(error: SttError) {
    val hint = remember(error) { ErrorHints.forCode(error.code) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.ERROR),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = buildString {
                    append("ERROR [")
                    append(error.code.value)
                    append("] ")
                    append(error.message)
                    error.platformDetail?.let {
                        append(" · platformDetail=")
                        append(it)
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            hint?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// --- transcript -----------------------------------------------------------------------------------

/**
 * Committed finals as immutable appended blocks, with the live partial trailing beneath them.
 *
 * The visual distinction between the two IS the requirement, not styling:
 *
 * - a **final** is settled — normal weight, full opacity, its own block, and it is never rewritten;
 * - a **partial** is provisional — muted colour, italic, replaced in place on every emission.
 *
 * A reader has to be able to tell at a glance which words are safe to rely on. Rendering them alike
 * would make the engine's central guarantee — that a final is immutable — invisible.
 */
@Composable
private fun Transcript(
    committed: List<FinalSegment>,
    partial: String,
    isIdleAndEmpty: Boolean,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TestTags.TRANSCRIPT),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (isIdleAndEmpty) {
                Text(
                    text = "Tap Start and speak. Live text appears here; each pause commits a block.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            committed.forEach { segment ->
                FinalBlock(segment)
            }

            // Always present, even when empty, so the anchor exists before the first word arrives —
            // an automation that has to wait for a node to appear cannot distinguish "no partial
            // yet" from "the screen is broken".
            Text(
                text = partial,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.PARTIAL),
                style = MaterialTheme.typography.bodyLarge,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FinalBlock(segment: FinalSegment) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "#${segment.segmentId}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline,
        )
        Text(
            text = segment.text,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.FINAL),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// --- controls -------------------------------------------------------------------------------------

/**
 * Start and Stop, enabled straight off the engine state.
 *
 * A session counts as live for `connecting`, `listening` and `reconnecting` — the same three the web
 * demo uses — so Start is available again exactly when a session has genuinely ended.
 */
@Composable
private fun Controls(sessionLive: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = onStart,
            enabled = !sessionLive,
            modifier = Modifier
                .weight(1f)
                .testTag(TestTags.START),
        ) {
            Text("Start")
        }
        OutlinedButton(
            onClick = onStop,
            enabled = sessionLive,
            modifier = Modifier
                .weight(1f)
                .testTag(TestTags.STOP),
        ) {
            Text("Stop")
        }
    }
}
