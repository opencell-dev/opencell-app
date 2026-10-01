package org.opencell.app.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.opencell.app.audio.TonePlanSetting
import org.opencell.app.ui.theme.MonoStyle
import org.opencell.core.link.LinkState
import org.opencell.core.link.LinkTarget
import org.opencell.core.phone.Activation
import org.opencell.core.phone.DialPad
import org.opencell.core.phone.PhoneState
import org.opencell.core.protocol.ActivationQr
import org.opencell.core.protocol.PhoneNumber
import org.opencell.core.protocol.RegMode
import org.opencell.core.protocol.SigState
import org.opencell.core.voice.TonePlans

/** Which of the Phone tab's screens shows: the setup states in order, then the dial screen once registered. */
private enum class PhoneStage { CONNECT, WAITING, ACTIVATION_RESULT, ONBOARDING, HOME }

/**
 * The Phone tab: what a subscriber sees. Picks one of: no terminal, waiting
 * for the terminal's state, activation result, activation (QR code), or the
 * registered home: the line card and the keypad (dial-and-recents spec §2).
 * Recents sits beside that page on a wide screen (the Fold's inner screen)
 * and in a second tab on a narrow one; before the first call, with the
 * terminal not yet registered, there are no tabs.
 */
@Composable
fun PhoneScreen(vm: MainViewModel, onOpenTerminal: () -> Unit) {
    val link by vm.linkState.collectAsStateWithLifecycle()
    val wanted by vm.wanted.collectAsStateWithLifecycle()
    val phone by vm.phone.collectAsStateWithLifecycle()
    val log by vm.callLogEntries.collectAsStateWithLifecycle()
    val unseen by vm.unseenMissed.collectAsStateWithLifecycle()
    val devUnlocked by vm.devUnlocked.collectAsStateWithLifecycle()
    val tonesOn by vm.keypadTonesOn.collectAsStateWithLifecycle()
    // Saveable: folding or unfolding recreates the activity, and must not close these.
    var menu by rememberSaveable { mutableStateOf(false) }
    var confirmDeactivate by rememberSaveable { mutableStateOf(false) }
    var chooseTones by rememberSaveable { mutableStateOf(false) }
    var confirmClearLog by rememberSaveable { mutableStateOf(false) }
    val activated = phone.linkUp && phone.sig != null && phone.sig != SigState.NOT_ACTIVATED
    val stage = when {
        wanted == null && !link.isConnected -> PhoneStage.CONNECT
        !phone.linkUp || phone.sig == null -> PhoneStage.WAITING
        phone.activation != Activation.Idle -> PhoneStage.ACTIVATION_RESULT
        phone.sig == SigState.NOT_ACTIVATED || vm.reactivating -> PhoneStage.ONBOARDING
        else -> PhoneStage.HOME
    }
    val terminalMenu = activated && phone.activeCall == null

    val tabbed = !(stage != PhoneStage.HOME && log.isEmpty()) && !isWide()
    Scaffold(
        // No app bar: on the narrow screen the Keypad | Recents tabs are the top row (with ⋮), so
        // the keypad gets the height; elsewhere a slim title row carries ⋮.
        topBar = {
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (tabbed) {
                    Box(Modifier.weight(1f)) { PhoneTabs(vm.phonePage, unseen, onSelect = { vm.phonePage = it }) }
                } else {
                    Text(
                        "OpenCell",
                        Modifier.weight(1f).padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                if (terminalMenu || log.isNotEmpty()) {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (terminalMenu) {
                                DropdownMenuItem(
                                    text = { Text("Activate with a new code") },
                                    onClick = { menu = false; vm.reactivating = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Deactivate terminal") },
                                    onClick = { menu = false; confirmDeactivate = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Call tones") },
                                    onClick = { menu = false; chooseTones = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Keypad tones") },
                                    trailingIcon = { Checkbox(checked = tonesOn, onCheckedChange = null) },
                                    onClick = { menu = false; vm.setKeypadTones(!tonesOn) },
                                )
                            }
                            if (log.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("Clear call log") },
                                    onClick = { menu = false; confirmClearLog = true },
                                )
                            }
                        }
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        val main: @Composable (Modifier) -> Unit = { m ->
            if (stage == PhoneStage.HOME) {
                HomePage(vm, phone, m)
            } else {
                SetupPage(vm, stage, link, phone, onOpenTerminal, m)
            }
        }
        val recents: @Composable (Modifier) -> Unit = { m ->
            Recents(
                log,
                unseen,
                canDial = phone.canDial,
                devUnlocked = devUnlocked,
                actions = RecentsActions(
                    onPutOnKeypad = vm::putOnKeypad,
                    onCall = vm::dialNumber,
                    onDelete = vm::deleteCall,
                    onSeen = vm::markMissedSeen,
                ),
                modifier = m,
            )
        }
        val area = Modifier.fillMaxSize().padding(padding)
        when {
            stage != PhoneStage.HOME && log.isEmpty() -> main(area)
            isWide() -> BoxWithConstraints(area) {
                // A short window (the cover screen sideways) gives the keypad page more width,
                // so the number fits beside the keys.
                val page = if (maxHeight < 480.dp) (maxWidth * 0.6f).coerceIn(420.dp, 560.dp) else 420.dp
                Row(Modifier.fillMaxSize()) {
                    recents(Modifier.weight(1f))
                    VerticalDivider()
                    main(Modifier.width(page))
                }
            }
            else -> Column(area) {
                when (vm.phonePage) {
                    PhonePage.KEYPAD -> main(Modifier.weight(1f))
                    PhonePage.RECENTS -> {
                        // A call back from here that didn't go out (refused, or not a number any more)
                        // says why here: the Keypad page that would show it is behind the other tab.
                        val why = vm.dialError ?: phone.notice
                        if (why != null) {
                            Box(Modifier.padding(horizontal = 16.dp)) {
                                NoticeLine(why, onDismiss = { vm.clearDialError(); vm.clearNotice() })
                            }
                        }
                        recents(Modifier.weight(1f))
                    }
                }
            }
        }
    }

    if (chooseTones) CallTonesDialog(vm.tonePlan, onDismiss = { chooseTones = false })

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

    // No text field in it, so a platform dialog is safe under Robolectric (see DeveloperOptionsDialog).
    if (confirmClearLog) {
        AlertDialog(
            onDismissRequest = { confirmClearLog = false },
            title = { Text("Clear the call log?") },
            text = { Text("Every call in Recents is deleted from this phone. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmClearLog = false; vm.clearCallLog() }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClearLog = false }) { Text("Cancel") } },
        )
    }
}

/** Keypad and Recents, the narrow screen's two pages; Recents carries the missed-call count. */
@Composable
private fun PhoneTabs(page: PhonePage, unseen: Int, onSelect: (PhonePage) -> Unit) {
    PrimaryTabRow(selectedTabIndex = page.ordinal) {
        Tab(selected = page == PhonePage.KEYPAD, onClick = { onSelect(PhonePage.KEYPAD) }, text = { Text("Keypad") })
        Tab(
            selected = page == PhonePage.RECENTS,
            onClick = { onSelect(PhonePage.RECENTS) },
            modifier = if (unseen > 0) Modifier.semantics { stateDescription = missedCallsLabel(unseen) } else Modifier,
            text = {
                BadgedBox(badge = { if (unseen > 0) Badge { Text("$unseen") } }) { Text("Recents") }
            },
        )
    }
}

/** Everything before the dial screen: connecting, waiting, activating. Scrolls. */
@Composable
private fun SetupPage(
    vm: MainViewModel,
    stage: PhoneStage,
    link: LinkState,
    phone: PhoneState,
    onOpenTerminal: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (stage) {
            PhoneStage.CONNECT -> ConnectPrompt(vm, onOpenTerminal)
            PhoneStage.WAITING -> WaitingForTerminal(
                link,
                onRetry = vm::connect,
                onBluetoothSettings = { context.startActivity(bluetoothSettings()) },
            )
            PhoneStage.ACTIVATION_RESULT -> ActivationResult(vm, phone)
            PhoneStage.ONBOARDING -> Onboarding(vm, again = phone.sig != SigState.NOT_ACTIVATED)
            PhoneStage.HOME -> Unit
        }
        phone.notice?.let { NoticeLine(it, onDismiss = vm::clearNotice) }
    }
}

/**
 * The registered home: the line card, what the phone needs to ring, then the
 * keypad. The keypad always gets the height it needs ([KeypadMetrics]): when
 * the full cards don't fit above it they collapse to one-line versions, and
 * whatever is left for them scrolls on its own, never the keypad. A window too
 * short for the keypad's column (the cover screen sideways) gets it side by side.
 */
@Composable
private fun HomePage(vm: MainViewModel, phone: PhoneState, modifier: Modifier) {
    val m = keypadMetrics()
    val readiness = readinessNotice(vm)
    val compact = compactHeaderHeight(phone, readiness != null)
    // The line card is one line by default; a tap shows the whole card (and the readiness card),
    // scrolling in the room the keypad leaves, and a tap on that card folds it again.
    var expanded by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val layout = when {
            maxHeight - m.columnMin >= compact -> KeypadLayout.COLUMN
            else -> KeypadLayout.choose(maxWidth, maxHeight - compact, m)
        }
        val keypadMin = if (layout == KeypadLayout.SIDE) m.sideMin else m.columnMin
        val room = maxHeight - keypadMin
        val header = when {
            room < 40.dp -> HeaderStyle.NONE
            expanded -> HeaderStyle.FULL
            else -> HeaderStyle.COMPACT
        }
        Column(Modifier.fillMaxSize()) {
            if (header != HeaderStyle.NONE) {
                Column(
                    Modifier
                        .heightIn(max = room)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = if (header == HeaderStyle.FULL) 12.dp else 4.dp),
                    verticalArrangement = Arrangement.spacedBy(if (header == HeaderStyle.FULL) 8.dp else 0.dp),
                ) {
                    if (header == HeaderStyle.FULL) {
                        LineCard(vm, phone, onCollapse = { expanded = false })
                    } else {
                        CompactLine(vm, phone, onExpand = { expanded = true })
                    }
                    readiness?.let { if (header == HeaderStyle.FULL) it.Card() else it.Line() }
                    phone.notice?.let { NoticeLine(it, onDismiss = vm::clearNotice) }
                }
            }
            Keypad(
                input = vm.dialInput,
                hint = DialPad.hint(vm.dialInput, phone.number),
                canDial = phone.canDial,
                notice = vm.dialError ?: phone.notice.takeIf { header == HeaderStyle.NONE },
                actions = KeypadActions(
                    onKey = vm::press,
                    onPlus = vm::pressPlus,
                    onBackspace = vm::backspace,
                    onClear = vm::clearDial,
                    onPaste = vm::paste,
                    onCall = vm::dial,
                    onTestNumber = vm::dialNumber,
                ),
                modifier = Modifier.weight(1f),
                layout = layout,
            )
        }
    }
}

private enum class HeaderStyle { FULL, COMPACT, NONE }

/** About how tall the one-line versions are. */
@Composable
private fun compactHeaderHeight(phone: PhoneState, readiness: Boolean): Dp {
    val t = MaterialTheme.typography
    return with(LocalDensity.current) {
        var h = 4.dp + max(40.dp, t.titleMedium.lineHeight.toDp() + 8.dp)
        if (readiness) h += max(48.dp, t.bodyMedium.lineHeight.toDp() * 2)
        if (phone.notice != null) h += max(48.dp, t.bodyMedium.lineHeight.toDp() * 2)
        h
    }
}

/**
 * The line card in one line: "+883-1-606-555-01234 · Registered · Part 15 🔒 · -73 dBm"
 * (a registration failure instead, in the error colour). A tap shows the whole card.
 */
@Composable
private fun CompactLine(vm: MainViewModel, phone: PhoneState, onExpand: () -> Unit) {
    val status by vm.status.collectAsStateWithLifecycle()
    val failed = phone.regFailureCode != null
    val details = listOfNotNull(
        if (failed) "Registration failed" else phone.sig?.label ?: "–",
        // Part 15 encrypts signalling and voice: the lock says so.
        phone.mode?.let { if (it == RegMode.PART15) "${it.label} 🔒" else it.label },
        status?.signalLabel,
    ).joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clickable(onClickLabel = "Show the line details", onClick = onExpand)
            .testTag(LINE_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            phone.number?.let { PhoneNumber.display(it) } ?: "Number not known yet",
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
        )
        Text(
            " · $details",
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = when {
                failed -> MaterialTheme.colorScheme.error
                phone.sig == SigState.REGISTERED -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The line, one line or the whole card: UI tests find the registered home by it. */
const val LINE_TAG = "phone-line"

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
internal fun WaitingForTerminal(link: LinkState, onRetry: (LinkTarget) -> Unit, onBluetoothSettings: () -> Unit) {
    if (link is LinkState.Pairing || link is LinkState.PairingFailed) {
        // The user must act (type the code, or Retry): a spinner would say "just wait".
        PairingNotice(link, onRetry, onBluetoothSettings)
        return
    }
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
            Text("Number ${a.number?.let(PhoneNumber::display) ?: "not known (invalid number from the terminal)"}", style = MaterialTheme.typography.titleMedium)
            val failure = phone.regFailureCode
            if (failure == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Registering with the network…")
                }
            } else {
                RegistrationFailure(phone)
                Button(onClick = vm::finishActivation) { Text("Continue") }
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
        "Scan the one-time activation code from the OpenCell portal (on the bench: oc-core admin sub issue), or paste its text. " +
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
            label = { Text("Activation code (opencell:2:…)") },
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

/** Your number, the registration state, the mode and the link. */
@Composable
private fun LineCard(vm: MainViewModel, phone: PhoneState, onCollapse: (() -> Unit)? = null) {
    val status by vm.status.collectAsStateWithLifecycle()
    val link by vm.linkState.collectAsStateWithLifecycle()
    val tap = if (onCollapse != null) Modifier.clickable(onClickLabel = "Show less", onClick = onCollapse) else Modifier
    Card(Modifier.fillMaxWidth().then(tap).testTag(LINE_TAG)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Your number", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(phone.number?.let { PhoneNumber.display(it) } ?: "Not known yet", style = MaterialTheme.typography.headlineSmall)
            Text(
                phone.sig?.label ?: "–",
                style = MaterialTheme.typography.titleMedium,
                color = if (phone.sig == SigState.REGISTERED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            phone.mode?.let { InfoRow("Mode", "${it.label} · ${it.description}") }
            val s = status
            InfoRow(
                "Link",
                (link.target?.name ?: "Terminal") + if (s != null) " · ${s.stateLabel} · ${s.signalLabel}" else "",
            )
            RegistrationFailure(phone)
        }
    }
}

/** The last REG_FAILED, while the terminal is still registering (it retries by itself). */
@Composable
private fun RegistrationFailure(phone: PhoneState) {
    val code = phone.regFailureCode ?: return
    Text(
        "Registration failed: ${phone.regFailure?.text ?: "reason $code"}. The terminal retries by itself.",
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** Something the phone needs before it can ring in the background: as a card, or as one line with the same action. */
private class ReadinessNotice(val title: String, val text: String, val onAllow: () -> Unit) {
    @Composable
    fun Card() = NoticeCard(title = title, text = text, action = "Allow", onAction = onAllow)

    @Composable
    fun Line() {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onAllow) { Text("Allow") }
        }
    }
}

/** What the phone needs to ring for incoming calls while the app is in the background, if anything. */
@Composable
private fun readinessNotice(vm: MainViewModel): ReadinessNotice? {
    val env = vm.environment
    val context = LocalContext.current
    var requestedNotifications by rememberSaveable { mutableStateOf(false) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        requestedNotifications = true
        vm.refreshEnvironment()
    }
    return if (!env.notificationsAllowed) {
        ReadinessNotice(
            title = "Notifications are off",
            text = "Without them the phone can't ring for incoming calls while the app is in the background.",
            onAllow = {
                val activity = context.findActivity()
                // Below 13 there's no runtime permission to ask for; with the permission already
                // granted, notifications (or the calls channel) are blocked in Settings; and a denial
                // where the system won't show a rationale again means asking is pointless. All three
                // need the app's notification settings, not a permission request.
                val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                val settingsOnly = permissionGranted || (
                    requestedNotifications && activity != null &&
                        !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
                    )
                if (settingsOnly) {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                } else {
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        )
    } else if (!env.fullScreenCalls && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        ReadinessNotice(
            title = "Incoming calls on the lock screen",
            text = "Allow OpenCell to show incoming calls full screen. Otherwise they only show as a notification.",
            onAllow = {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, "package:${context.packageName}".toUri()),
                    )
                } catch (e: ActivityNotFoundException) {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
                    )
                }
            },
        )
    } else {
        null
    }
}

/** Walks the Context wrapper chain to find the hosting Activity: Compose's LocalContext may be wrapped. */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
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

/** The call progress tones setting (voice spec §6.4): automatic by the phone's region, or a plan. */
@Composable
private fun CallTonesDialog(setting: TonePlanSetting, onDismiss: () -> Unit) {
    val choice by setting.choice.collectAsStateWithLifecycle()
    val options = listOf(TonePlanSetting.AUTO to "Automatic (${setting.automatic.label})") +
        TonePlans.ALL.map { it.id to it.label }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Call tones") },
        text = {
            Column {
                Text(
                    "The ringback, busy and failure tones you hear while a call is set up.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                options.forEach { (id, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = choice == id, role = Role.RadioButton) { setting.set(id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice == id, onClick = null)
                        Text(label, Modifier.padding(start = 8.dp, top = 12.dp, bottom = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
