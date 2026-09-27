package org.opencell.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.opencell.app.ble.BleScanner
import org.opencell.app.ble.ScanException
import org.opencell.app.ble.ScannedDevice
import org.opencell.app.service.LinkService
import org.opencell.core.link.LinkState
import org.opencell.core.link.LinkTarget
import org.opencell.core.session.TerminalSession
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class ScanState(
    val scanning: Boolean = false,
    val devices: List<ScannedDevice> = emptyList(),
    val error: String? = null,
)

/**
 * App-scoped owner of the terminal link. It outlives activities (fold/unfold,
 * rotation, the user leaving the app) because it lives in the Application;
 * [LinkService] keeps the process in the foreground while a link is wanted.
 */
class TerminalRepository(
    private val context: Context,
    val session: TerminalSession,
    private val scanner: BleScanner,
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
) {
    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan.asStateFlow()
    private var scanJob: Job? = null

    /** Connect/disconnect requests, applied strictly in order. */
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch { for (command in commands) command() }
        scope.launch {
            // Whether the link is pairing or ended in a pairing failure, for [resume] after the
            // process was killed. Disconnected (never connected, or the user disconnected) says nothing.
            session.link.state.collect { s ->
                val halted = when (s) {
                    LinkState.Disconnected -> return@collect
                    is LinkState.Pairing, is LinkState.PairingFailed -> true
                    is LinkState.Connecting, is LinkState.Connected, is LinkState.WaitingToReconnect -> false
                }
                prefs.edit { putBoolean(KEY_PAIRING_HALTED, halted) }
            }
        }
    }

    private val _wanted = MutableStateFlow<LinkTarget?>(null)

    /** The terminal the user asked to be connected to, or null. [LinkService] runs while this is set. */
    val wanted: StateFlow<LinkTarget?> = _wanted.asStateFlow()

    /** The last terminal connected to, offered for one-tap reconnect. */
    val lastTarget: LinkTarget?
        get() = prefs.getString(KEY_ADDRESS, null)?.let { LinkTarget(it, prefs.getString(KEY_NAME, null)) }

    /** Set while a link is wanted; lets a restarted service resume after the process was killed. */
    val resumeTarget: LinkTarget?
        get() = if (prefs.getBoolean(KEY_RESUME, false)) lastTarget else null

    fun startScan(duration: Duration = 20.seconds) {
        scanJob?.cancel()
        _scan.value = ScanState(scanning = true)
        scanJob = scope.launch {
            try {
                withTimeoutOrNull(duration) {
                    scanner.scan().collect { d ->
                        _scan.update { s ->
                            val others = s.devices.filterNot { it.address == d.address }
                            val merged = d.copy(name = d.name ?: s.devices.firstOrNull { it.address == d.address }?.name)
                            s.copy(devices = (others + merged).sortedBy { it.address })
                        }
                    }
                }
            } catch (e: ScanException) {
                _scan.update { it.copy(error = e.message) }
            } finally {
                _scan.update { it.copy(scanning = false) }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
    }

    fun connect(target: LinkTarget) {
        stopScan()
        _wanted.value = target
        prefs.edit {
            putString(KEY_ADDRESS, target.address)
            putString(KEY_NAME, target.name)
            putBoolean(KEY_RESUME, true)
            putBoolean(KEY_PAIRING_HALTED, false)
        }
        commands.trySend { session.connect(target) }
        LinkService.start(context)
    }

    /**
     * Resumes the link to [resumeTarget] after the process was killed (the
     * system restarted [LinkService]). If it was pairing or had ended in a
     * pairing failure, it stays [LinkState.PairingFailed] until the user taps
     * Retry: a connect would start a system pairing nobody is there to answer,
     * which times out and costs one of the terminal's 3 tries.
     */
    fun resume() {
        val target = resumeTarget ?: return
        if (!prefs.getBoolean(KEY_PAIRING_HALTED, false)) {
            connect(target)
            return
        }
        _wanted.value = target
        commands.trySend { session.link.awaitPairingRetry(target, "pairing didn't finish before the app was closed") }
    }

    fun disconnect() {
        _wanted.value = null
        prefs.edit {
            putBoolean(KEY_RESUME, false)
            putBoolean(KEY_PAIRING_HALTED, false)
        }
        commands.trySend { session.disconnect() }
    }

    private companion object {
        const val KEY_ADDRESS = "last_address"
        const val KEY_NAME = "last_name"
        const val KEY_RESUME = "resume"
        const val KEY_PAIRING_HALTED = "pairing_halted"
    }
}
