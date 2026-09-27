package org.opencell.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.opencell.app.graph

/**
 * The incoming-call notification's Reject button: sends REJECT without
 * opening the app. It doesn't touch the notification or the ringer itself —
 * [LinkService]'s state-driven collector does that once the phase actually
 * leaves INCOMING, so a refused REJECT (a race with the far end) leaves both
 * up rather than this receiver hiding them regardless.
 */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REJECT) {
            context.graph.session.phone.reject()
        }
    }

    companion object {
        const val ACTION_REJECT = "org.opencell.app.REJECT"
    }
}
