package com.emfitsolutions.gopreach.data.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * JSON used for cached/queued documents and, later, the API. Matches what Gson did: unknown fields ignored (older/newer app
 * versions share documents), nulls omitted, and a null or unknown enum value falls back to the field default.
 */
val DocJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
}

inline fun <reified T> Json.encode(value: T): String = encodeToString(serializer<T>(), value)
inline fun <reified T> Json.decode(text: String): T = decodeFromString(serializer<T>(), text)
