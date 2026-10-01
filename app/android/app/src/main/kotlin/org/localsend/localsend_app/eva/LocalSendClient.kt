package org.localsend.localsend_app.eva

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.UUID
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLException
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.json.JSONObject

/**
 * Sends a text message with LocalSend protocol v2: one `text/plain` file whose
 * preview carries the text. LocalSend receivers show it with copy and close
 * buttons and answer only once it is closed; hanging up earlier withdraws it,
 * so [sendText] holds the request open for up to [holdMillis].
 */
class LocalSendClient(
    private val connectTimeoutMillis: Int = 8_000,
    private val holdMillis: Int = 10 * 60_000,
) {
    sealed interface Result {
        /** The receiver answered the request; 204 means the message was shown and closed. */
        data class Answered(val code: Int) : Result

        /** The receiver asked for the text as a file and stored it. */
        object SavedAsFile : Result

        /** The receiver accepted the text as a file but the upload failed. */
        data class UploadFailed(val detail: String) : Result

        /** The receiver never got the request. */
        data class NotSent(val reason: NotSentReason, val detail: String) : Result

        /** The connection broke after the request reached the receiver. */
        data class Dropped(val detail: String) : Result

        /** Nobody closed the message within the hold limit, so it was withdrawn. */
        object Expired : Result

        /** Sending failed unexpectedly, before or after the request reached the receiver. */
        data class Crashed(val delivered: Boolean) : Result
    }

    enum class NotSentReason { UNREACHABLE, CERTIFICATE_MISMATCH, TLS_REJECTED }

    fun sendText(sender: LocalSendState, device: FavoriteDevice, text: String, onDelivered: () -> Unit): Result {
        val security = sender.security
        val ssl = if (device.https) {
            checkNotNull(security) { "LocalSend has no identity yet" }
            sslContext(security, device.fingerprint)
        } else {
            null
        }
        val fileId = UUID.randomUUID().toString()
        val bytes = text.toByteArray(Charsets.UTF_8)
        val body = JSONObject()
            .put("info", senderInfo(sender))
            .put(
                "files",
                JSONObject().put(
                    fileId,
                    JSONObject()
                        .put("id", fileId)
                        .put("fileName", "$fileId.txt")
                        .put("size", bytes.size)
                        .put("fileType", "text/plain")
                        .put("preview", text),
                ),
            )
            .toString()
            .toByteArray(Charsets.UTF_8)

        val connection = open(device, ssl, "prepare-upload", emptyMap(), holdMillis)
        try {
            try {
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            } catch (e: IOException) {
                return notSent(e)
            }
            onDelivered()
            val code = try {
                connection.responseCode
            } catch (e: SocketTimeoutException) {
                return Result.Expired
            } catch (e: IOException) {
                return if (e is SSLException && rejectedHandshake(e)) notSent(e) else Result.Dropped(describe(e))
            }
            if (code != 200) return Result.Answered(code)
            val response = try {
                JSONObject(connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            } catch (e: Exception) {
                return Result.UploadFailed("the receiver's answer could not be read")
            }
            // A receiver that accepts none of the files has nothing to receive, like a 204.
            val token = response.optJSONObject("files")?.optString(fileId)?.takeIf { it.isNotEmpty() }
                ?: return Result.Answered(204)
            return upload(device, ssl, response.optString("sessionId"), fileId, token, bytes)
        } finally {
            connection.disconnect()
        }
    }

    private fun upload(
        device: FavoriteDevice,
        ssl: SSLContext?,
        sessionId: String,
        fileId: String,
        token: String,
        bytes: ByteArray,
    ): Result {
        val connection = open(
            device,
            ssl,
            "upload",
            mapOf("sessionId" to sessionId, "fileId" to fileId, "token" to token),
            connectTimeoutMillis * 4,
        )
        return try {
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            if (code == 200) Result.SavedAsFile else Result.UploadFailed("the receiver answered HTTP $code")
        } catch (e: IOException) {
            Result.UploadFailed(describe(e))
        } finally {
            connection.disconnect()
        }
    }

    private fun open(
        device: FavoriteDevice,
        ssl: SSLContext?,
        route: String,
        query: Map<String, String>,
        readTimeoutMillis: Int,
    ): HttpURLConnection {
        val scheme = if (ssl != null) "https" else "http"
        val queryString = query.entries.joinToString("&", prefix = if (query.isEmpty()) "" else "?") { (k, v) ->
            "$k=" + URLEncoder.encode(v, "UTF-8")
        }
        val connection = URL("$scheme://${device.address}/api/localsend/v2/$route$queryString").openConnection() as HttpURLConnection
        if (connection is HttpsURLConnection && ssl != null) {
            connection.sslSocketFactory = ssl.socketFactory
            // The pinned certificate fingerprint replaces hostname checks; LocalSend certificates name no host.
            connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.useCaches = false
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.setRequestProperty("Connection", "close")
        return connection
    }

    private fun senderInfo(sender: LocalSendState): JSONObject = JSONObject()
        .put("alias", sender.alias)
        .put("version", PROTOCOL_VERSION)
        .put("deviceModel", sender.deviceModel ?: JSONObject.NULL)
        .put("deviceType", sender.deviceType)
        .put("fingerprint", sender.security?.certificateHash ?: UUID.randomUUID().toString())
        .put("port", sender.port)
        .put("protocol", if (sender.https) "https" else "http")
        .put("download", false)

    private fun notSent(e: IOException): Result.NotSent {
        val reason = when {
            causes(e).any { it is FingerprintMismatch } -> NotSentReason.CERTIFICATE_MISMATCH
            e is SSLException -> NotSentReason.TLS_REJECTED
            else -> NotSentReason.UNREACHABLE
        }
        return Result.NotSent(reason, describe(e))
    }

    /** With TLS 1.3 a refused client certificate surfaces as an alert on the first read, before any HTTP exchange. */
    private fun rejectedHandshake(e: SSLException): Boolean =
        causes(e).any { cause ->
            val message = cause.message.orEmpty().lowercase()
            cause is FingerprintMismatch || "alert" in message || "certificate" in message || "handshake" in message
        }

    private fun causes(e: Throwable): Sequence<Throwable> = generateSequence(e) { it.cause }.take(8)

    private fun describe(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    class FingerprintMismatch(expected: String, actual: String) :
        CertificateException("certificate fingerprint $actual does not match the favorite's $expected")

    companion object {
        const val PROTOCOL_VERSION = "2.1"

        fun fingerprint(certificate: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02X".format(it) }

        fun sslContext(security: SecurityContext, expectedFingerprint: String): SSLContext {
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(security.certificatePem.toByteArray(Charsets.US_ASCII))) as X509Certificate
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pemBody(security.privateKeyPem)))
            return SSLContext.getInstance("TLS").apply {
                init(arrayOf(IdentityKeyManager(key, certificate)), arrayOf(PinnedTrustManager(expectedFingerprint)), SecureRandom())
            }
        }

        @OptIn(ExperimentalEncodingApi::class)
        private fun pemBody(pem: String): ByteArray {
            val base64 = pem.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("-----") }.joinToString("")
            return Base64.decode(base64)
        }
    }

    /** Presents LocalSend's own certificate, which receivers require and use as the sender's identity. */
    private class IdentityKeyManager(private val key: PrivateKey, private val certificate: X509Certificate) : X509ExtendedKeyManager() {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getCertificateChain(alias: String?) = arrayOf(certificate)
        override fun getPrivateKey(alias: String?) = key
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

        companion object {
            const val ALIAS = "localsend"
        }
    }

    /** Trusts exactly the certificate whose SHA-256 the favorite recorded, as LocalSend itself does. */
    private class PinnedTrustManager(expected: String) : X509ExtendedTrustManager() {
        private val expected = expected.uppercase()

        private fun check(chain: Array<out X509Certificate>?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("no server certificate")
            val actual = fingerprint(leaf)
            if (actual != expected) throw FingerprintMismatch(expected, actual)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("client only")
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
            throw CertificateException("client only")
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
            throw CertificateException("client only")
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
