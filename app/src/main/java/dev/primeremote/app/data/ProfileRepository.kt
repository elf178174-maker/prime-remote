package dev.primeremote.app.data

import android.content.Context
import android.util.Log
import dev.primeremote.core.model.Presets
import dev.primeremote.core.model.Profile
import dev.primeremote.core.model.ProfileJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Stores layouts as a single JSON file in the app's private storage.
 *
 * Writes go to a temporary file first and are then renamed over the real one, so a crash
 * mid-save cannot leave a half-written file behind and lose every layout.
 */
class ProfileRepository(context: Context) {

    private val file = File(context.filesDir, "layouts.json")
    private val tempFile = File(context.filesDir, "layouts.json.tmp")

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    init {
        _profiles.value = load()
    }

    private fun load(): List<Profile> {
        if (!file.exists()) return defaults()
        return try {
            val text = file.readText()
            val loaded = ProfileJson.decodeAll(text)
            loaded.ifEmpty { defaults() }
        } catch (e: Exception) {
            Log.w(TAG, "could not read saved layouts, falling back to the presets", e)
            // Keep the unreadable file around so nothing is silently destroyed.
            try {
                file.copyTo(File(file.parentFile, "layouts.corrupt.json"), overwrite = true)
            } catch (copyError: Exception) {
                Log.w(TAG, "could not preserve the unreadable file", copyError)
            }
            defaults()
        }
    }

    private fun defaults(): List<Profile> = listOf(Presets.arcadeDrive(), Presets.tankDrive())

    private fun persist(profiles: List<Profile>) {
        _profiles.value = profiles
        try {
            tempFile.writeText(ProfileJson.encodeAll(profiles))
            if (!tempFile.renameTo(file)) {
                file.writeText(tempFile.readText())
                tempFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "could not save layouts", e)
        }
    }

    fun upsert(profile: Profile) {
        val existing = _profiles.value
        val index = existing.indexOfFirst { it.id == profile.id }
        persist(if (index >= 0) existing.toMutableList().also { it[index] = profile } else existing + profile)
    }

    fun delete(profileId: String) {
        val remaining = _profiles.value.filterNot { it.id == profileId }
        persist(remaining.ifEmpty { defaults() })
    }

    fun duplicate(profileId: String): Profile? {
        val original = _profiles.value.firstOrNull { it.id == profileId } ?: return null
        val copy = ProfileJson.reId(original).copy(name = "${original.name} copy")
        upsert(copy)
        return copy
    }

    fun add(profile: Profile): Profile {
        upsert(profile)
        return profile
    }

    /** Import one or more layouts from exported JSON. Returns the ones that were added. */
    fun import(text: String): List<Profile> {
        val imported = ProfileJson.decodeAny(text).map { ProfileJson.reId(it) }
        persist(_profiles.value + imported)
        return imported
    }

    fun export(profileId: String?): String {
        val all = _profiles.value
        val chosen = if (profileId == null) all else all.filter { it.id == profileId }
        return if (chosen.size == 1) ProfileJson.encode(chosen.first()) else ProfileJson.encodeAll(chosen)
    }

    private companion object {
        const val TAG = "ProfileRepository"
    }
}
