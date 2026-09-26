package org.opencell.core.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.opencell.core.protocol.Hex
import java.util.concurrent.atomic.AtomicLong

enum class ConsoleKind { UP, DOWN, INFO, ERROR }

class ConsoleEntry(
    val id: Long,
    val wallMillis: Long,
    val kind: ConsoleKind,
    val text: String,
    val payload: ByteArray? = null,
) {
    val hex: String? get() = payload?.let { Hex.format(it) }
    val ascii: String? get() = payload?.let { Hex.ascii(it) }
}

/** A bounded, thread-safe log the console screen renders. Oldest entries drop first. */
class ConsoleLog(
    private val capacity: Int = 2000,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private val ids = AtomicLong()
    private val _entries = MutableStateFlow<List<ConsoleEntry>>(emptyList())
    val entries: StateFlow<List<ConsoleEntry>> = _entries.asStateFlow()

    fun add(kind: ConsoleKind, text: String, payload: ByteArray? = null, wallMillis: Long = wallClock()) {
        val entry = ConsoleEntry(ids.incrementAndGet(), wallMillis, kind, text, payload?.copyOf())
        _entries.update { old -> if (old.size >= capacity) old.drop(old.size - capacity + 1) + entry else old + entry }
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
