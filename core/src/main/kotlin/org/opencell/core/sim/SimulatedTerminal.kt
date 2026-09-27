package org.opencell.core.sim

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.opencell.core.link.Connection
import org.opencell.core.link.ConnectionEvents
import org.opencell.core.link.Connector
import org.opencell.core.link.LinkTarget
import org.opencell.core.link.WriteResult
import org.opencell.core.protocol.GattContract
import org.opencell.core.protocol.TerminalState
import org.opencell.core.protocol.TerminalStatus
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A software terminal plus echoing test cell, for trying the app without
 * hardware ("Demo terminal") and for tests. It behaves like the firmware as
 * far as the phone can tell:
 * - walks SEARCH -> SYNCED -> ATTACHING -> GRANTED over ~2 s of frames;
 * - takes UP writes into a 4-deep queue (0x80 when full or not granted,
 *   0x0D when over 20 bytes) and sends one per 120 ms frame;
 * - echoes each sent payload back on DOWN after [echoDelay];
 * - notifies STATUS on state changes and every second with a jittered RSSI.
 */
class SimulatedTerminal(
    private val scope: CoroutineScope,
    val tmid: Long = 0x76AD0488L,
    private val echoDelay: Duration = 300.milliseconds,
    private val connectDelay: Duration = 400.milliseconds,
    private val bleDelay: Duration = 15.milliseconds,
    private val seed: Int = 1,
) : Connector {
    val name: String get() = GattContract.NAME_PREFIX + "%08X".format(tmid)

    override suspend fun connect(target: LinkTarget, events: ConnectionEvents): Connection {
        delay(connectDelay)
        return Sim(events).also { it.start() }
    }

    private inner class Sim(private val events: ConnectionEvents) : Connection {
        private val lock = Any()
        private val random = Random(seed)
        private val queue = ArrayDeque<ByteArray>()
        private var frame = 1000L
        private var state = TerminalState.SEARCH
        private var rssi = -52
        private var closed = false
        private var job: Job? = null

        override val mtu: Int = 247

        fun start() {
            job = scope.launch {
                var n = 0
                while (isActive) {
                    delay(GattContract.FRAME_MILLIS)
                    n++
                    val (status, up) = synchronized(lock) {
                        frame++
                        val next = when {
                            n < 5 -> TerminalState.SEARCH
                            n < 9 -> TerminalState.SYNCED
                            n < 13 -> TerminalState.ATTACHING
                            else -> TerminalState.GRANTED
                        }
                        val changed = next != state
                        state = next
                        if (n % 8 == 0) rssi = -52 + random.nextInt(-3, 4)
                        val st = if (changed || n % 8 == 0) status() else null
                        val sent = if (state == TerminalState.GRANTED) queue.removeFirstOrNull() else null
                        st to sent
                    }
                    status?.let { if (!isClosed()) events.onStatus(it.encode()) }
                    up?.let { echo(it) }
                }
            }
        }

        private fun echo(payload: ByteArray) {
            scope.launch {
                delay(echoDelay)
                if (!isClosed()) events.onDownlink(payload)
            }
        }

        private fun isClosed() = synchronized(lock) { closed }

        private fun status() = TerminalStatus(
            stateCode = state.code,
            bandCode = 0,
            tierCode = 2,
            rssiDbm = rssi,
            snrQuarterDb = 50,
            tmid = tmid,
            frame = frame,
            cellSeed = 0x5EED1234L,
        )

        override suspend fun write(payload: ByteArray): WriteResult {
            delay(bleDelay)
            synchronized(lock) {
                return when {
                    closed -> WriteResult.NotConnected
                    payload.size > GattContract.MAX_PAYLOAD -> WriteResult.TooLong
                    state != TerminalState.GRANTED -> WriteResult.NotNow
                    queue.size >= 4 -> WriteResult.NotNow
                    else -> {
                        queue.addLast(payload.copyOf())
                        WriteResult.Accepted
                    }
                }
            }
        }

        /** The v1 simulator has no signalling yet (Task 7 adds it): every command is refused as "not now". */
        override suspend fun writeCommand(payload: ByteArray): WriteResult {
            delay(bleDelay)
            return if (isClosed()) WriteResult.NotConnected else WriteResult.NotNow
        }

        override suspend fun readStatus(): ByteArray? {
            delay(bleDelay)
            return synchronized(lock) { if (closed) null else status().encode() }
        }

        override fun close() {
            synchronized(lock) { closed = true }
            job?.cancel()
        }
    }

    companion object {
        /** The address the app uses for the simulated terminal. */
        const val ADDRESS = "SIMULATED"
    }
}
