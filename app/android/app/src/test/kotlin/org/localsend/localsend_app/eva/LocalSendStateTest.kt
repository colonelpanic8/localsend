package org.localsend.localsend_app.eva

import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSendStateTest {

    private val listPrefix = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu"
    private val certHash = "A".repeat(64)

    private fun favorite(id: String, ip: String, fingerprint: String = certHash) =
        JSONObject().put("id", id).put("fingerprint", fingerprint).put("ip", ip).put("port", 53317)
            .put("alias", "Desk $id").put("customAlias", false).toString()

    @Test
    fun readsIdentityAndJsonEncodedFavorites() {
        val prefs = mapOf(
            "flutter.ls_alias" to "Pixel",
            "flutter.ls_port" to 53318L,
            "flutter.ls_https" to true,
            "flutter.ls_security_context" to JSONObject().put("privateKey", "k").put("publicKey", "p")
                .put("certificate", "c").put("certificateHash", certHash).toString(),
            "flutter.ls_favorites" to "$listPrefix!" + JSONArray(listOf(favorite("1", "100.90.1.42"), "not json")).toString(),
        )
        val state = LocalSendState.from(prefs, "Model")

        assertEquals("Pixel", state.alias)
        assertEquals(53318, state.port)
        assertEquals("Model", state.deviceModel)
        assertEquals(certHash, state.security?.certificateHash)
        assertEquals(listOf("1"), state.favorites.map { it.id })
        assertEquals("100.90.1.42:53317", state.favorites.single().address)
    }

    @Test
    fun readsFavoritesWrittenWithTheOlderJavaSerializedEncoding() {
        val bytes = ByteArrayOutputStream()
        ObjectOutputStream(bytes).use { it.writeObject(arrayListOf(favorite("old", "192.168.1.5"))) }
        val prefs = mapOf("flutter.ls_favorites" to listPrefix + Base64.getEncoder().encodeToString(bytes.toByteArray()))

        assertEquals(listOf("old"), LocalSendState.from(prefs, null).favorites.map { it.id })
    }

    @Test
    fun missingSettingsFallBackToLocalSendDefaults() {
        val state = LocalSendState.from(emptyMap<String, Any>(), null)

        assertEquals(LocalSendState.DEFAULT_PORT, state.port)
        assertTrue(state.https)
        assertNull(state.security)
        assertTrue(state.favorites.isEmpty())
    }

    @Test
    fun fingerprintDecidesTheProtocolAndIpv6IsBracketed() {
        assertTrue(FavoriteDevice("1", certHash.lowercase(), "10.0.0.2", 53317, "a").https)
        assertFalse(FavoriteDevice("2", "random-uuid", "10.0.0.2", 53317, "b").https)
        assertEquals("[fd7a:115c::1]:53317", FavoriteDevice("3", certHash, "fd7a:115c::1", 53317, "c").address)
    }
}
