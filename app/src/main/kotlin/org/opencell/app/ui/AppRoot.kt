package org.opencell.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import org.opencell.core.link.LinkState

enum class Destination(val label: String, val icon: ImageVector) {
    PHONE("Phone", Icons.Filled.Phone),
    TERMINAL("Terminal", Icons.Filled.Settings),
    CONSOLE("Console", Icons.AutoMirrored.Filled.Send),
    LOOPBACK("Loopback", Icons.Filled.Refresh),
}

/**
 * Top level. The navigation suite is a bottom bar on the Fold's cover screen
 * and a rail on the inner screen. Folding or unfolding only changes the
 * window size; the selected destination is saved and the link is untouched.
 * Phone is the subscriber's screen; Terminal, Console and Loopback are the
 * bring-up and diagnostics tools of v1.
 */
@Composable
fun AppRoot(vm: MainViewModel) {
    var destination by rememberSaveable { mutableStateOf(Destination.PHONE) }
    val phone by vm.phone.collectAsStateWithLifecycle()
    if (phone.call != null) {
        // Any call (ringing, connected or just ended) takes the whole screen, whatever tab is open.
        CallScreen(vm.phoneSession, vm.linkState, onRetry = vm::connect)
        return
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            Destination.entries.forEach { d ->
                item(
                    selected = d == destination,
                    onClick = { destination = d },
                    icon = { Icon(d.icon, contentDescription = null) },
                    label = { Text(d.label) },
                )
            }
        },
    ) {
        when (destination) {
            Destination.PHONE -> PhoneScreen(vm, onOpenTerminal = { destination = Destination.TERMINAL })
            Destination.TERMINAL -> TerminalListDetail(vm)
            Destination.CONSOLE -> WithStatusPanel(vm, onOpenTerminal = { destination = Destination.TERMINAL }) {
                ConsoleScreen(vm)
            }
            Destination.LOOPBACK -> WithStatusPanel(vm, onOpenTerminal = { destination = Destination.TERMINAL }) {
                LoopbackScreen(vm)
            }
        }
    }
}

/** True on the unfolded inner screen (and tablets): room for a side panel. */
@Composable
fun isWide(): Boolean =
    currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

/**
 * Wide screens show the live terminal status next to [content]; narrow ones
 * get a one-line link strip on top instead.
 */
@Composable
fun WithStatusPanel(vm: MainViewModel, onOpenTerminal: () -> Unit, content: @Composable () -> Unit) {
    if (isWide()) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { content() }
            VerticalDivider()
            Surface(Modifier.width(340.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                StatusContent(vm, compact = true)
            }
        }
    } else {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { content() }
            LinkStrip(vm, onClick = onOpenTerminal)
        }
    }
}

/** One line of link state for narrow screens. Tapping it opens the Terminal tab. */
@Composable
fun LinkStrip(vm: MainViewModel, onClick: () -> Unit) {
    val state by vm.linkState.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            LinkDot(state)
            Spacer(Modifier.width(8.dp))
            val s = status
            val text = if (state is LinkState.Connected && s != null) {
                "${state.target?.name ?: "Terminal"} · ${s.stateLabel} · ${s.signalLabel}"
            } else {
                state.summary()
            }
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
fun LinkDot(state: LinkState) {
    val color = when (state) {
        is LinkState.Connected -> Color(0xFF2E7D32)
        is LinkState.Connecting, is LinkState.Pairing -> Color(0xFFF9A825)
        is LinkState.WaitingToReconnect, is LinkState.PairingFailed -> MaterialTheme.colorScheme.error
        LinkState.Disconnected -> MaterialTheme.colorScheme.outline
    }
    Box(Modifier.size(10.dp).background(color, CircleShape))
}
