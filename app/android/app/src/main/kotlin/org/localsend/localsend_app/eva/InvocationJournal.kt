package org.localsend.localsend_app.eva

import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable record of EVA sends, keyed by caller UID and invocation ID. A send is
 * recorded before any network I/O, its interim reply while LocalSend waits for
 * the receiver, and its final reply once known. A repeated invocation never
 * sends twice, and a send cut short by process death reads as interrupted.
 */
class InvocationJournal(
    private val store: Store,
    private val wallClock: () -> Long,
    private val maxEntries: Int = 200,
    private val maxAgeMillis: Long = 14L * 24 * 60 * 60 * 1000,
) {
    interface Store {
        fun read(): String?
        fun write(text: String)
    }

    sealed class Prior {
        /** Never seen; the caller may [begin] it. */
        object None : Prior()
        data class Replay(val envelope: String) : Prior()
        /** Started and still running, with no reply recorded yet. */
        object InFlight : Prior()
        /** Started earlier with no recorded reply; the process died before sending finished. */
        object Interrupted : Prior()
        /** The same invocation ID arrived with a different request. */
        object Conflict : Prior()
    }

    data class Record(val envelope: String?, val final: Boolean, val running: Boolean)

    private data class Entry(val key: String, val fingerprint: String, val startedAt: Long, val envelope: String?, val final: Boolean)

    private var entries: MutableMap<String, Entry>? = null
    private val running = mutableSetOf<String>()

    @Synchronized
    fun lookup(callerUid: Int, invocationId: String, fingerprint: String): Prior {
        val key = key(callerUid, invocationId)
        val existing = load()[key] ?: return Prior.None
        return when {
            existing.fingerprint != fingerprint -> Prior.Conflict
            existing.envelope != null && (existing.final || key in running) -> Prior.Replay(existing.envelope)
            key in running -> Prior.InFlight
            else -> Prior.Interrupted
        }
    }

    @Synchronized
    fun record(callerUid: Int, invocationId: String): Record? {
        val key = key(callerUid, invocationId)
        val entry = load()[key] ?: return null
        return Record(entry.envelope, entry.final, key in running)
    }

    /**
     * Durably records the send as started. False if the ID was already
     * claimed. Throws if the record cannot be written; the send must not run.
     */
    @Synchronized
    fun begin(callerUid: Int, invocationId: String, fingerprint: String): Boolean {
        val all = load()
        val key = key(callerUid, invocationId)
        if (key in all) return false
        all[key] = Entry(key, fingerprint, wallClock(), null, false)
        prune(all)
        try {
            save(all)
        } catch (e: Exception) {
            all.remove(key)
            throw e
        }
        running.add(key)
        return true
    }

    /** Records an interim reply unless a final one is already there, and returns whichever is current. */
    @Synchronized
    fun progress(callerUid: Int, invocationId: String, envelope: String): String {
        val all = load()
        val key = key(callerUid, invocationId)
        val entry = all[key] ?: return envelope
        if (entry.final) return entry.envelope ?: envelope
        all[key] = entry.copy(envelope = envelope)
        save(all)
        return envelope
    }

    @Synchronized
    fun finish(callerUid: Int, invocationId: String, envelope: String) {
        val all = load()
        val key = key(callerUid, invocationId)
        running.remove(key)
        val entry = all[key] ?: return
        all[key] = entry.copy(envelope = envelope, final = true)
        save(all)
    }

    private fun key(callerUid: Int, invocationId: String) = "$callerUid:$invocationId"

    private fun load(): MutableMap<String, Entry> {
        entries?.let { return it }
        val parsed = linkedMapOf<String, Entry>()
        try {
            val array = store.read()?.let { JSONArray(it) } ?: JSONArray()
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val entry = Entry(
                    o.getString("key"),
                    o.getString("fingerprint"),
                    o.getLong("startedAt"),
                    if (o.isNull("envelope")) null else o.getString("envelope"),
                    o.optBoolean("final", false),
                )
                parsed[entry.key] = entry
            }
        } catch (e: Exception) {
            // An unreadable journal must not block new sends; it only loses replay of old replies.
        }
        return parsed.also { entries = it }
    }

    private fun prune(all: MutableMap<String, Entry>) {
        val cutoff = wallClock() - maxAgeMillis
        all.values.removeAll { it.startedAt < cutoff && it.key !in running }
        val excess = all.size - maxEntries
        if (excess > 0) {
            all.values.filter { it.key !in running }.sortedBy { it.startedAt }.take(excess).forEach { all.remove(it.key) }
        }
    }

    private fun save(all: Map<String, Entry>) {
        val array = JSONArray()
        all.values.forEach { e ->
            array.put(
                JSONObject()
                    .put("key", e.key)
                    .put("fingerprint", e.fingerprint)
                    .put("startedAt", e.startedAt)
                    .put("envelope", e.envelope ?: JSONObject.NULL)
                    .put("final", e.final),
            )
        }
        store.write(array.toString())
    }
}
