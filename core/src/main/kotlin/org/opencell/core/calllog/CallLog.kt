package org.opencell.core.calllog

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.opencell.core.phone.FinishedCall
import org.opencell.core.voice.VoiceCounters

/** Where the call log's text lives between runs (Android: the app's preferences). Null: no log yet. */
interface CallLogStore {
    fun read(): String?
    fun write(text: String?)

    companion object {
        fun inMemory(initial: String? = null): CallLogStore = object : CallLogStore {
            @Volatile private var text = initial
            override fun read() = text
            override fun write(text: String?) {
                this.text = text
            }
        }
    }
}

/**
 * The phone's call log (dial-and-recents spec §4): every call the phone saw,
 * newest first, at most [cap] (the oldest go first). Local only: it lives in
 * [store] and is never sent anywhere. Each change is written through at once.
 * [unseenMissed] counts missed calls not yet looked at in Recents (the badge).
 */
class CallLog(private val store: CallLogStore = CallLogStore.inMemory(), private val cap: Int = MAX_ENTRIES) {
    private val lock = Any()
    private val _entries = MutableStateFlow(CallLogCodec.decode(store.read()).take(cap))
    val entries: StateFlow<List<CallLogEntry>> = _entries.asStateFlow()

    private val _unseenMissed = MutableStateFlow(unseen(_entries.value))
    val unseenMissed: StateFlow<Int> = _unseenMissed.asStateFlow()

    /** Adds [call] at the top, with its [voice] counters. Returns the new entry. */
    fun record(call: FinishedCall, voice: VoiceCounters?): CallLogEntry = synchronized(lock) {
        val list = _entries.value
        val entry = CallLogEntry.of(call, id = (list.maxOfOrNull { it.id } ?: 0) + 1, voice = voice)
        publish((listOf(entry) + list).take(cap))
        entry
    }

    fun delete(id: Long) = synchronized(lock) {
        val list = _entries.value
        if (list.any { it.id == id }) publish(list.filterNot { it.id == id })
    }

    fun clear() = synchronized(lock) {
        publish(emptyList())
    }

    /** The user is looking at Recents: every missed call counts as seen. */
    fun markMissedSeen() = synchronized(lock) {
        val list = _entries.value
        if (list.any { !it.seen }) publish(list.map { if (it.seen) it else it.copy(seen = true) })
    }

    private fun publish(list: List<CallLogEntry>) {
        store.write(if (list.isEmpty()) null else CallLogCodec.encode(list))
        _entries.value = list
        _unseenMissed.value = unseen(list)
    }

    private fun unseen(list: List<CallLogEntry>) = list.count { !it.seen }

    companion object {
        const val MAX_ENTRIES = 500
    }
}
