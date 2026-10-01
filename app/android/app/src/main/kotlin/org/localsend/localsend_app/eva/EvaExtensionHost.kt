package org.localsend.localsend_app.eva

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Admission, deadlines and reply discipline for the binder service, free of
 * Android types. Binder threads only authenticate and enqueue; every request
 * gets exactly one reply.
 */
class EvaExtensionHost(
    private val capabilities: EvaCapabilities,
    private val callerAllowed: (uid: Int) -> Boolean,
    private val now: () -> Long,
    private val executor: Executor = boundedExecutor(),
) {
    fun describe(callingUid: Int, deadline: Long, reply: (String) -> Unit) {
        val once = once(reply)
        if (!callerAllowed(callingUid)) {
            once(EvaProtocol.describeFailure(unauthorized()))
            return
        }
        val effectiveDeadline = minOf(deadline, now() + EvaProtocol.DESCRIBE_MAX_MILLIS)
        submit({
            once(
                if (now() >= effectiveDeadline) {
                    EvaProtocol.describeFailure(
                        EvaProtocol.notExecuted(EvaProtocol.REASON_DEADLINE_EXCEEDED, "The describe deadline passed before LocalSend started."),
                    )
                } else {
                    guarded({ EvaProtocol.describeFailure(EvaProtocol.failed("LocalSend hit an unexpected error.")) }) { capabilities.describe() }
                },
            )
        }) { once(EvaProtocol.describeFailure(busy())) }
    }

    fun execute(
        callingUid: Int,
        invocationId: String,
        expectedRevision: String,
        capability: String,
        argumentsJson: String,
        deadline: Long,
        reply: (String) -> Unit,
    ) {
        val once = once(reply)
        if (!callerAllowed(callingUid)) {
            once(EvaProtocol.executeEnvelope(unauthorized()))
            return
        }
        if (now() >= deadline) {
            once(
                capabilities.rejection(
                    capability, invocationId, EvaProtocol.REASON_DEADLINE_EXCEEDED,
                    "The deadline passed before LocalSend received the request. Nothing was sent.", EvaCapabilities.STATE_NOT_SENT,
                ),
            )
            return
        }
        submit({
            once(
                guarded({
                    // Reached only through a bug; a send may have started, so this is not a not_executed.
                    EvaProtocol.executeEnvelope(EvaProtocol.unknown("LocalSend hit an unexpected error."))
                }) {
                    capabilities.execute(callingUid, invocationId, expectedRevision, capability, argumentsJson, deadline)
                },
            )
        }) {
            once(
                capabilities.rejection(
                    capability, invocationId, EvaProtocol.REASON_BUSY,
                    "LocalSend is busy with other requests. Nothing was sent.", EvaCapabilities.STATE_NOT_STARTED,
                ),
            )
        }
    }

    private fun submit(work: Runnable, rejected: () -> Unit) {
        try {
            executor.execute(work)
        } catch (e: RejectedExecutionException) {
            rejected()
        }
    }

    private inline fun <T> guarded(fallback: () -> T, block: () -> T): T =
        try {
            block()
        } catch (e: Throwable) {
            fallback()
        }

    private fun once(reply: (String) -> Unit): (String) -> Unit {
        val sent = AtomicBoolean(false)
        return { response -> if (sent.compareAndSet(false, true)) reply(response) }
    }

    private fun unauthorized() = EvaProtocol.notExecuted(EvaProtocol.REASON_UNAUTHORIZED_CALLER, "Caller is not authorized.")

    private fun busy() = EvaProtocol.notExecuted(EvaProtocol.REASON_BUSY, "LocalSend is busy with other requests.")

    companion object {
        private const val MAX_QUEUED_REQUESTS = 4

        fun boundedExecutor(): Executor =
            ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(MAX_QUEUED_REQUESTS)).apply {
                allowCoreThreadTimeOut(true)
            }
    }
}
