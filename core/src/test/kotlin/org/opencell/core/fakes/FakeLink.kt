package org.opencell.core.fakes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.opencell.core.link.Downlink
import org.opencell.core.link.LinkState
import org.opencell.core.link.LinkTarget
import org.opencell.core.link.TerminalLink
import org.opencell.core.link.WriteResult
import org.opencell.core.protocol.TerminalStatus
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * A scriptable [TerminalLink] on virtual time: write results come from
 * [results] (Accepted when empty), and accepted writes can be echoed on DOWN
 * after [echoDelay] like the bench test cell does.
 */
class FakeLink(
    private val scope: CoroutineScope,
    private val timeSource: TimeSource.WithComparableMarks,
) : TerminalLink {
    val results = ArrayDeque<WriteResult>()
    val writes = mutableListOf<ByteArray>()
    val writeTimes = mutableListOf<kotlin.time.ComparableTimeMark>()

    /** BLE round trip of one write. */
    var writeLatency: Duration = Duration.ZERO

    /** Echo delay for the n-th accepted write (0-based), or null to drop it. */
    var echoDelay: (Int) -> Duration? = { null }
    private var accepted = 0

    override val state: StateFlow<LinkState> =
        MutableStateFlow(LinkState.Connected(LinkTarget("AA:BB", "OpenCell-76AD0488"), 247))
    override val status: StateFlow<TerminalStatus?> = MutableStateFlow(null)

    private val _downlink = MutableSharedFlow<Downlink>(extraBufferCapacity = 64)
    override val downlink: SharedFlow<Downlink> = _downlink

    override suspend fun writeUp(payload: ByteArray): WriteResult {
        writeTimes += timeSource.markNow()
        delay(writeLatency)
        writes += payload.copyOf()
        val r = results.removeFirstOrNull() ?: WriteResult.Accepted
        if (r == WriteResult.Accepted) {
            val d = echoDelay(accepted++)
            if (d != null) {
                val copy = payload.copyOf()
                scope.launch {
                    delay(d)
                    emitDown(copy)
                }
            }
        }
        return r
    }

    fun emitDown(payload: ByteArray) {
        check(_downlink.tryEmit(Downlink(payload, timeSource.markNow(), 0L)))
    }

    override suspend fun refreshStatus(): TerminalStatus? = null
}
