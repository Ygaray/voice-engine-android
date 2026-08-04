package io.github.ygaray.sttengine.demo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * The reference host app's single Activity.
 *
 * Public, not `internal`, because the framework instantiates it by name from the manifest.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: DictationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Opt in explicitly rather than inheriting the platform's forced edge-to-edge. Android 15+
        // makes every targetSdk-35 window edge-to-edge whether the app asked or not; declaring it
        // means the behaviour is the same on older releases too, so the layout is not being tuned
        // against one OS version's default. DictationScreen consumes the insets.
        enableEdgeToEdge()
        setContent {
            DemoTheme {
                val context = LocalContext.current

                // ── THE ONE CONTRACT ASYMMETRY VERSUS THE WEB ENGINE (D-11) ─────────────────────
                //
                // The HOST requests RECORD_AUDIO; the LIBRARY only checks it. On the web backend the
                // engine triggers the browser's own permission prompt as part of capture, but an
                // Android library has no Activity and therefore no way to ask — so this
                // responsibility sits here, in the app, and it is not an oversight.
                //
                // The library's half of the deal is that a missing grant is a TYPED EVENT, never a
                // thrown exception: calling start() without the permission emits `mic-denied` on the
                // error stream and returns normally. So a host that skips this launcher does not
                // crash — it fails the same loud, diagnosable way every time.
                //
                // This is the snippet the adoption docs lift. Copy it whole.
                val micPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                ) {
                    // Start either way. On a grant this is the session the user asked for; on a
                    // denial the engine reports `mic-denied` in the loud banner, which is a better
                    // answer than a button that silently does nothing.
                    viewModel.start()
                }

                DictationScreen(
                    viewModel = viewModel,
                    onStartRequested = {
                        if (hasMicPermission(context)) {
                            viewModel.start()
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                )
            }
        }
    }
}

/**
 * `Context.checkSelfPermission` rather than `ActivityCompat.checkSelfPermission`.
 *
 * The platform method is API 23+ and free; the androidx.core wrapper would be a dependency added for
 * one call. :stt makes exactly the same choice for exactly the same reason, and a reference app that
 * pulls in a library the engine deliberately avoids is a reference app that teaches the wrong thing.
 */
private fun hasMicPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Plain Material 3 defaults, following the system's day/night setting.
 *
 * No bespoke palette: the reference app's job is to show the engine's behaviour, and a custom theme
 * would be one more thing a consumer copies without meaning to.
 */
@Composable
private fun DemoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
