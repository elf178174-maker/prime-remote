package dev.primeremote.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Reading and writing layouts as JSON, for storage and for share/export. */
object ProfileJson {

    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "kind"
    }

    fun encode(profile: Profile): String = json.encodeToString(profile)

    fun decode(text: String): Profile = json.decodeFromString<Profile>(text)

    fun encodeAll(profiles: List<Profile>): String = json.encodeToString(profiles)

    fun decodeAll(text: String): List<Profile> = json.decodeFromString<List<Profile>>(text)

    /**
     * Accepts either a single layout or a list of them, which makes importing a shared
     * file forgiving about what exactly was exported.
     */
    fun decodeAny(text: String): List<Profile> {
        val trimmed = text.trim()
        return if (trimmed.startsWith("[")) decodeAll(trimmed) else listOf(decode(trimmed))
    }

    /** Give an imported layout fresh ids so it can live alongside the original. */
    fun reId(profile: Profile): Profile = profile.copy(
        id = Presets.newId("p"),
        pages = profile.pages.map { page ->
            page.copy(
                id = Presets.newId("pg"),
                controls = page.controls.map { it.copy(id = Presets.newId("c")) },
            )
        },
    )
}
