package dev.primeremote.app

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.primeremote.app.input.GamepadRouter
import dev.primeremote.app.input.TiltProvider
import dev.primeremote.app.ui.AppRoot
import dev.primeremote.app.ui.theme.PrimeRemoteTheme

class MainActivity : ComponentActivity() {

    private lateinit var controller: AppController
    private lateinit var gamepad: GamepadRouter
    private lateinit var tilt: TiltProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = (application as PrimeRemoteApp).controller
        gamepad = GamepadRouter(controller)
        tilt = TiltProvider(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            PrimeRemoteTheme {
                AppRoot(controller = controller, tilt = tilt)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        controller.onAppResumed()
    }

    override fun onPause() {
        super.onPause()
        // Never leave a motor running because the phone was put down.
        controller.onAppPaused()
        gamepad.reset()
    }

    override fun onDestroy() {
        tilt.stop()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (gamepad.onKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (gamepad.onMotion(event)) return true
        return super.onGenericMotionEvent(event)
    }
}
