package org.localsend.localsend_app.eva

import java.io.ByteArrayInputStream
import java.io.ObjectInputStream
import java.io.ObjectStreamClass
import org.json.JSONArray
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.json.JSONObject

/** A device the user marked as a favorite in LocalSend; the only destinations EVA may send to. */
data class FavoriteDevice(
    val id: String,
    val fingerprint: String,
    val ip: String,
    val port: Int,
    val alias: String,
) {
    /** LocalSend uses the certificate hash as the fingerprint in HTTPS mode and a random string otherwise. */
    val https: Boolean get() = CERT_FINGERPRINT.matches(fingerprint)

    val address: String get() = if (ip.contains(':') && !ip.startsWith('[')) "[$ip]:$port" else "$ip:$port"

    companion object {
        private val CERT_FINGERPRINT = Regex("^[0-9A-Fa-f]{64}$")
    }
}

/** LocalSend's own identity as a sender, generated on the app's first start. */
data class SecurityContext(val privateKeyPem: String, val certificatePem: String, val certificateHash: String)

/**
 * The parts of LocalSend's Flutter preferences that sending needs. Reads the
 * legacy `FlutterSharedPreferences` store the Dart side writes, so the
 * extension works without starting Flutter.
 */
data class LocalSendState(
    val alias: String,
    val port: Int,
    val https: Boolean,
    val deviceModel: String?,
    val deviceType: String,
    val security: SecurityContext?,
    val favorites: List<FavoriteDevice>,
) {
    companion object {
        const val PREFERENCES_NAME = "FlutterSharedPreferences"
        private const val PREFIX = "flutter."
        private const val LIST_PREFIX = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu"
        private const val JSON_LIST_PREFIX = "$LIST_PREFIX!"
        const val DEFAULT_PORT = 53317

        fun from(prefs: Map<String, *>, fallbackModel: String?): LocalSendState {
            fun value(key: String) = prefs[PREFIX + key]
            return LocalSendState(
                alias = (value("ls_alias") as? String)?.takeIf { it.isNotBlank() } ?: "EVA",
                port = (value("ls_port") as? Number)?.toInt()?.takeIf { it in 1..65_535 } ?: DEFAULT_PORT,
                https = value("ls_https") as? Boolean ?: true,
                deviceModel = (value("ls_device_model") as? String) ?: fallbackModel,
                deviceType = (value("ls_device_type") as? String) ?: "mobile",
                security = (value("ls_security_context") as? String)?.let(::parseSecurityContext),
                favorites = stringList(value("ls_favorites")).mapNotNull(::parseFavorite),
            )
        }

        private fun parseSecurityContext(json: String): SecurityContext? =
            try {
                val o = JSONObject(json)
                SecurityContext(o.getString("privateKey"), o.getString("certificate"), o.getString("certificateHash"))
            } catch (e: Exception) {
                null
            }

        private fun parseFavorite(json: String): FavoriteDevice? =
            try {
                val o = JSONObject(json)
                FavoriteDevice(
                    id = o.getString("id"),
                    fingerprint = o.getString("fingerprint"),
                    ip = o.getString("ip"),
                    port = o.getInt("port"),
                    alias = o.getString("alias"),
                ).takeIf { it.id.isNotEmpty() && it.ip.isNotEmpty() && it.port in 1..65_535 }
            } catch (e: Exception) {
                null
            }

        /** Decodes both list encodings the Flutter plugin has written: JSON, and older Java serialization. */
        internal fun stringList(raw: Any?): List<String> {
            if (raw is Set<*>) return raw.filterIsInstance<String>()
            if (raw !is String) return emptyList()
            return try {
                when {
                    raw.startsWith(JSON_LIST_PREFIX) -> {
                        val array = JSONArray(raw.substring(JSON_LIST_PREFIX.length))
                        (0 until array.length()).mapNotNull { array.opt(it) as? String }
                    }
                    raw.startsWith(LIST_PREFIX) -> decodeSerializedList(raw.substring(LIST_PREFIX.length))
                    else -> emptyList()
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        @OptIn(ExperimentalEncodingApi::class)
        private fun decodeSerializedList(base64: String): List<String> {
            val stream = object : ObjectInputStream(ByteArrayInputStream(Base64.decode(base64))) {
                override fun resolveClass(desc: ObjectStreamClass): Class<*> {
                    require(desc.name == "java.util.ArrayList" || desc.name == "java.lang.String") { "unexpected ${desc.name}" }
                    return super.resolveClass(desc)
                }
            }
            return stream.use { (it.readObject() as List<*>).filterIsInstance<String>() }
        }
    }
}
