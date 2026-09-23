package dev.primeremote.core

/**
 * Details of the receiver program the app puts on the hub.
 *
 * Commands reach it as BLE tunnel messages, which SPIKE App 3 firmware delivers to the
 * running program through `hub.config["module_tunnel"]` -- a callback per message, not
 * standard input. The file has to be stored as `program.py`: a slot silently refuses to
 * run a file under any other name.
 *
 * The program itself lives at `app/src/main/assets/prime_remote_hub.py`. When the app
 * connects it starts whatever is already in [defaultSlot] and waits for the program to
 * announce itself; the upload only happens when nothing answers or the version differs,
 * which keeps reconnecting fast and spares the hub's flash.
 */
object HubProgram {

    /** Must match VERSION in prime_remote_hub.py — tools/test_hub_program.py checks this. */
    const val VERSION = "3"

    const val ASSET_NAME = "prime_remote_hub.py"

    /** File name recorded on the hub. */
    const val FILE_NAME = "program.py"

    /** Slot the app uses unless the layout says otherwise. Slot 19 is the last one. */
    const val defaultSlot = 19

    /** How long to wait for `!rdy` before deciding the program needs uploading. */
    const val READY_TIMEOUT_MS = 2500L
}
