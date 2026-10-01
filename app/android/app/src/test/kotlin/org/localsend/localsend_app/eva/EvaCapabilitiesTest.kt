package org.localsend.localsend_app.eva

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.localsend.localsend_app.eva.LocalSendClient.NotSentReason
import org.localsend.localsend_app.eva.LocalSendClient.Result

class EvaCapabilitiesTest {

    private val desk = FavoriteDevice("fav-desk", "A".repeat(64), "100.90.1.42", 53317, "Desk")
    private val laptop = FavoriteDevice("fav-laptop", "B".repeat(64), "100.68.113.84", 53317, "Laptop")
    private val identity = SecurityContext("key", "cert", "C".repeat(64))

    private class MemoryStore : InvocationJournal.Store {
        var text: String? = null
        override fun read() = text
        override fun write(text: String) {
            this.text = text
        }
    }

    private val store = MemoryStore()
    private val calls = AtomicInteger()
    private val sentTo = mutableListOf<String>()
    private val clock = { System.nanoTime() / 1_000_000 }

    private fun capabilities(
        favorites: List<FavoriteDevice> = listOf(desk),
        journalStore: MemoryStore = store,
        receiver: (onDelivered: () -> Unit) -> Result,
    ): EvaCapabilities {
        val state = LocalSendState("Phone", 53317, true, null, "mobile", identity, favorites)
        val transfers = SendTransfers(
            { _, device, _, onDelivered ->
                calls.incrementAndGet()
                synchronized(sentTo) { sentTo.add(device.id) }
                receiver(onDelivered)
            },
            object : SendTransfers.KeepAlive {
                override fun hold(deviceAlias: String) = Unit
                override fun release() = Unit
            },
            clock,
        )
        return EvaCapabilities(
            { EvaCapabilities.Configuration(true, state) },
            InvocationJournal(journalStore, System::currentTimeMillis),
            transfers,
            clock,
            settleMillis = 100,
        )
    }

    private fun EvaCapabilities.revision(): String = JSONObject(describe()).getJSONObject("descriptor").getString("descriptorRevision")

    private fun EvaCapabilities.run(capability: String, args: JSONObject, id: String = "eva-1", revision: String = revision()): JSONObject =
        JSONObject(execute(7, id, revision, capability, args.toString(), clock() + 20_000))

    private fun JSONObject.state() = getJSONObject("structuredContent").getString("state")

    private fun send(text: String = "https://example.com", device: String? = null) =
        JSONObject().put("text", text).apply { device?.let { put("device", it) } }

    @Test
    fun closedMessageCompletesAndARepeatReplaysWithoutSendingAgain() {
        val caps = capabilities { delivered ->
            delivered()
            Result.Answered(204)
        }

        val first = caps.run("send_text", send())
        val repeat = caps.run("send_text", send())

        assertEquals("completed", first.getString("status"))
        assertEquals("completed", first.state())
        assertEquals(first.toString(), repeat.toString())
        assertEquals(1, calls.get())
    }

    @Test
    fun messageStillShowingIsHandedOffAndItsOutcomeIsReadableLater() {
        val closed = CountDownLatch(1)
        val caps = capabilities { delivered ->
            delivered()
            closed.await(5, TimeUnit.SECONDS)
            Result.Answered(204)
        }

        val reply = caps.run("send_text", send())
        assertEquals("handed_off", reply.getString("status"))
        assertEquals("submitted", reply.state())
        assertEquals("submitted", caps.run("request_status", JSONObject().put("invocationId", "eva-1"), id = "eva-2").state())

        closed.countDown()
        val deadline = clock() + 5_000
        var status = ""
        while (status != "completed" && clock() < deadline) {
            status = caps.run("request_status", JSONObject().put("invocationId", "eva-1"), id = "eva-3").state()
        }
        assertEquals("completed", status)
    }

    @Test
    fun refusalRightAfterDeliveryIsReportedInsteadOfAHandoff() {
        val caps = capabilities { delivered ->
            delivered()
            Thread.sleep(20)
            Result.Answered(401)
        }

        val reply = caps.run("send_text", send())

        assertEquals("not_executed", reply.getString("status"))
        assertEquals("not_configured", reply.getString("reasonCode"))
        assertEquals("needs_configuration", reply.state())
    }

    @Test
    fun unreachableReceiverIsNotExecuted() {
        val caps = capabilities { Result.NotSent(NotSentReason.UNREACHABLE, "Connection refused") }

        val reply = caps.run("send_text", send())

        assertEquals("not_executed", reply.getString("status"))
        assertEquals("not_sent", reply.state())
    }

    @Test
    fun severalFavoritesRequireADeviceAndMatchAliasesCaseInsensitively() {
        val caps = capabilities(favorites = listOf(desk, laptop)) { delivered ->
            delivered()
            Result.Answered(204)
        }

        val ambiguous = caps.run("send_text", send(), id = "eva-1")
        val named = caps.run("send_text", send(device = "laptop"), id = "eva-2")

        assertEquals("invalid_arguments", ambiguous.getString("reasonCode"))
        assertEquals("completed", named.getString("status"))
        assertEquals(listOf("fav-laptop"), sentTo)
    }

    @Test
    fun staleRevisionSendsNothing() {
        val caps = capabilities { Result.Answered(204) }

        val reply = caps.run("send_text", send(), revision = "localsend-old")

        assertEquals("stale_descriptor", reply.getString("reasonCode"))
        assertEquals(0, calls.get())
    }

    @Test
    fun sendCutShortByProcessDeathIsInterruptedAndNeverResent() {
        val never = CountDownLatch(1)
        capabilities { delivered ->
            delivered()
            never.await(5, TimeUnit.SECONDS)
            Result.Answered(204)
        }.run("send_text", send())

        // A new process reads the journal the old one left mid-send.
        val restarted = capabilities(journalStore = MemoryStore().apply { text = store.text }) { Result.Answered(204) }
        val status = restarted.run("request_status", JSONObject().put("invocationId", "eva-1"), id = "eva-2")
        val repeat = restarted.run("send_text", send())
        never.countDown()

        assertEquals("interrupted", status.state())
        assertEquals("unknown", repeat.getString("status"))
        assertEquals("uncertain", repeat.state())
        assertEquals(1, calls.get())
    }

    @Test
    fun descriptorListsTheThreeCapabilitiesWithWriteEffectsOnlyForSending() {
        val caps = capabilities { Result.Answered(204) }

        val listed = JSONObject(caps.describe()).getJSONObject("descriptor").getJSONArray("capabilities")
        val effects = (0 until listed.length()).associate {
            listed.getJSONObject(it).getJSONObject("tool").getString("name") to listed.getJSONObject(it).getString("effects")
        }

        assertEquals(mapOf("list_devices" to "read", "send_text" to "write", "request_status" to "read"), effects)
        assertTrue(caps.revision().matches(Regex("[A-Za-z0-9._:-]{1,128}")))
    }
}
