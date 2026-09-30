package org.opencell.app.service

/**
 * Whether [LinkService] should hold the `microphone` foreground-service type
 * (voice spec §5.7), as a pure function. Android lets a service take that type
 * only while the app is in front with RECORD_AUDIO granted, and then keeps
 * microphone access when the app goes to the background. So: take it while a
 * call is active (any phase before ENDED) and the app is in front with the
 * permission; keep it ([holding]) while the call lasts wherever the app is;
 * give it up when there is no active call.
 */
fun micPlan(callActive: Boolean, appInFront: Boolean, granted: Boolean, holding: Boolean): Boolean = when {
    !callActive -> false
    holding -> granted
    else -> appInFront && granted
}
