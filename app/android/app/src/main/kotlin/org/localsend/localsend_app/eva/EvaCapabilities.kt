package org.localsend.localsend_app.eva

import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.json.JSONArray
import org.json.JSONObject
import org.localsend.localsend_app.eva.EvaProtocol.Reply
import org.localsend.localsend_app.eva.LocalSendClient.NotSentReason
import org.localsend.localsend_app.eva.LocalSendClient.Result

/**
 * LocalSend's catalog for EVA's installed-app protocol. Sending needs only
 * LocalSend's stored identity and favorites, not Flutter or an activity, so it
 * works from a cold process while the phone is locked after its first unlock.
 * Favorites are the only destinations: the model cannot name an address.
 */
class EvaCapabilities(
    private val state: () -> Configuration,
    private val journal: InvocationJournal,
    private val transfers: SendTransfers,
    private val now: () -> Long,
    private val settleMillis: Long = SETTLE_MILLIS,
) {
    data class Configuration(
        /** False before the first unlock after boot, when LocalSend's settings are unreadable. */
        val userUnlocked: Boolean,
        val localSend: LocalSendState?,
    )

    private class Property(val name: String, val description: String, val required: Boolean, val maxLength: Int) {
        fun toJson(): JSONObject = JSONObject().put("type", "string").put("description", description)
            .put("minLength", 1).put("maxLength", maxLength)
    }

    private class Capability(
        val name: String,
        val title: String,
        val description: String,
        val write: Boolean,
        val properties: List<Property>,
    ) {
        val maxWaitMillis: Long get() = if (write) WRITE_WAIT_MILLIS else READ_WAIT_MILLIS

        fun check(args: JSONObject): String? {
            val known = properties.associateBy { it.name }
            args.keys().forEach { if (it !in known) return "Unknown argument '$it'." }
            for (property in properties) {
                val value = args.opt(property.name)
                if (value == null) {
                    if (property.required) return "Missing required argument '${property.name}'."
                    continue
                }
                if (value !is String) return "Argument '${property.name}' must be a string."
                val length = value.codePointCount(0, value.length)
                if (length < 1 || length > property.maxLength) {
                    return "Argument '${property.name}' must be 1 to ${property.maxLength} characters."
                }
            }
            return null
        }

        fun toJson(): JSONObject {
            val schema = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject().apply { properties.forEach { put(it.name, it.toJson()) } })
                .put("required", JSONArray(properties.filter { it.required }.map { it.name }))
                .put("additionalProperties", false)
            val annotations = JSONObject().put("readOnlyHint", !write).put("openWorldHint", true)
            if (write) annotations.put("destructiveHint", false)
            return JSONObject()
                .put(
                    "tool",
                    JSONObject().put("name", name).put("title", title).put("description", description)
                        .put("inputSchema", schema).put("annotations", annotations),
                )
                .put("effects", if (write) "write" else "read")
                .put("execution", JSONObject().put("mode", "synchronous").put("requiresForeground", false).put("maxWaitMillis", maxWaitMillis))
                .put("result", JSONObject().put("maxBytes", EvaProtocol.MAX_EXECUTE_BYTES))
        }
    }

    companion object {
        const val READ_WAIT_MILLIS = 5_000L
        const val WRITE_WAIT_MILLIS = 20_000L

        /** Kept back from the deadline so the reply reaches EVA in time. */
        const val REPLY_MARGIN_MILLIS = 1_500L
        private const val MIN_WORK_MILLIS = 2_000L

        /** How long a delivered message is watched for an immediate refusal before EVA is told it is showing. */
        const val SETTLE_MILLIS = 1_500L
        const val MAX_TEXT_LENGTH = 6_000

        const val STATE_COMPLETED = "completed"
        const val STATE_ACCEPTED = "accepted"
        const val STATE_SUBMITTED = "submitted"
        const val STATE_UNCERTAIN = "uncertain"
        const val STATE_FAILED = "failed"
        const val STATE_NOT_SENT = "not_sent"
        const val STATE_NOT_STARTED = "not_started"
        const val STATE_REJECTED = "rejected"
        const val STATE_INVALID = "invalid_request"
        const val STATE_CONFLICT = "request_id_conflict"
        const val STATE_STALE = "stale_descriptor"
        const val STATE_INTERRUPTED = "interrupted"
        const val STATE_UNKNOWN_REQUEST = "unknown_request"
        const val STATE_NEEDS_UNLOCK = "needs_unlock"
        const val STATE_NEEDS_CONFIGURATION = "needs_configuration"

        private const val SEND = "send_text"
        private const val HOLD_MINUTES = 10

        private const val NEEDS_UNLOCK_TEXT =
            "LocalSend cannot read its settings until the phone has been unlocked once since it restarted. Nothing was sent."
        private const val UNREADABLE_TEXT = "LocalSend could not read its settings. Open LocalSend once. Nothing was sent."
        private const val NO_IDENTITY_TEXT =
            "LocalSend has not created its identity yet. Open LocalSend once. Nothing was sent."
        private const val NO_FAVORITES_TEXT =
            "LocalSend has no favorite devices. In LocalSend, add the computer as a favorite (on the Send tab, " +
                "favorites, add by address; over Tailscale use its 100.x address)."
        private const val UNCERTAIN_TEXT =
            "LocalSend may already have sent this text. Do not send it again; ask whether it arrived or check with request_status."
    }

    private val capabilities = listOf(
        Capability(
            "list_devices", "List LocalSend devices",
            "List the devices favorited in LocalSend, the only ones send_text can reach: id, alias, and address. " +
                "Favorites are managed in LocalSend itself. This does not check whether a device is online.",
            write = false, properties = emptyList(),
        ),
        Capability(
            SEND, "Send text with LocalSend",
            "Send text, such as a link, address, snippet, or note, to a device favorited in LocalSend, typically the " +
                "user's computer. LocalSend there shows it in a window with Copy (and Open for a link); nothing is " +
                "pasted or saved automatically, and that device's LocalSend settings decide the rest. Omit device when " +
                "LocalSend has exactly one favorite; otherwise pass an id or alias from list_devices. state completed " +
                "means the window was closed (or the text was saved as a file); submitted means it is showing now and " +
                "is withdrawn if not closed within $HOLD_MINUTES minutes; accepted means LocalSend is still connecting. " +
                "Check those later with request_status. Never resend after uncertain.",
            write = true,
            properties = listOf(
                Property("text", "The exact text to send.", required = true, maxLength = MAX_TEXT_LENGTH),
                Property("device", "Favorite id or alias from list_devices. Optional with a single favorite.", required = false, maxLength = 200),
            ),
        ),
        Capability(
            "request_status", "Check an earlier LocalSend send",
            "Report what happened to an earlier send_text by its invocationId, including one whose reply never " +
                "arrived. state is completed, submitted (still showing), accepted, failed, rejected, not_sent, " +
                "uncertain, interrupted (LocalSend stopped while it was showing, so it was likely withdrawn), or " +
                "unknown_request. Records are kept for 14 days.",
            write = false,
            properties = listOf(Property("invocationId", "Invocation id of the earlier send.", required = true, maxLength = 256)),
        ),
    )

    private val byName = capabilities.associateBy { it.name }

    private val capabilitiesJson: JSONArray by lazy { JSONArray(capabilities.map { it.toJson() }) }

    private val catalogDigest: String by lazy { EvaProtocol.sha256Hex(EvaProtocol.canonical(capabilitiesJson)).take(16) }

    /** Sends go out under LocalSend's own identity, so a new identity is a new authority. */
    private fun authorizationScope(config: Configuration): String =
        config.localSend?.security?.let { "identity-" + EvaProtocol.sha256Hex(it.certificateHash).take(16) } ?: "identity-none"

    private fun descriptorRevision(config: Configuration) = "localsend-$catalogDigest.${authorizationScope(config)}"

    private fun readConfiguration(): Configuration? =
        try {
            state()
        } catch (e: Exception) {
            null
        }

    fun describe(): String {
        val config = readConfiguration()
            ?: return EvaProtocol.describeFailure(EvaProtocol.notExecuted(EvaProtocol.REASON_NOT_CONFIGURED, UNREADABLE_TEXT))
        if (!config.userUnlocked) {
            return EvaProtocol.describeFailure(EvaProtocol.notExecuted(EvaProtocol.REASON_NOT_CONFIGURED, NEEDS_UNLOCK_TEXT))
        }
        return EvaProtocol.describeEnvelope(
            JSONObject()
                .put("protocolVersion", EvaProtocol.PROTOCOL_VERSION)
                .put("descriptorRevision", descriptorRevision(config))
                .put("authorizationScopeRevision", authorizationScope(config))
                .put("title", "LocalSend")
                .put("capabilities", capabilitiesJson),
        )
    }

    /** A reply for a request refused before it reached [execute]. */
    fun rejection(capability: String, invocationId: String, reasonCode: String, text: String, state: String): String =
        EvaProtocol.executeEnvelope(
            EvaProtocol.notExecuted(reasonCode, text, if (capability == SEND) sendState(state, invocationId, null) else null),
        )

    fun execute(callerUid: Int, invocationId: String, expectedRevision: String, capabilityName: String, argumentsJson: String, deadline: Long): String {
        val capability = byName[capabilityName]
            ?: return EvaProtocol.executeEnvelope(
                EvaProtocol.notExecuted(EvaProtocol.REASON_INVALID_ARGUMENTS, "LocalSend has no capability '$capabilityName'."),
            )
        val write = capability.write
        fun state(state: String, device: FavoriteDevice? = null) = if (write) sendState(state, invocationId, device) else null
        fun reply(reply: Reply) = EvaProtocol.executeEnvelope(reply)

        val args = parseArguments(argumentsJson)
        val problem = if (args == null) "Arguments are not a JSON object." else capability.check(args)
        if (args == null || problem != null) {
            return reply(EvaProtocol.notExecuted(EvaProtocol.REASON_INVALID_ARGUMENTS, problem!!, state(STATE_INVALID)))
        }

        val config = readConfiguration()
            ?: return reply(EvaProtocol.notExecuted(EvaProtocol.REASON_NOT_CONFIGURED, UNREADABLE_TEXT, state(STATE_NEEDS_CONFIGURATION)))
        if (!config.userUnlocked) {
            return reply(EvaProtocol.notExecuted(EvaProtocol.REASON_NOT_CONFIGURED, NEEDS_UNLOCK_TEXT, state(STATE_NEEDS_UNLOCK)))
        }

        val fingerprint = EvaProtocol.sha256Hex(capability.name + "\n" + EvaProtocol.canonical(args))
        if (write) {
            when (val prior = journal.lookup(callerUid, invocationId, fingerprint)) {
                is InvocationJournal.Prior.Replay -> return prior.envelope
                InvocationJournal.Prior.Conflict -> return reply(
                    EvaProtocol.notExecuted(
                        EvaProtocol.REASON_INVALID_ARGUMENTS,
                        "This invocation ID was already used for a different request. Nothing was sent.",
                        state(STATE_CONFLICT),
                    ),
                )
                InvocationJournal.Prior.InFlight, InvocationJournal.Prior.Interrupted ->
                    return reply(EvaProtocol.unknown(UNCERTAIN_TEXT, state(STATE_UNCERTAIN)))
                InvocationJournal.Prior.None -> Unit
            }
        }
        if (expectedRevision != descriptorRevision(config)) {
            return reply(
                EvaProtocol.notExecuted(
                    EvaProtocol.REASON_STALE_DESCRIPTOR,
                    "LocalSend's capabilities or identity changed since EVA approved them. Nothing was sent.",
                    state(STATE_STALE),
                ),
            )
        }
        val localSend = config.localSend
            ?: return reply(EvaProtocol.notExecuted(EvaProtocol.REASON_NOT_CONFIGURED, UNREADABLE_TEXT, state(STATE_NEEDS_CONFIGURATION)))

        return when (capability.name) {
            "list_devices" -> reply(listDevices(localSend))
            "request_status" -> reply(requestStatus(callerUid, args.getString("invocationId")))
            else -> send(callerUid, invocationId, fingerprint, localSend, args, deadline)
        }
    }

    private fun listDevices(localSend: LocalSendState): Reply {
        if (localSend.favorites.isEmpty()) return EvaProtocol.completed(NO_FAVORITES_TEXT, JSONObject().put("devices", JSONArray()))
        val devices = JSONArray()
        val lines = localSend.favorites.take(50).map { device ->
            devices.put(JSONObject().put("id", device.id).put("alias", device.alias).put("address", device.address))
            "${device.alias} (id ${device.id}) at ${device.address}"
        }
        return EvaProtocol.completed(lines.joinToString("\n"), JSONObject().put("devices", devices))
    }

    private fun requestStatus(callerUid: Int, invocationId: String): Reply {
        val record = journal.record(callerUid, invocationId)
        val envelope = record?.envelope?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (record == null || (envelope == null && record.running)) {
            val state = if (record == null) STATE_UNKNOWN_REQUEST else STATE_ACCEPTED
            val text = if (record == null) "LocalSend has no record of that send." else "LocalSend is still connecting for that send."
            return EvaProtocol.completed(text, JSONObject().put("invocationId", invocationId).put("state", state))
        }
        if (!record.final && !record.running) {
            return EvaProtocol.completed(
                "LocalSend stopped while that send was in progress, so the text was most likely withdrawn on the receiving device.",
                JSONObject().put("invocationId", invocationId).put("state", STATE_INTERRUPTED),
            )
        }
        val text = envelope?.optJSONArray("content")?.optJSONObject(0)?.optString("text").orEmpty()
        val structured = envelope?.optJSONObject("structuredContent") ?: JSONObject().put("invocationId", invocationId).put("state", STATE_UNCERTAIN)
        return EvaProtocol.completed(text, structured)
    }

    private fun send(callerUid: Int, invocationId: String, fingerprint: String, localSend: LocalSendState, args: JSONObject, deadline: Long): String {
        fun reply(reply: Reply) = EvaProtocol.executeEnvelope(reply)
        val favorites = localSend.favorites
        if (favorites.isEmpty()) {
            return reply(EvaProtocol.notExecuted(EvaProtocol.REASON_NOT_CONFIGURED, "$NO_FAVORITES_TEXT Nothing was sent.", sendState(STATE_NEEDS_CONFIGURATION, invocationId, null)))
        }
        val requested = args.optString("device").takeIf { args.has("device") }
        val device = when {
            requested == null && favorites.size == 1 -> favorites.single()
            requested == null -> null
            else -> favorites.firstOrNull { it.id == requested }
                ?: favorites.filter { it.alias.equals(requested.trim(), ignoreCase = true) }.singleOrNull()
        }
        if (device == null) {
            val known = favorites.joinToString(", ") { "${it.alias} (id ${it.id})" }
            val why = if (requested == null) "Several devices are favorited; pass device." else "No single favorite matches '$requested'."
            return reply(
                EvaProtocol.notExecuted(EvaProtocol.REASON_INVALID_ARGUMENTS, "$why Favorites: $known. Nothing was sent.", sendState(STATE_INVALID, invocationId, null)),
            )
        }
        if (device.https && localSend.security == null) {
            return reply(EvaProtocol.notExecuted(EvaProtocol.REASON_NOT_CONFIGURED, NO_IDENTITY_TEXT, sendState(STATE_NEEDS_CONFIGURATION, invocationId, device)))
        }
        val replyBy = minOf(deadline, now() + WRITE_WAIT_MILLIS) - REPLY_MARGIN_MILLIS
        if (replyBy - now() < MIN_WORK_MILLIS) {
            return reply(
                EvaProtocol.notExecuted(EvaProtocol.REASON_DEADLINE_EXCEEDED, "Too little time was left to start. Nothing was sent.", sendState(STATE_NOT_SENT, invocationId, device)),
            )
        }

        val started = try {
            journal.begin(callerUid, invocationId, fingerprint)
        } catch (e: Exception) {
            return reply(EvaProtocol.notExecuted(null, "LocalSend could not record the request, so it did not send it.", sendState(STATE_NOT_SENT, invocationId, device)))
        }
        if (!started) return reply(EvaProtocol.unknown(UNCERTAIN_TEXT, sendState(STATE_UNCERTAIN, invocationId, device)))

        val finalEnvelope = { result: Result -> EvaProtocol.executeEnvelope(outcome(result, invocationId, device)) }
        val transfer = transfers.start(localSend, device, args.getString("text")) { result ->
            runCatching { journal.finish(callerUid, invocationId, finalEnvelope(result)) }
        }
        if (transfer == null) {
            val envelope = reply(
                EvaProtocol.notExecuted(EvaProtocol.REASON_BUSY, "LocalSend is busy with other sends. Nothing was sent.", sendState(STATE_NOT_STARTED, invocationId, device)),
            )
            runCatching { journal.finish(callerUid, invocationId, envelope) }
            return envelope
        }

        val result = transfer.await(replyBy, settleMillis, now)
        if (result != null) return finalEnvelope(result)
        val interim = if (transfer.delivered) {
            EvaProtocol.handedOff(
                "${device.alias} is showing the text in LocalSend now. It stays until it is closed there, " +
                    "or is withdrawn after $HOLD_MINUTES minutes.",
                sendState(STATE_SUBMITTED, invocationId, device).put("pollable", true),
            )
        } else {
            EvaProtocol.handedOff(
                "LocalSend is still connecting to ${device.alias} and will keep trying to deliver the text.",
                sendState(STATE_ACCEPTED, invocationId, device).put("pollable", true),
            )
        }
        return runCatching { journal.progress(callerUid, invocationId, reply(interim)) }.getOrElse { reply(interim) }
    }

    private fun outcome(result: Result, invocationId: String, device: FavoriteDevice): Reply {
        fun state(state: String) = sendState(state, invocationId, device)
        val alias = device.alias
        return when (result) {
            is Result.Answered -> when (result.code) {
                204 -> EvaProtocol.completed("$alias showed the text in LocalSend and it was closed there.", state(STATE_COMPLETED))
                401 -> EvaProtocol.notExecuted(
                    EvaProtocol.REASON_NOT_CONFIGURED,
                    "LocalSend on $alias requires a PIN, which EVA cannot enter. Turn off its receive PIN to send from EVA. Nothing was shown.",
                    state(STATE_NEEDS_CONFIGURATION),
                )
                403 -> EvaProtocol.notExecuted(null, "$alias declined the text.", state(STATE_REJECTED))
                409 -> EvaProtocol.notExecuted(null, "LocalSend on $alias is busy with another transfer. Nothing was shown.", state(STATE_REJECTED))
                429 -> EvaProtocol.notExecuted(null, "LocalSend on $alias refused: too many requests. Nothing was shown.", state(STATE_REJECTED))
                else -> EvaProtocol.failed("LocalSend on $alias answered HTTP ${result.code}; the text was probably not shown.", state(STATE_FAILED))
            }
            Result.SavedAsFile -> EvaProtocol.completed("$alias received the text and saved it as a file.", state(STATE_COMPLETED))
            is Result.UploadFailed -> EvaProtocol.failed(
                "$alias accepted the text as a file, but saving it failed (${result.detail}).",
                state(STATE_FAILED),
            )
            is Result.NotSent -> when (result.reason) {
                NotSentReason.UNREACHABLE -> EvaProtocol.notExecuted(
                    null,
                    "Could not reach LocalSend on $alias at ${device.address} (${result.detail}). Make sure LocalSend is " +
                        "running there and the favorite's address is current. Nothing was sent.",
                    state(STATE_NOT_SENT),
                )
                NotSentReason.CERTIFICATE_MISMATCH -> EvaProtocol.notExecuted(
                    EvaProtocol.REASON_NOT_CONFIGURED,
                    "The device at ${device.address} is not the $alias favorited in LocalSend: its certificate changed. " +
                        "Remove and re-add the favorite in LocalSend if it is the same device. Nothing was sent.",
                    state(STATE_NEEDS_CONFIGURATION),
                )
                NotSentReason.TLS_REJECTED -> EvaProtocol.notExecuted(
                    null,
                    "LocalSend on $alias refused the secure connection (${result.detail}). Nothing was sent.",
                    state(STATE_NOT_SENT),
                )
            }
            is Result.Dropped -> EvaProtocol.failed(
                "The connection to $alias broke while the text was showing (${result.detail}). It may have been seen " +
                    "briefly; LocalSend there now shows it as cancelled.",
                state(STATE_FAILED),
            )
            Result.Expired -> EvaProtocol.failed(
                "Nobody closed the text on $alias within $HOLD_MINUTES minutes, so it was withdrawn. LocalSend there " +
                    "now shows it as cancelled.",
                state(STATE_FAILED),
            )
            is Result.Crashed -> if (result.delivered) {
                EvaProtocol.unknown(UNCERTAIN_TEXT, state(STATE_UNCERTAIN))
            } else {
                EvaProtocol.notExecuted(null, "LocalSend could not prepare the send. Nothing was sent.", state(STATE_NOT_SENT))
            }
        }
    }

    private fun sendState(state: String, invocationId: String, device: FavoriteDevice?): JSONObject =
        JSONObject().put("invocationId", invocationId).put("state", state).put("operation", SEND).apply {
            device?.let { put("device", JSONObject().put("id", it.id).put("alias", it.alias)) }
        }

    private fun parseArguments(json: String): JSONObject? {
        if (EvaProtocol.utf8Length(json) > EvaProtocol.MAX_ARGUMENTS_BYTES) return null
        return try {
            JSONObject(json)
        } catch (e: Exception) {
            null
        }
    }
}
