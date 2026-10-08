package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * String serializer for an enum that has had entries removed.
 *
 * Preferences are stored as JSON by [kotlinx.serialization], and an enum is written as its
 * constant name. Deleting a constant from an enum whose older builds already wrote that name
 * makes `decodeFromString` throw, and `PlayerPreferencesSerializer` turns that into a
 * `CorruptionException` with no corruption handler installed - so the whole player-preference
 * store would stop loading after an update.
 *
 * This serializer keeps the stored format identical (a plain string) but resolves an
 * unrecognised name to [fallback] instead of failing. The removed names are exactly the ones
 * this build deleted, so the effect is a silent migration to the surviving value.
 */
abstract class RemovedEnumEntrySerializer<T : Enum<T>>(
    serialName: String,
    private val entries: Array<T>,
    private val fallback: T,
) : KSerializer<T> {

    override val descriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: T) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): T {
        val name = decoder.decodeString()
        return entries.firstOrNull { it.name == name } ?: fallback
    }
}
