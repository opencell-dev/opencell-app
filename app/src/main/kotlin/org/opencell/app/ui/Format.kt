package org.opencell.app.ui

import org.opencell.core.link.LinkState
import org.opencell.core.link.PairingProblem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

fun clockTime(millis: Long): String =
    CLOCK.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/** A unix time in seconds as local date and time, e.g. an activation code's expiry. */
fun dateTime(unixSeconds: Long): String =
    DATE_TIME.format(Instant.ofEpochSecond(unixSeconds).atZone(ZoneId.systemDefault()))

fun Duration?.ms(): String = this?.let { "${it.inWholeMilliseconds} ms" } ?: "–"

fun LinkState.summary(): String = when (this) {
    LinkState.Disconnected -> "Disconnected"
    is LinkState.Connecting -> if (attempt > 1) "Connecting (attempt $attempt)…" else "Connecting…"
    is LinkState.Pairing -> "Pairing: enter the terminal's code"
    is LinkState.PairingFailed -> when (problem) {
        PairingProblem.FAILED -> "Pairing failed"
        PairingProblem.STALE_BOND -> "Pairing out of date"
    }
    is LinkState.Connected -> "Connected"
    is LinkState.WaitingToReconnect -> "Link lost, retry in ${delay.inWholeSeconds.coerceAtLeast(1)} s"
}
