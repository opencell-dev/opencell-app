package org.opencell.app.ui

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import org.opencell.app.ble.BlePermissions
import org.opencell.app.ble.ScannedDevice
import org.opencell.app.graph
import org.opencell.app.service.CallNotifier
import org.opencell.core.link.LinkTarget
import org.opencell.core.loopback.LoopbackConfig
import org.opencell.core.phone.DialPad
import org.opencell.core.protocol.ActivationQr
import org.opencell.core.protocol.GattContract
import org.opencell.core.protocol.Hex
import org.opencell.core.protocol.PayloadCheck
import org.opencell.core.protocol.PayloadRules
import org.opencell.core.protocol.QrParse
import org.opencell.core.sim.SimulatedTerminal
import kotlin.time.Duration.Companion.milliseconds

/** Phone-side prerequisites, re-checked whenever the activity resumes. */
data class Environment(
    val bluetoothPermission: Boolean = false,
    val notificationPermission: Boolean = false,
    val bluetoothOn: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val cameraPermission: Boolean = false,
    /** POST_NOTIFICATIONS (Android 13+): needed to ring while the app is in the background. */
    val notificationsAllowed: Boolean = true,
    /** Android 14+: the user allows full-screen incoming-call screens (USE_FULL_SCREEN_INTENT). */
    val fullScreenCalls: Boolean = true,
)

/** The Phone tab's two pages on a narrow screen; a wide one shows both side by side. */
enum class PhonePage { KEYPAD, RECENTS }

/** The console input parsed into bytes, or why it can't be sent. */
data class ConsoleDraft(val bytes: ByteArray?, val error: String?) {
    val size: Int get() = bytes?.size ?: 0
}

/**
 * Thin UI state holder. The link and all its state live in the app-scoped
 * [org.opencell.app.data.TerminalRepository]; this only adds form state and
 * forwards actions, so recreating it (or the activity) never touches the link.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = app.graph.repository
    private val session = app.graph.session

    val linkState = session.link.state
    val status = session.link.status
    val statusUpdated = session.link.statusUpdatedMillis
    val scan = repo.scan
    val wanted = repo.wanted
    val console = session.console.entries

    /** The terminal's scan list (Channels tab). */
    val channels = session.channels
    val loopback = session.loopback
    val lastTarget: LinkTarget? get() = repo.lastTarget

    var environment by mutableStateOf(Environment())
        private set

    fun refreshEnvironment() {
        val ctx = getApplication<Application>()
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        environment = Environment(
            bluetoothPermission = BlePermissions.hasBluetooth(ctx),
            notificationPermission = BlePermissions.ALL.all { BlePermissions.has(ctx, it) },
            bluetoothOn = adapter?.isEnabled == true,
            batteryUnrestricted = ctx.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(ctx.packageName),
            cameraPermission = BlePermissions.has(ctx, Manifest.permission.CAMERA),
            notificationsAllowed = notificationsAllowed(ctx),
            fullScreenCalls = CallNotifier(ctx).canUseFullScreenIntent(),
        )
    }

    /**
     * POST_NOTIFICATIONS on 13+ (without it the system refuses to post anything),
     * notifications enabled overall, and the calls channel itself not silenced by
     * the user (it may not exist yet, before the first incoming call: that's fine).
     */
    private fun notificationsAllowed(ctx: Application): Boolean {
        val runtimeOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            BlePermissions.has(ctx, Manifest.permission.POST_NOTIFICATIONS)
        val channel = ctx.getSystemService(NotificationManager::class.java).getNotificationChannel(CallNotifier.CHANNEL_ID)
        val channelOk = channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
        return runtimeOk && NotificationManagerCompat.from(ctx).areNotificationsEnabled() && channelOk
    }

    // --- terminal ---

    fun startScan() = repo.startScan()
    fun stopScan() = repo.stopScan()
    fun connect(device: ScannedDevice) = repo.connect(LinkTarget(device.address, device.name))
    fun connect(target: LinkTarget) = repo.connect(target)
    fun connectDemo() = repo.connect(LinkTarget(SimulatedTerminal.ADDRESS, "Demo terminal (simulated)"))
    fun disconnect() = repo.disconnect()
    fun refreshStatus() = session.refreshStatus()

    // --- console ---

    var consoleInput by mutableStateOf("")
    var hexMode by mutableStateOf(false)
    var allowOversize by mutableStateOf(false)

    fun consoleDraft(): ConsoleDraft {
        val bytes = if (hexMode) {
            when (val p = Hex.parse(consoleInput)) {
                is Hex.Parse.Ok -> p.bytes
                is Hex.Parse.Error -> return ConsoleDraft(null, p.message)
            }
        } else {
            consoleInput.encodeToByteArray()
        }
        val check = PayloadRules.check(bytes)
        val error = when {
            check is PayloadCheck.Empty -> check.message
            check is PayloadCheck.TooLong && !allowOversize -> check.message
            else -> null
        }
        return ConsoleDraft(bytes, error)
    }

    fun sendConsole() {
        val draft = consoleDraft()
        val bytes = draft.bytes ?: return
        if (draft.error != null) return
        session.send(bytes, enforceLimit = !allowOversize)
    }

    fun sendQuick(text: String) {
        session.send(text.encodeToByteArray())
    }

    fun clearConsole() = session.console.clear()

    // --- loopback ---

    var loopCount by mutableStateOf("20")
    var loopIntervalMs by mutableStateOf("1000")
    var loopPayload by mutableStateOf("HELLO")
    var loopTag by mutableStateOf(true)
    var loopError by mutableStateOf<String?>(null)
        private set

    /** The loopback config from the form, or a problem with it. */
    fun loopbackConfig(): Pair<LoopbackConfig?, String?> {
        val count = loopCount.trim().toIntOrNull() ?: return null to "Count must be a number"
        val interval = loopIntervalMs.trim().toLongOrNull() ?: return null to "Interval must be a number"
        val cfg = LoopbackConfig(
            count = count,
            interval = interval.milliseconds,
            payload = loopPayload.encodeToByteArray(),
            tagSequence = loopTag,
        )
        return cfg to cfg.problem()
    }

    fun startLoopback() {
        val (cfg, problem) = loopbackConfig()
        loopError = problem ?: session.startLoopback(cfg!!)
    }

    fun stopLoopback() = session.stopLoopback()

    // --- phone ---

    /** The phone side of the terminal; the call screen talks to it directly (it is shared with CallActivity). */
    val phoneSession = session.phone
    val callAudio = app.graph.callAudio
    val tonePlan = app.graph.tonePlan
    val phone = phoneSession.state

    // --- developer options ---

    private val developerAccess = app.graph.developerAccess
    val devUnlocked = developerAccess.unlocked

    /** True (and unlocked, remembered) if [code] is the developer code. */
    fun unlockDeveloper(code: String): Boolean = developerAccess.tryUnlock(code)
    fun lockDeveloper() = developerAccess.lock()

    /** True while connected (or connecting) to the demo terminal, which offers demo activation codes. */
    val isDemo: Boolean get() = (linkState.value.target ?: wanted.value)?.address == SimulatedTerminal.ADDRESS

    /** The activation code text field. */
    var codeInput by mutableStateOf("")

    /** A valid code waiting for the user to confirm its number and expiry. */
    var pendingCode by mutableStateOf<ActivationQr?>(null)
        private set
    var codeError by mutableStateOf<String?>(null)
        private set

    /** The user chose "Activate with a new code" on an activated terminal. */
    var reactivating by mutableStateOf(false)

    /** What the keypad holds: `0-9 * #` and a leading `+` ([DialPad]). */
    var dialInput by mutableStateOf("")
        private set

    /** Why the last Call didn't go out (the number changed under it); cleared by the next key. */
    var dialError by mutableStateOf<String?>(null)
        private set

    private val keypadTones = app.graph.keypadTones
    private val keySound = app.graph.keySound
    val keypadTonesOn = keypadTones.enabled

    fun setKeypadTones(on: Boolean) = keypadTones.set(on)

    /** Any change to the number: the last refusal (ours or the terminal's) is about the old one, so it goes. */
    private fun edit(text: String) {
        dialInput = text
        dialError = null
        if (phone.value.notice != null) phoneSession.clearNotice()
    }

    /** A key: its tone (if on), then the character. */
    fun press(key: Char) {
        if (keypadTones.enabled.value) keySound.play(key)
        edit(DialPad.press(dialInput, key))
    }

    /** A long press on 0. */
    fun pressPlus() = edit(DialPad.press(dialInput, '+'))

    fun backspace() = edit(DialPad.backspace(dialInput))

    fun clearDial() = edit("")

    /** Pasted text replaces the number (the keypad has no cursor). */
    fun paste(text: String) = edit(DialPad.fromPaste(text))

    /** A number from Recents, put on the keypad to check before calling. */
    fun putOnKeypad(number: String) {
        edit(DialPad.fromPaste(number))
        phonePage = PhonePage.KEYPAD
    }

    /** The Phone tab's page on a narrow screen. */
    var phonePage by mutableStateOf(PhonePage.KEYPAD)

    /** Set by [showRecents]; AppRoot switches to the Phone tab and clears it. */
    var recentsRequested by mutableStateOf(false)

    /** Opens the Phone tab on Recents (the missed-call notification, or a call just missed). */
    fun showRecents() {
        phonePage = PhonePage.RECENTS
        recentsRequested = true
    }

    // --- call log ---

    private val callLog = app.graph.callLog
    val callLogEntries = callLog.entries
    val unseenMissed = callLog.unseenMissed

    fun deleteCall(id: Long) = callLog.delete(id)
    fun clearCallLog() = callLog.clear()
    fun markMissedSeen() = callLog.markMissedSeen()

    /** A scanned or pasted code, checked like the terminal checks it. Valid codes wait for [confirmActivation]. */
    fun onCode(text: String) {
        when (val p = ActivationQr.parse(text)) {
            is QrParse.Ok -> {
                pendingCode = p.qr
                codeError = null
            }
            is QrParse.Invalid -> {
                pendingCode = null
                codeError = p.reason
            }
        }
    }

    fun useDemoCode() = onCode(getApplication<Application>().graph.simulator.demoQrText())

    /** The camera viewfinder is open. */
    var scanning by mutableStateOf(false)

    /** A QR code the camera read. Ignored while a code waits for confirmation; a valid one closes the camera. */
    fun onScanned(text: String) {
        if (pendingCode != null) return
        onCode(text)
        if (pendingCode != null) scanning = false
    }

    fun confirmActivation() {
        val code = pendingCode ?: return
        phoneSession.activate(code)
        pendingCode = null
        codeInput = ""
    }

    fun cancelCode() {
        pendingCode = null
        codeError = null
    }

    /** Leaves the activation result screen. */
    fun finishActivation() {
        phoneSession.clearActivation()
        reactivating = false
    }

    /** Call: the keypad's number. It stays on the keypad until the call screen opens (AppRoot clears it then). */
    fun dial() {
        dialError = phoneSession.dial(dialInput)
    }

    /** Dismisses the reason the last Call didn't go out. */
    fun clearDialError() {
        dialError = null
    }

    /** Calls [number] straight away: a test number, or Call back in Recents. */
    fun dialNumber(number: String) {
        dialError = phoneSession.dial(number)
    }

    fun deactivate() = phoneSession.deactivate()
    fun clearNotice() = phoneSession.clearNotice()

    companion object {
        const val MAX = GattContract.MAX_PAYLOAD
    }
}
