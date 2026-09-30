package org.opencell.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JitterBufferTest {
    private fun block(n: Int) = byteArrayOf(n.toByte())

    /** "P3" for Play(block 3), "C3@0.5" for Conceal(block 3, 0.5), "S" for Silence. */
    private fun Playout.label(): String = when (this) {
        is Playout.Play -> "P${payload[0]}"
        is Playout.Conceal -> "C${payload[0]}@$gain"
        Playout.Silence -> "S"
    }

    private fun JitterBuffer.polls(n: Int) = List(n) { poll().label() }

    @Test
    fun waitsForTwoBlocksThenPlaysInOrder() {
        val j = JitterBuffer()
        assertEquals("S", j.poll().label())
        j.offer(block(1), 0)
        assertEquals("S", j.poll().label())
        j.offer(block(2), 120)
        assertEquals(listOf("P1"), j.polls(1))
        j.offer(block(3), 240)
        assertEquals(listOf("P2", "P3"), j.polls(2))
        assertEquals(JitterCounts(played = 3), j.counts())
    }

    @Test
    fun aLostBlockIsConcealedWhereItWasLost() {
        val j = JitterBuffer()
        j.offer(block(1), 0)
        j.offer(block(2), 120)
        assertEquals("P1", j.poll().label())
        j.offer(block(4), 360) // block 3 (at 240) never came
        assertEquals(listOf("P2", "C2@0.5", "P4"), j.polls(3))
        assertEquals(1, j.counts().concealed)
    }

    @Test
    fun twoLostBlocksFadeByTwelveDecibels() {
        val j = JitterBuffer()
        j.offer(block(1), 0)
        j.offer(block(2), 120)
        assertEquals("P1", j.poll().label())
        j.offer(block(3), 240)
        assertEquals(listOf("P2", "P3"), j.polls(2)) // blocks 4 and 5 (360, 480) never come
        assertEquals("C3@0.5", j.poll().label()) // block 4's turn: nothing waiting
        j.offer(block(6), 600) // the gap says two were lost; one is already concealed
        assertEquals(listOf("C3@0.25", "P6"), j.polls(2))
        assertEquals(2, j.counts().concealed)
    }

    @Test
    fun jitterWithinHalfABlockIsNotALoss() {
        val j = JitterBuffer()
        j.offer(block(1), 0)
        j.offer(block(2), 170) // 50 ms late
        j.offer(block(3), 200) // 40 ms early
        assertEquals(listOf("P1", "P2", "P3"), j.polls(3))
        assertEquals(0, j.counts().concealed)
    }

    @Test
    fun runningDryConcealsTwiceThenGoesSilentAndRefills() {
        val j = JitterBuffer()
        j.offer(block(1), 0)
        j.offer(block(2), 120)
        assertEquals(listOf("P1", "P2", "C2@0.5", "C2@0.25", "S", "S"), j.polls(6))
        assertEquals(1, j.counts().rebuffered)
        j.offer(block(3), 5_000)
        assertEquals("S", j.poll().label()) // one block is not enough to start again
        j.offer(block(4), 5_120)
        assertEquals(listOf("P3", "P4"), j.polls(2))
    }

    @Test
    fun aBlockTheSpeakerAlreadyConcealedIsNotConcealedAgain() {
        val j = JitterBuffer()
        j.offer(block(1), 0)
        j.offer(block(2), 120)
        assertEquals(listOf("P1", "P2", "C2@0.5"), j.polls(3)) // block 3 is late: concealed while waiting
        j.offer(block(4), 360) // the 240 ms gap is block 3's, already concealed
        assertEquals("P4", j.poll().label())
        assertEquals(1, j.counts().concealed)
    }

    @Test
    fun aBurstAfterAStallIsTrimmedToTheTarget() {
        val j = JitterBuffer()
        for (i in 1..5) j.offer(block(i), 600) // five at once
        assertEquals(listOf("P4", "P5"), j.polls(2)) // 1-3 dropped: 240 ms of delay, not 600
        assertEquals(1, j.counts().trimmed)
    }

    @Test
    fun aFullBufferDropsTheOldest() {
        val j = JitterBuffer(target = 2, max = 3)
        for (i in 1..5) j.offer(block(i), i * 120L)
        assertEquals(3, j.depth())
        assertEquals(2, j.counts().overflowed)
    }

    @Test
    fun aLongGapIsNotFilledWithMarkers() {
        val j = JitterBuffer()
        j.offer(block(1), 0)
        j.offer(block(2), 2_000)
        assertEquals(2, j.depth())
    }

    @Test
    fun theTargetMustFitTheBuffer() {
        assertThrows(IllegalArgumentException::class.java) { JitterBuffer(target = 0) }
        assertThrows(IllegalArgumentException::class.java) { JitterBuffer(target = 7, max = 6) }
    }
}
