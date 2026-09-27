package org.opencell.app.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.opencell.app.ui.theme.CallGreen
import org.opencell.app.ui.theme.MonoStyle
import org.opencell.core.link.LinkState
import org.opencell.core.phone.Activation
import org.opencell.core.phone.PhoneState
import org.opencell.core.protocol.ActivationQr
import org.opencell.core.protocol.PhoneNumber
import org.opencell.core.protocol.SigState
import org.opencell.core.sim.SimulatedTerminal

/**
 * The Phone tab: what a subscriber sees. Picks one of: no terminal, waiting
 * for the terminal's state, activation result, activation (QR code), or the
 * registered home screen with the dialer.
 */
@Composable
fun PhoneScreen(vm: MainViewModel, onOpenTerminal: () -> Unit) {
    val link by vm.linkState.collectAsStateWithLifecycle()
    val wanted by vm.wanted.collectAsStateWithLifecycle()
    val phone by vm.phone.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var confirmDeactivate by remember { mutableStateOf(false) }
    val activated = phone.linkUp && phone.sig != null && phone.sig != SigState.NOT_ACTIVATED

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OpenCell") },
                actions = {
                    if (activated && phone.activeCall == null) {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Activate with a new code") },
                                onClick = { menu = false; vm.reactivating = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Deactivate terminal") },
                                onClick = { menu = false; confirmDeactivate = true },
                            )
                        }
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                wanted == null && !link.isConnected -> ConnectPrompt(vm, onOpenTerminal)
                !phone.linkUp || phone.sig == null -> WaitingForTerminal(link)
                phone.activation != Activation.Idle -> ActivationResult(vm, phone)
                phone.sig == SigState.NOT_ACTIVATED || vm.reactivating -> Onboarding(vm, again = phone.sig != SigState.NOT_ACTIVATED)
                else -> Home(vm, phone)
            }
            phone.notice?.let { NoticeLine(it, onDismiss = vm::clearNotice) }
        }
    }

    if (confirmDeactivate) {
        AlertDialog(
            onDismissRequest = { confirmDeactivate = false },
            title = { Text("Deactivate this terminal?") },
            text = {
                Text(
                    "The terminal wipes its keys and stops registering. To use it again you need a new " +
                        "activation code from the portal.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDeactivate = false; vm.deactivate() }) { Text("Deactivate") }
            },
            dismissButton = { TextButton(onClick = { confirmDeactivate = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ConnectPrompt(vm: MainViewModel, onOpenTerminal: () -> Unit) {
    Text("No terminal connected", style = MaterialTheme.typography.headlineSmall)
    Text(
        "The phone talks to your OpenCell terminal over Bluetooth. Find it in the terminal list, or reconnect to the last one.",
        style = MaterialTheme.typography.bodyMedium,
    )
    vm.lastTarget?.let { last ->
        Button(onClick = { vm.connect(last) }) { Text("Reconnect to ${last.name ?: last.address}") }
    }
    OutlinedButton(onClick = onOpenTerminal) { Text("Find a terminal") }
}

@Composable
private fun WaitingForTerminal(link: LinkState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            if (link.isConnected) "Reading the terminal's state…" else link.summary(),
            style = MaterialTheme.typography.titleMedium,
        )
    }
    link.target?.let { Text(it.name ?: it.address, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun ActivationResult(vm: MainViewModel, phone: PhoneState) {
    when (val a = phone.activation) {
        is Activation.InProgress -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Activating…", style = MaterialTheme.typography.headlineSmall)
            }
            a.number?.let { Text("Number ${PhoneNumber.display(it)}", style = MaterialTheme.typography.titleMedium) }
            Text(
                "The terminal is agreeing keys with the network. This takes a few seconds.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        is Activation.Succeeded -> {
            // Once registered, the home screen takes over by itself.
            LaunchedEffect(phone.sig) { if (phone.sig == SigState.REGISTERED) vm.finishActivation() }
            Text("Activated", style = MaterialTheme.typography.headlineSmall)
            Text("Number ${PhoneNumber.display(a.number)}", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Registering with the network…")
            }
        }
        is Activation.Failed -> {
            ErrorCard("Activation failed", a.text)
            Button(onClick = vm::finishActivation) { Text("Try another code") }
        }
        Activation.Interrupted -> {
            ErrorCard(
                "Contact lost during activation",
                "The result didn't reach the phone. The terminal is now: ${phone.sig?.label ?: "unknown"}.",
            )
            Button(onClick = vm::finishActivation) { Text("OK") }
        }
        Activation.Idle -> Unit
    }
}

@Composable
private fun Onboarding(vm: MainViewModel, again: Boolean) {
    Text(if (again) "Activate with a new code" else "Activate your terminal", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Scan the one-time activation code from the OpenCell portal (on the bench: lcbench mkqr), or paste its text. " +
            "The terminal agrees its keys with the network; the phone keeps no secrets.",
        style = MaterialTheme.typography.bodyMedium,
    )
    if (again) {
        Text(
            "The terminal keeps its current keys unless the network accepts the new code.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (vm.pendingCode == null) ScanCode(vm)

    val pending = vm.pendingCode
    if (pending != null) {
        ConfirmCode(pending, onActivate = vm::confirmActivation, onCancel = vm::cancelCode)
    } else {
        OutlinedTextField(
            value = vm.codeInput,
            onValueChange = { vm.codeInput = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Activation code (opencell:1:…)") },
            textStyle = MonoStyle,
            isError = vm.codeError != null,
            supportingText = { vm.codeError?.let { Text(it) } },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.onCode(vm.codeInput) }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.onCode(vm.codeInput) }, enabled = vm.codeInput.isNotBlank()) { Text("Check code") }
            if (vm.isDemo) TextButton(onClick = vm::useDemoCode) { Text("Use a demo code") }
            if (again) TextButton(onClick = { vm.cancelCode(); vm.reactivating = false }) { Text("Cancel") }
        }
    }
}

/** Shows what the code is for before anything goes to the terminal (spec: show number and expiry first). */
@Composable
private fun ConfirmCode(code: ActivationQr, onActivate: () -> Unit, onCancel: () -> Unit) {
    val expired = code.isExpired(System.currentTimeMillis() / 1000)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Activation code", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(PhoneNumber.display(code.number), style = MaterialTheme.typography.headlineMedium)
            InfoRow("Valid until", dateTime(code.expiryUnix))
            InfoRow("Network key", "${code.keyId}")
            if (expired) {
                Text(
                    "This code has expired; the network will refuse it.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onActivate) { Text("Activate") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun Home(vm: MainViewModel, phone: PhoneState) {
    val status by vm.status.collectAsStateWithLifecycle()
    val link by vm.linkState.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Your number", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(phone.number?.let { PhoneNumber.display(it) } ?: "Not known yet", style = MaterialTheme.typography.headlineMedium)
            Text(
                phone.sig?.label ?: "–",
                style = MaterialTheme.typography.titleMedium,
                color = if (phone.sig == SigState.REGISTERED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            phone.mode?.let { InfoRow("Mode", "${it.label} · ${it.description}") }
            val s = status
            InfoRow(
                "Link",
                (link.target?.name ?: "Terminal") + if (s != null) " · ${s.stateLabel} · ${s.rssiDbm} dBm" else "",
            )
            phone.regFailure?.let {
                Text(
                    "Registration failed: ${it.text}. The terminal retries by itself.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
    Dialer(vm, enabled = phone.canDial)
    CallReadiness(vm)
}

/** What the phone needs to ring for incoming calls while the app is in the background. */
@Composable
private fun CallReadiness(vm: MainViewModel) {
    val env = vm.environment
    val context = LocalContext.current
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshEnvironment() }
    if (!env.notificationsAllowed) {
        NoticeCard(
            title = "Notifications are off",
            text = "Without them the phone can't ring for incoming calls while the app is in the background.",
            action = "Allow",
            onAction = { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) },
        )
    } else if (!env.fullScreenCalls && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        NoticeCard(
            title = "Incoming calls on the lock screen",
            text = "Allow OpenCell to show incoming calls full screen. Otherwise they only show as a notification.",
            action = "Allow",
            onAction = {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, "package:${context.packageName}".toUri()),
                )
            },
        )
    }
}

@Composable
private fun Dialer(vm: MainViewModel, enabled: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Make a call", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(
                value = vm.dialInput,
                onValueChange = { vm.dialInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Number (+883…)") },
                singleLine = true,
                isError = vm.dialError != null,
                supportingText = { vm.dialError?.let { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (enabled) vm.dial() }),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.dial() },
                    enabled = enabled && vm.dialInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = CallGreen),
                ) {
                    Icon(Icons.Filled.Call, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Call")
                }
                AssistChip(
                    onClick = { vm.dialInput = SimulatedTerminal.PEER; vm.dial(SimulatedTerminal.PEER) },
                    label = { Text("Test peer") },
                    enabled = enabled,
                )
            }
            if (!enabled) {
                Text(
                    "Calls need the terminal registered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun ErrorCard(title: String, text: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
fun NoticeLine(text: String, onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}
