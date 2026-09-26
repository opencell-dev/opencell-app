package org.opencell.app

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.opencell.app.ble.BleScanner
import org.opencell.app.ble.GattConnector
import org.opencell.app.data.TerminalRepository
import org.opencell.core.link.Connector
import org.opencell.core.session.TerminalSession
import org.opencell.core.sim.SimulatedTerminal

/** Manual dependency graph: one of each, living as long as the process. */
class AppGraph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val simulator = SimulatedTerminal(scope)
    private val gatt = GattConnector(context)

    /** The simulated terminal answers at [SimulatedTerminal.ADDRESS]; everything else is BLE. */
    private val connector = Connector { target, events ->
        if (target.address == SimulatedTerminal.ADDRESS) simulator.connect(target, events) else gatt.connect(target, events)
    }

    val session = TerminalSession(connector, scope)
    val repository = TerminalRepository(
        context = context,
        session = session,
        scanner = BleScanner(context),
        prefs = context.getSharedPreferences("opencell", Context.MODE_PRIVATE),
        scope = scope,
    )
}

class OpenCellApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

val Context.graph: AppGraph get() = (applicationContext as OpenCellApplication).graph
