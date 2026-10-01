package org.opencell.app

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.opencell.app.audio.AndroidAudio
import org.opencell.app.audio.CallAudio
import org.opencell.app.audio.CallAudioRoute
import org.opencell.app.audio.KeySound
import org.opencell.app.audio.KeyTonePlayer
import org.opencell.app.audio.KeypadTones
import org.opencell.app.audio.TonePlanSetting
import org.opencell.app.ble.BleScanner
import org.opencell.app.ble.GattConnector
import org.opencell.app.data.DeveloperUnlock
import org.opencell.app.data.PrefsCallLogStore
import org.opencell.app.data.PrefsPhoneMemory
import org.opencell.app.data.TerminalRepository
import org.opencell.app.service.MissedCallNotifier
import org.opencell.codec2.Codec2
import org.opencell.core.calllog.CallLog
import org.opencell.core.link.Connector
import org.opencell.core.session.TerminalSession
import org.opencell.core.sim.SimulatedTerminal
import org.opencell.core.voice.AudioIo

/**
 * Manual dependency graph: one of each, living as long as the process.
 * [audio] makes the voice session's audio devices, and [keySound] the keypad's
 * tones (the unit tests pass silent and recording ones).
 */
class AppGraph(
    context: Context,
    audio: (Context, CallAudioRoute) -> AudioIo = ::AndroidAudio,
    keySound: (Context) -> KeySound = ::KeyTonePlayer,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val simulator = SimulatedTerminal(scope)

    /** Bluetooth back on: reconnect now rather than at the end of the backoff (up to 30 s). */
    private val gatt = GattConnector(context) { session.link.retryNow() }

    /** The simulated terminal answers at [SimulatedTerminal.ADDRESS]; everything else is BLE. */
    private val connector = Connector { target, events ->
        if (target.address == SimulatedTerminal.ADDRESS) simulator.connect(target, events) else gatt.connect(target, events)
    }

    private val prefs = context.getSharedPreferences("opencell", Context.MODE_PRIVATE)
    private val route = CallAudioRoute(context)
    private val micAllowed = MutableStateFlow(false)
    val tonePlan = TonePlanSetting(prefs)

    /** Console, Loopback, the demo terminal and the voice stats line, behind a static code. */
    val developerAccess = DeveloperUnlock(prefs)

    /** Keypad key tones: the setting, and what plays them. */
    val keypadTones = KeypadTones(prefs)
    val keySound: KeySound = keySound(context)

    /** Every call, on this phone only (dial-and-recents spec §4). */
    val callLog = CallLog(PrefsCallLogStore(context.getSharedPreferences(PrefsCallLogStore.FILE, Context.MODE_PRIVATE)))
    val session: TerminalSession = TerminalSession(
        connector, scope, phoneMemory = PrefsPhoneMemory(prefs),
        codecs = Codec2, audio = audio(context, route), micAllowed = micAllowed, tonePlan = tonePlan.plan,
        callLog = callLog,
    )
    val callAudio = CallAudio(session.voice, route, MutableStateFlow(false), micAllowed)
    /** Posts and clears the missed-call notification from the log's unseen count. */
    val missedCalls = MissedCallNotifier(context, callLog, scope)

    val repository = TerminalRepository(
        context = context,
        session = session,
        scanner = BleScanner(context),
        prefs = prefs,
        scope = scope,
    )

    /**
     * Whether [org.opencell.app.ui.MainActivity] is started. [org.opencell.app.service.LinkService]
     * uses this — not `ProcessLifecycleOwner` — to decide whether to keep the
     * incoming-call notification posted, so [org.opencell.app.ui.CallActivity]
     * (which the notification's full-screen intent or Answer action opens)
     * being on screen doesn't itself count as "the app is in front" and hide it.
     */
    val mainActivityInFront = MutableStateFlow(false)

    /** Whether [org.opencell.app.ui.CallActivity] is started (with [mainActivityInFront]: the app is in front). */
    val callActivityInFront = MutableStateFlow(false)
}

open class OpenCellApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = makeGraph()
    }

    protected open fun makeGraph(): AppGraph = AppGraph(this)
}

val Context.graph: AppGraph get() = (applicationContext as OpenCellApplication).graph
