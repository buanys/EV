package ev.buan.plugins.agent.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Small, typed accessors over `kotlinx.serialization`'s JSON tree.
 *
 * The provider talks to an OpenAI-compatible wire format, which is *not* owned by this plugin: a
 * missing or differently-typed field must read as "absent" instead of throwing a cast error deep
 * inside a response parser. Callers decide what an absent field means.
 */
internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arrayOrNull(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.doubleOrNull(key: String): Double? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()

internal fun JsonObject.intOrNull(key: String): Int? = doubleOrNull(key)?.toInt()

internal fun JsonObject.booleanOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
