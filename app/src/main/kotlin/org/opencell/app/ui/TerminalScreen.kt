package org.opencell.app.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.opencell.app.ble.BlePermissions
import org.opencell.app.ble.ScannedDevice
import org.opencell.app.ui.theme.MonoStyle
import org.opencell.core.link.LinkState
import org.opencell.core.protocol.GattContract
import org.opencell.core.protocol.Hex
import org.opencell.core.protocol.Tmid

/**
 * Terminal tab: device list and terminal status as a list-detail pair.
 * Side by side on the inner screen; one at a time (with back) on the cover screen.
 */
@Composable
fun TerminalListDetail(vm: MainViewModel) {
    val navigator = rememberListDetailPaneScaffoldNavigator<Nothing>(
        scaffoldDirective = calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(currentWindowAdaptiveInfoV2()),
    )
    val scope = rememberCoroutineScope()
    NavigableListDetailPaneScaffold(
        navigator = navigator,
        listPane = {
            AnimatedPane {
                DevicesPane(vm, onShowStatus = { scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail) } })
            }
        },
        detailPane = {
            AnimatedPane {
                StatusPane(
                    vm,
                    onBack = if (navigator.canNavigateBack()) {
                        { scope.launch { navigator.navigateBack() } }
                    } else {
                        null
                    },
                )
            }
        },
    )
}

@Composable
fun DevicesPane(vm: MainViewModel, onShowStatus: () -> Unit) {
    val scan by vm.scan.collectAsStateWithLifecycle()
    val state by vm.linkState.collectAsStateWithLifecycle()
    val wanted by vm.wanted.collectAsStateWithLifecycle()
    val env = vm.environment
    val context = LocalContext.current
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refreshEnvironment()
    }
    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.refreshEnvironment()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OpenCell") },
                actions = {
                    if (scan.scanning) {
                        CircularProgressIndicator(Modifier.padding(end = 8.dp).width(20.dp), strokeWidth = 2.dp)
                        TextButton(onClick = vm::stopScan) { Text("Stop") }
                    } else {
                        TextButton(onClick = vm::startScan, enabled = env.bluetoothPermission && env.bluetoothOn) { Text("Scan") }
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!env.bluetoothPermission || !env.notificationPermission) {
                item {
                    NoticeCard(
                        title = "Permissions needed",
                        text = "Nearby devices lets the app find and connect to terminals (it never uses location). " +
                            "Notifications show the link while it runs in the background.",
                        action = "Grant",
                        onAction = { permissions.launch(BlePermissions.ALL) },
                        secondary = "App settings",
                        onSecondary = { context.startActivity(appSettings(context.packageName)) },
                    )
                }
            } else if (!env.bluetoothOn) {
                item {
                    NoticeCard(
                        title = "Bluetooth is off",
                        text = "Turn Bluetooth on to reach the terminal.",
                        action = "Turn on",
                        onAction = { enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                    )
                }
            }

            val target = state.target ?: wanted
            if (target != null) {
                item {
                    Card(Modifier.fillMaxWidth().clickable(onClick = onShowStatus)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            LinkDot(state)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(target.name ?: target.address, style = MaterialTheme.typography.titleMedium)
                                Text(state.summary(), style = MaterialTheme.typography.bodyMedium)
                            }
                            TextButton(onClick = onShowStatus) { Text("Status") }
                        }
                    }
                }
            } else {
                vm.lastTarget?.let { last ->
                    item {
                        OutlinedButton(onClick = { vm.connect(last); onShowStatus() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Reconnect to ${last.name ?: last.address}")
                        }
                    }
                }
            }

            item {
                Text(
                    if (scan.scanning) "Scanning for terminals…" else "Terminals",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            scan.error?.let { err ->
                item { Text(err, color = MaterialTheme.colorScheme.error) }
            }
            if (scan.devices.isEmpty() && !scan.scanning) {
                item {
                    Text(
                        "Tap Scan to look for terminals nearby. A terminal only advertises while no phone is connected to it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(scan.devices, key = { it.address }) { d ->
                DeviceRow(d, connected = state.target?.address == d.address) {
                    vm.connect(d)
                    onShowStatus()
                }
            }
            item { HorizontalDivider() }
            item {
                ListItem(
                    headlineContent = { Text("Demo terminal") },
                    supportingContent = { Text("Simulated terminal and echoing cell, no hardware needed") },
                    modifier = Modifier.clickable {
                        vm.connectDemo()
                        onShowStatus()
                    },
                )
            }
            if (!env.batteryUnrestricted) {
                item {
                    NoticeCard(
                        title = "Background use",
                        text = "Samsung may stop the link when the screen is off. Allow unrestricted battery use, " +
                            "and add OpenCell to Settings > Battery > Background usage limits > Never sleeping apps.",
                        action = "Allow",
                        onAction = {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()),
                            )
                        },
                        secondary = "App settings",
                        onSecondary = { context.startActivity(appSettings(context.packageName)) },
                    )
                }
            }
        }
    }
}

private fun appSettings(pkg: String) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$pkg".toUri())

@Composable
private fun DeviceRow(d: ScannedDevice, connected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(d.name ?: "Unnamed terminal", fontWeight = if (connected) FontWeight.Bold else null) },
        supportingContent = {
            Text(
                buildString {
                    append("TMID ")
                    append(d.tmid?.let { Tmid.format(it) } ?: "?")
                    append(" · ")
                    append(d.address)
                },
                style = MonoStyle,
            )
        },
        trailingContent = { Text("${d.rssi} dBm", style = MaterialTheme.typography.labelLarge) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
fun NoticeCard(
    title: String,
    text: String,
    action: String,
    onAction: () -> Unit,
    secondary: String? = null,
    onSecondary: () -> Unit = {},
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAction) { Text(action) }
                if (secondary != null) TextButton(onClick = onSecondary) { Text(secondary) }
            }
        }
    }
}

@Composable
fun StatusPane(vm: MainViewModel, onBack: (() -> Unit)?) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Status") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refreshStatus() }) { Icon(Icons.Filled.Refresh, "Refresh status") }
                },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(Modifier.padding(padding)) { StatusContent(vm, compact = false) }
    }
}

/** Connection and decoded STATUS. Used by the status pane and the wide-screen side panel. */
@Composable
fun StatusContent(vm: MainViewModel, compact: Boolean) {
    val state by vm.linkState.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val updated by vm.statusUpdated.collectAsStateWithLifecycle()
    val wanted by vm.wanted.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinkDot(state)
                    Spacer(Modifier.width(8.dp))
                    Text(state.summary(), style = MaterialTheme.typography.titleMedium)
                }
                val target = state.target ?: wanted
                if (target != null) {
                    InfoRow("Terminal", target.name ?: "–")
                    InfoRow("Address", target.address)
                }
                (state as? LinkState.Connected)?.let { InfoRow("MTU", "${it.mtu}") }
                (state as? LinkState.WaitingToReconnect)?.let { InfoRow("Last error", it.reason) }
                Spacer(Modifier.padding(2.dp))
                if (wanted != null) {
                    FilledTonalButton(onClick = vm::disconnect) { Text("Disconnect") }
                } else if (!compact) {
                    Text(
                        "Not connected. Pick a terminal from the list.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        val s = status
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (s == null) {
                    Text("No STATUS yet", style = MaterialTheme.typography.titleMedium)
                } else {
                    Text(s.stateLabel, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    s.state?.let {
                        Text(it.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.padding(2.dp))
                    InfoRow("Band", s.bandLabel)
                    InfoRow("Tier", s.tierLabel)
                    InfoRow("Signalling", s.sigLabel)
                    InfoRow("RSSI", "${s.rssiDbm} dBm")
                    InfoRow("SNR", if (s.snrQuarterDb == 0 && s.band?.code == 1) "– (FLRC)" else "%.2f dB".format(s.snrDb))
                    InfoRow("TMID", s.tmidHex)
                    InfoRow("Frame", "${s.frame}")
                    InfoRow("Cell seed", "0x%08X".format(s.cellSeed))
                    if (updated > 0) InfoRow("Updated", clockTime(updated))
                    if (!compact) {
                        Text("Raw " + Hex.format(s.encode(), "-"), style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.padding(2.dp))
                OutlinedButton(onClick = { vm.refreshStatus() }, enabled = state.isConnected) { Text("Refresh") }
            }
        }

        if (!compact) {
            Text(
                "UP takes one app data frame of up to ${GattContract.MAX_PAYLOAD} bytes per write; the terminal sends " +
                    "one per ${GattContract.FRAME_MILLIS} ms frame, and only while it holds a grant (0x80 otherwise).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MonoStyle)
    }
}
