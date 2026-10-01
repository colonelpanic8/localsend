package org.localsend.localsend_app.eva

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import org.localsend.localsend_app.eva.LocalSendClient.Result

/**
 * Runs sends past EVA's reply deadline: a LocalSend receiver answers only once
 * its user closes the message, and hanging up earlier withdraws it.
 */
class SendTransfers(
    private val send: (LocalSendState, FavoriteDevice, String, onDelivered: () -> Unit) -> Result,
    private val keepAlive: KeepAlive,
    private val now: () -> Long,
    private val maxActive: Int = 3,
    private val executor: Executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "eva-localsend-send").apply { isDaemon = true }
    },
) {
    /** Keeps the process alive while any send waits on its receiver. */
    interface KeepAlive {
        fun hold(deviceAlias: String)
        fun release()
    }

    class Transfer internal constructor() {
        private val lock = Object()
        private var deliveredAt: Long? = null
        private var result: Result? = null

        val delivered: Boolean get() = synchronized(lock) { deliveredAt != null }

        internal fun markDelivered(at: Long) = synchronized(lock) {
            deliveredAt = at
            lock.notifyAll()
        }

        internal fun complete(value: Result) = synchronized(lock) {
            result = value
            lock.notifyAll()
        }

        /**
         * Waits for the outcome until [replyBy], or for [settleMillis] after
         * delivery, when an immediate refusal would have arrived. Null means
         * the send is still running.
         */
        fun await(replyBy: Long, settleMillis: Long, now: () -> Long): Result? = synchronized(lock) {
            while (result == null) {
                val limit = deliveredAt?.let { minOf(replyBy, it + settleMillis) } ?: replyBy
                val remaining = limit - now()
                if (remaining <= 0) break
                lock.wait(remaining)
            }
            result
        }
    }

    private var active = 0

    /** Starts a send, or returns null when too many are already waiting. [onFinished] runs before waiters see the result. */
    fun start(state: LocalSendState, device: FavoriteDevice, text: String, onFinished: (Result) -> Unit): Transfer? {
        synchronized(this) {
            if (active >= maxActive) return null
            active++
        }
        val transfer = Transfer()
        // Without a keep-alive the send still runs; the process is just easier to reclaim while it waits.
        runCatching { keepAlive.hold(device.alias) }
        try {
            executor.execute {
                val result = try {
                    send(state, device, text) { transfer.markDelivered(now()) }
                } catch (e: Throwable) {
                    Result.Crashed(transfer.delivered)
                }
                try {
                    onFinished(result)
                } finally {
                    transfer.complete(result)
                    finished()
                }
            }
        } catch (e: Throwable) {
            finished()
            return null
        }
        return transfer
    }

    private fun finished() {
        val idle = synchronized(this) {
            active--
            active == 0
        }
        if (idle) runCatching { keepAlive.release() }
    }
}
