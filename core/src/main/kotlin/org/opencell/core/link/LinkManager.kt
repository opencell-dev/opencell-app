package org.opencell.core.link

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.opencell.core.protocol.TerminalEvent
import org.opencell.core.protocol.TerminalStatus
import kotlin.time.TimeSource

/**
 * Keeps one terminal connected: connects through a [Connector], forwards
 * DOWN/STATUS, and reconnects with [reconnect] backoff whenever the link drops
 * or a connect fails, until [disconnect] is called.
 *
 * Pure Kotlin, so the reconnect logic is unit-tested with a fake [Connector].
 */
class LinkManager(
    private val connector: Connector,
    private val scope: CoroutineScope,
    private val reconnect: Backoff = Backoff.RECONNECT,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : TerminalLink {
    private val _state = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _status = MutableStateFlow<TerminalStatus?>(null)
    override val status: StateFlow<TerminalStatus?> = _status.asStateFlow()

    private val _statusUpdated = MutableStateFlow(0L)

    /** Wall-clock time of the last STATUS notification or read (0 = never), for "updated at" displays. */
    val statusUpdatedMillis: StateFlow<Long> = _statusUpdated.asStateFlow()

    private val _downlink = MutableSharedFlow<Downlink>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val downlink: SharedFlow<Downlink> = _downlink.asSharedFlow()

    private val _events = MutableSharedFlow<TerminalEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<TerminalEvent> = _events.asSharedFlow()

    private val control = Mutex()
    private var job: Job? = null

    @Volatile
    private var connection: Connection? = null

    /** The terminal the manager is trying to keep connected, or null. */
    val target: LinkTarget? get() = _state.value.target

    /** Connects to [target], dropping any current link first. Reconnects until [disconnect]. */
    suspend fun connect(target: LinkTarget) = control.withLock {
        job?.cancelAndJoin()
        _status.value = null
        job = scope.launch { run(target) }
    }

    suspend fun disconnect() = control.withLock {
        job?.cancelAndJoin()
        job = null
        _state.value = LinkState.Disconnected
    }

    override suspend fun writeUp(payload: ByteArray): WriteResult = withConnection { it.write(payload) }

    override suspend fun writeCommand(payload: ByteArray): WriteResult = withConnection { it.writeCommand(payload) }

    private suspend fun withConnection(write: suspend (Connection) -> WriteResult): WriteResult {
        val c = connection ?: return WriteResult.NotConnected
        return try {
            write(c)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            WriteResult.Failed(-1, e.message ?: e.javaClass.simpleName)
        }
    }

    override suspend fun refreshStatus(): TerminalStatus? {
        val c = connection ?: return null
        val raw = try {
            c.readStatus()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        return TerminalStatus.decodeOrNull(raw)?.also(::publishStatus)
    }

    private fun publishStatus(s: TerminalStatus) {
        _status.value = s
        _statusUpdated.value = wallClock()
    }

    private suspend fun run(target: LinkTarget) {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            _state.value = LinkState.Connecting(target, failures + 1)
            val closed = CompletableDeferred<String>()
            val events = object : ConnectionEvents {
                override fun onDownlink(payload: ByteArray) {
                    _downlink.tryEmit(Downlink(payload.copyOf(), timeSource.markNow(), wallClock()))
                }

                override fun onStatus(raw: ByteArray) {
                    TerminalStatus.decodeOrNull(raw)?.let(::publishStatus)
                }

                override fun onEvent(raw: ByteArray) {
                    _events.tryEmit(TerminalEvent.decode(raw))
                }

                override fun onClosed(reason: String) {
                    closed.complete(reason)
                }
            }
            val reason: String = try {
                val c = connector.connect(target, events)
                try {
                    connection = c
                    failures = 0
                    _state.value = LinkState.Connected(target, c.mtu)
                    refreshStatus()
                    closed.await()
                } finally {
                    connection = null
                    c.close()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            failures++
            val wait = reconnect.delayAfter(failures)
            _state.value = LinkState.WaitingToReconnect(target, failures, wait, reason)
            delay(wait)
        }
    }
}
