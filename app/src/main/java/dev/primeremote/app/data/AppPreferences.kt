package dev.primeremote.app.data

import android.content.Context

/** Small bits of state that should survive an app restart. */
class AppPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("prime-remote", Context.MODE_PRIVATE)

    var lastHubAddress: String?
        get() = prefs.getString(KEY_LAST_HUB, null)
        set(value) = prefs.edit().putString(KEY_LAST_HUB, value).apply()

    var lastProfileId: String?
        get() = prefs.getString(KEY_LAST_PROFILE, null)
        set(value) = prefs.edit().putString(KEY_LAST_PROFILE, value).apply()

    var autoReconnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RECONNECT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RECONNECT, value).apply()

    var showTelemetryOverlay: Boolean
        get() = prefs.getBoolean(KEY_TELEMETRY_OVERLAY, true)
        set(value) = prefs.edit().putBoolean(KEY_TELEMETRY_OVERLAY, value).apply()

    /** Remembers which version of the receiver program each hub already has. */
    fun programVersionFor(address: String): String? = prefs.getString(KEY_PROGRAM_PREFIX + address, null)

    fun setProgramVersion(address: String, version: String) {
        prefs.edit().putString(KEY_PROGRAM_PREFIX + address, version).apply()
    }

    fun forgetProgramVersion(address: String) {
        prefs.edit().remove(KEY_PROGRAM_PREFIX + address).apply()
    }

    private companion object {
        const val KEY_LAST_HUB = "last_hub"
        const val KEY_LAST_PROFILE = "last_profile"
        const val KEY_AUTO_RECONNECT = "auto_reconnect"
        const val KEY_TELEMETRY_OVERLAY = "telemetry_overlay"
        const val KEY_PROGRAM_PREFIX = "program_version_"
    }
}
