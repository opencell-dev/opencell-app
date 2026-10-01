package org.opencell.core.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.opencell.core.link.TerminalLink
import org.opencell.core.link.WriteResult
import org.opencell.core.protocol.Command
import org.opencell.core.protocol.ScanList
import org.opencell.core.protocol.UserChannel

/**
 * The terminal's scan list for the UI (channel-list spec §9): read from SCAN when
 * the link comes up, on [refresh] and after every change, and edited with COMMAND
 * SCAN. Like every COMMAND, a change is never retried; [problem] says why one failed.
 * Where the terminal is looking right now is in STATUS ([org.opencell.core.protocol.TerminalStatus.scanLabel]).
 */
class ChannelSession(
    private val link: TerminalLink,
    private val scope: CoroutineScope,
    private val log: (ConsoleKind, String) -> Unit = { _, _ -> },
) {
    private val _list = MutableStateFlow<ScanList?>(null)

    /** The last SCAN read; null while not connected (or on firmware without SCAN). */
    val list: StateFlow<ScanList?> = _list.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)

    /** Why the last read or change failed; null after a good one. */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    init {
        scope.launch {
            link.state.map { it.isConnected }.distinctUntilChanged().collect { up ->
                if (up) {
                    refreshNow()
                } else {
                    _list.value = null
                    _problem.value = null
                }
            }
        }
    }

    fun refresh(): Job = scope.launch { refreshNow() }

    /** Replaces the user's entries (at most [ScanList.MAX_USER]; empty clears them). */
    fun setUser(channels: List<UserChannel>): Job = change(Command.ScanSetUser(channels))

    /** Adds [channel] after the user's entries (the same frequency replaces its old entry). */
    fun addUser(channel: UserChannel): Job {
        val now = userChannels().filter { it.freqHz != channel.freqHz }
        if (now.size >= ScanList.MAX_USER) {
            _problem.value = TOO_MANY
            return Job().apply { complete() }
        }
        return setUser(now + channel)
    }

    fun removeUser(freqHz: Long): Job = setUser(userChannels().filter { it.freqHz != freqHz })

    /** Search outside the list after [after] passes ([ScanList.NEVER]: never), [chunk] channels a round. */
    fun setFallback(after: Int, chunk: Int): Job = change(Command.ScanSetFallback(after, chunk))

    fun forgetLearned(): Job = change(Command.ScanForgetLearned)

    private fun userChannels(): List<UserChannel> =
        _list.value?.userEntries.orEmpty().map { UserChannel(it.freqHz, it.fixed) }

    private suspend fun refreshNow() {
        val l = link.refreshScan()
        _list.value = l
        _problem.value = if (l == null && link.state.value.isConnected) NO_SCAN else null
    }

    private fun change(cmd: Command): Job = scope.launch {
        val r = link.writeCommand(cmd.encode())
        log(if (r == WriteResult.Accepted) ConsoleKind.INFO else ConsoleKind.ERROR, "${cmd.label}: ${r.label}")
        if (r == WriteResult.Accepted) {
            refreshNow()
        } else {
            _problem.value = when (r) {
                WriteResult.BadArgument -> REFUSED
                WriteResult.NotConnected -> "The terminal isn't connected"
                else -> "${cmd.label} failed: ${r.label}"
            }
        }
    }

    companion object {
        const val NO_SCAN = "This terminal's firmware has no scan list: update it"
        const val REFUSED = "The terminal refused this: a channel off the 915 MHz grid, a setting out of range, " +
            "or firmware without a scan list (update it)"
        const val TOO_MANY = "At most ${ScanList.MAX_USER} channels of your own: remove one first"
    }
}
