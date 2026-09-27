package org.opencell.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.opencell.app.graph

/** The incoming-call notification's Reject button: sends REJECT without opening the app. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REJECT) {
            context.graph.session.phone.reject()
            CallNotifier(context).cancel()
        }
    }

    companion object {
        const val ACTION_REJECT = "org.opencell.app.REJECT"
    }
}
