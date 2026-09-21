package dev.primeremote.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.primeremote.app.AppController
import dev.primeremote.app.input.TiltProvider
import dev.primeremote.core.model.Profile

enum class Screen { HOME, CONTROLLER, EDITOR, TELEMETRY, CONSOLE, SETTINGS }

/** Permissions this app needs, which differ a lot between Android versions. */
fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
        )
    }

fun hasPermissions(context: Context): Boolean = requiredPermissions().all {
    ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

@Composable
fun AppRoot(controller: AppController, tilt: TiltProvider) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf(Screen.HOME) }
    var editing by remember { mutableStateOf<Profile?>(null) }
    var permissionsGranted by remember { mutableStateOf(hasPermissions(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionsGranted = result.values.all { it } || hasPermissions(context)
    }

    val haptics = remember { Haptics(context) }

    LaunchedEffect(screen) {
        if (screen == Screen.CONTROLLER) controller.onControllerVisible() else controller.onControllerHidden()
    }

    DisposableEffect(Unit) {
        onDispose { controller.onControllerHidden() }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            when (screen) {
                Screen.HOME -> HomeScreen(
                    controller = controller,
                    permissionsGranted = permissionsGranted,
                    onRequestPermissions = { permissionLauncher.launch(requiredPermissions()) },
                    onOpenController = { screen = Screen.CONTROLLER },
                    onEditProfile = { profile ->
                        editing = profile
                        screen = Screen.EDITOR
                    },
                    onOpenTelemetry = { screen = Screen.TELEMETRY },
                    onOpenConsole = { screen = Screen.CONSOLE },
                    onOpenSettings = { screen = Screen.SETTINGS },
                )

                Screen.CONTROLLER -> ControllerScreen(
                    controller = controller,
                    tilt = tilt,
                    haptics = haptics,
                    onBack = { screen = Screen.HOME },
                    onEdit = {
                        editing = controller.activeProfile.value
                        screen = Screen.EDITOR
                    },
                    onOpenTelemetry = { screen = Screen.TELEMETRY },
                )

                Screen.EDITOR -> {
                    val profile = editing
                    if (profile == null) {
                        screen = Screen.HOME
                    } else {
                        EditorScreen(
                            controller = controller,
                            profile = profile,
                            onDone = { updated ->
                                controller.saveProfile(updated)
                                editing = null
                                screen = Screen.HOME
                            },
                            onCancel = {
                                editing = null
                                screen = Screen.HOME
                            },
                        )
                    }
                }

                Screen.TELEMETRY -> TelemetryScreen(controller) { screen = Screen.HOME }
                Screen.CONSOLE -> ConsoleScreen(controller) { screen = Screen.HOME }
                Screen.SETTINGS -> SettingsScreen(controller) { screen = Screen.HOME }
            }
        }
    }
}

/** Short taps of feedback when a control is pressed. */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun tap() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            v.vibrate(VibrationEffect.createOneShot(12, 60))
        } catch (e: Exception) {
            // Some devices refuse short effects; feedback is not worth crashing over.
        }
    }
}

/** Opens the system share sheet with a layout's JSON. */
fun shareText(context: Context, title: String, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_TITLE, title)
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, title).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}
