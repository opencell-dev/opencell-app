package org.opencell.core.fakes

import kotlinx.coroutines.delay
import org.opencell.core.link.ConnectException
import org.opencell.core.link.Connection
import org.opencell.core.link.ConnectionEvents
import org.opencell.core.link.Connector
import org.opencell.core.link.LinkTarget
import org.opencell.core.link.PairingException
import org.opencell.core.link.PairingProblem
import org.opencell.core.link.WriteResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A [Connector] whose connects take [connectTime] and can be made to fail,
 * pair first ([pairTime] > 0: reports onPairing, then waits), or fail pairing.
 */
class FakeConnector(private val connectTime: Duration = 100.milliseconds) : Connector {
    var failNext = 0
    var pairTime: Duration = Duration.ZERO
    var pairingProblem: PairingProblem? = null
    var statusBytes: ByteArray? = null
    val connections = mutableListOf<FakeConnection>()
    var attempts = 0
        private set

    override suspend fun connect(target: LinkTarget, events: ConnectionEvents): Connection {
        attempts++
        delay(connectTime)
        if (pairTime > Duration.ZERO) {
            events.onPairing()
            delay(pairTime)
        }
        pairingProblem?.let {
            pairingProblem = null
            throw PairingException(it, "pairing failed or was cancelled")
        }
        if (failNext > 0) {
            failNext--
            throw ConnectException("GATT error 133")
        }
        return FakeConnection(events, statusBytes).also { connections += it }
    }
}

class FakeConnection(val events: ConnectionEvents, private val statusBytes: ByteArray?) : Connection {
    override val mtu = 247
    var closed = false
        private set
    val writes = mutableListOf<ByteArray>()
    val commands = mutableListOf<ByteArray>()

    /** Results for the next COMMAND writes (Accepted when empty). */
    val commandResults = ArrayDeque<WriteResult>()

    override suspend fun write(payload: ByteArray): WriteResult {
        if (closed) return WriteResult.NotConnected
        writes += payload
        return WriteResult.Accepted
    }

    override suspend fun writeCommand(payload: ByteArray): WriteResult {
        if (closed) return WriteResult.NotConnected
        commands += payload
        return commandResults.removeFirstOrNull() ?: WriteResult.Accepted
    }

    override suspend fun readStatus(): ByteArray? = statusBytes

    override fun close() {
        closed = true
    }

    /** The peer went away (supervision timeout, terminal reset...). */
    fun drop(reason: String = "link lost") = events.onClosed(reason)
}
