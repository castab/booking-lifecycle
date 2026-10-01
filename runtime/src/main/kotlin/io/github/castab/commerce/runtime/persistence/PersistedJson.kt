package io.github.castab.commerce.runtime.persistence

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID

/**
 * The JSON that aggregate snapshot rows store, deliberately separate from [io.github.castab.commerce.runtime.http.CommerceJson]
 * (the HTTP contract) so neither representation constrains the other.
 *
 * Decoding is strict. Unknown properties, missing properties, `null` where a value is
 * required, unknown discriminators, and malformed values all fail rather than defaulting,
 * because a stored snapshot is a historical fact that must never be silently reinterpreted.
 * Every stored property is written, `null` included, so the shape of a row does not depend on
 * its values.
 */
internal val PersistedJson: Json =
    Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        classDiscriminator = "kind"
    }

private val decimalPattern = Regex("-?[0-9]+(\\.[0-9]+)?")
private val currencyCodePattern = Regex("[A-Z]{3}")

/** A decimal as plain text, which keeps every digit and its scale exactly. */
internal fun BigDecimal.toStoredDecimal(): String = toPlainString()

internal fun String.toDecimal(): BigDecimal {
    require(decimalPattern.matches(this)) { "Not a plain decimal: '$this'" }
    return BigDecimal(this)
}

internal fun Currency.toStoredCurrency(): String = currencyCode

internal fun String.toCurrency(): Currency {
    require(currencyCodePattern.matches(this)) { "Not an ISO 4217 currency code: '$this'" }
    return Currency.getInstance(this)
}

internal fun String.toStoredUuid(): UUID {
    val uuid = UUID.fromString(this)
    // UUID.fromString accepts shortened and mixed-case forms that would not round trip.
    require(uuid.toString() == this) { "Not a canonical UUID: '$this'" }
    return uuid
}

/** Encodes [value] for a `jsonb` column. */
internal fun <T> encodeStored(
    serializer: KSerializer<T>,
    value: T,
): String = PersistedJson.encodeToString(serializer, value)

/**
 * Decodes a stored `jsonb` value and restores domain values from it, or fails with an
 * [IllegalStateException] that names [what]: the text is malformed, its shape is
 * unsupported, or the restored values break a domain invariant. Persistence never repairs
 * stored state.
 */
internal fun <D, T> restoreStored(
    what: String,
    serializer: KSerializer<D>,
    json: String,
    restore: (D) -> T,
): T =
    try {
        restore(PersistedJson.decodeFromString(serializer, json))
    } catch (e: Exception) {
        throw IllegalStateException("Malformed persisted $what: ${e.message}", e)
    }

/**
 * An integer that must be a JSON number. kotlinx.serialization otherwise also accepts the
 * quoted text `"3"` for an `Int`, which would let a differently typed stored value pass.
 */
internal object StrictIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("StrictInt", PrimitiveKind.INT)

    override fun serialize(
        encoder: Encoder,
        value: Int,
    ) = encoder.encodeInt(value)

    override fun deserialize(decoder: Decoder): Int = decodeStrictNumber(decoder, String::toIntOrNull, "an integer")
}

/** A `long` that must be a JSON number; see [StrictIntSerializer]. */
internal object StrictLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("StrictLong", PrimitiveKind.LONG)

    override fun serialize(
        encoder: Encoder,
        value: Long,
    ) = encoder.encodeLong(value)

    override fun deserialize(decoder: Decoder): Long = decodeStrictNumber(decoder, String::toLongOrNull, "a long integer")
}

private fun <T : Any> decodeStrictNumber(
    decoder: Decoder,
    parse: (String) -> T?,
    expected: String,
): T {
    val element = (decoder as JsonDecoder).decodeJsonElement()
    require(element is JsonPrimitive && !element.isString) { "Expected $expected JSON number but found $element" }
    return requireNotNull(parse(element.content)) { "Expected $expected but found ${element.content}" }
}
