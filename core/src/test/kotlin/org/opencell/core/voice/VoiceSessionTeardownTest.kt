package org.opencell.core.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencell.core.fakes.FakeAudio
import org.opencell.core.fakes.FakeLink
import org.opencell.core.phone.Call
import org.opencell.core.phone.CallPhase
import org.opencell.core.phone.Direction
import org.opencell.core.phone.PhoneState
import org.opencell.core.protocol.SigState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.TimeSource

/**
 * On real threads (the virtual-time tests can't interleave): a codec is closed only after
 * the voice job has ended, never while an encode or decode is still running. OpenVox's
 * JNI calls are not synchronized with close(), so this ordering is what keeps a freed
 * native codec from being used.
 */
class VoiceSessionTeardownTest {
    /** An encoder that stays inside [encode] until released, and notes whether close() arrived meanwhile. */
    private class StuckCodec : VoiceCodec {
        override val codecId = CodecId.CODEC2_1200
        override val frameSamples = 320
        override val frameBytes = 6
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closedLatch = CountDownLatch(1)
        val inside = AtomicBoolean(false)
        val closedWhileInside = AtomicBoolean(false)
        val closes = AtomicInteger()

        override fun encode(pcm: ShortArray): ByteArray {
            inside.set(true)
            entered.countDown()
            release.await()
            inside.set(false)
            return ByteArray(frameBytes)
        }

        override fun decode(bits: ByteArray) = ShortArray(frameSamples)

        override fun close() {
            if (inside.get()) closedWhileInside.set(true)
            closes.incrementAndGet()
            closedLatch.countDown()
        }
    }

    @Test
    fun theCodecIsClosedOnlyAfterAnInFlightEncodeReturns() {
        val made = mutableListOf<StuckCodec>()
        val factory = VoiceCodecFactory { StuckCodec().also { synchronized(made) { made += it } } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val idle = PhoneState(linkUp = true, sig = SigState.REGISTERED)
            val call = Call(7, Direction.OUTGOING, "+883160655500100", CallPhase.CONNECTED, codec = CodecId.CODEC2_1200)
            val phone = MutableStateFlow(idle.copy(sig = SigState.IN_CALL, call = call))
            VoiceSession(FakeLink(scope, TimeSource.Monotonic), phone, factory, FakeAudio(), scope)

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (synchronized(made) { made.size } < 2 && System.nanoTime() < deadline) Thread.sleep(5)
            val encoder = synchronized(made) { made.first() } // the factory makes the encoder first
            assertTrue("the uplink never reached encode", encoder.entered.await(5, TimeUnit.SECONDS))

            phone.value = idle // the call ends while the encode is still running
            assertFalse("closed while an encode was in flight", encoder.closedLatch.await(500, TimeUnit.MILLISECONDS))
            assertEquals(0, encoder.closes.get())

            encoder.release.countDown()
            assertTrue("never closed after the encode returned", encoder.closedLatch.await(5, TimeUnit.SECONDS))
            assertFalse(encoder.closedWhileInside.get())
            assertEquals(1, encoder.closes.get())
            synchronized(made) { assertEquals(2, made.size) }
            assertTrue(made[1].closedLatch.await(5, TimeUnit.SECONDS)) // and so is the decoder
        } finally {
            made.forEach { it.release.countDown() }
            scope.cancel()
        }
    }
}
