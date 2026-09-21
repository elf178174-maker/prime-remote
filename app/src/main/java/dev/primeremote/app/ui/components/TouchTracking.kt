package dev.primeremote.app.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Follows one finger on this composable from touch down to lift.
 *
 * Each control gets its own tracker, which is what makes the layout properly
 * multi-touch: holding a button with the left thumb while steering with the right is
 * two independent gestures on two composables, not one gesture that has to be shared.
 */
fun Modifier.trackFinger(
    key: Any,
    onDown: (Offset) -> Unit,
    onMove: (Offset) -> Unit = {},
    onUp: () -> Unit,
): Modifier = composed {
    val down by rememberUpdatedState(onDown)
    val move by rememberUpdatedState(onMove)
    val up by rememberUpdatedState(onUp)
    pointerInput(key) {
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false)
            first.consume()
            down(first.position)
            val pointerId = first.id
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == pointerId }
                    if (change == null) break
                    if (!change.pressed) {
                        change.consume()
                        break
                    }
                    move(change.position)
                    change.consume()
                }
            } finally {
                up()
            }
        }
    }
}
