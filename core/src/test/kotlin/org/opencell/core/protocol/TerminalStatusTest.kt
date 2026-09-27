package org.opencell.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TerminalStatusTest {
    private fun bytes(hex: String): ByteArray = (Hex.parse(hex) as Hex.Parse.Ok).bytes

    /** Read from a real terminal on the bench (2026-09-26): GRANTED, 915, edge, -52 dBm, SNR 12.5 dB. */
    private val bench = bytes("04-00-02-00-cc-ff-32-00-88-04-ad-76-10-27-00-00-78-56-34-12")

    @Test
    fun decodesBenchCapture() {
        val s = TerminalStatus.decode(bench)
        assertEquals(TerminalState.GRANTED, s.state)
        assertEquals(Band.BAND_915, s.band)
        assertEquals(Tier.EDGE, s.tier)
        assertEquals(-52, s.rssiDbm)
        assertEquals(50, s.snrQuarterDb)
        assertEquals(12.5, s.snrDb, 0.0)
        assertEquals(0x76AD0488L, s.tmid)
        assertEquals("76AD0488", s.tmidHex)
        assertEquals(10000L, s.frame)
        assertEquals(0x12345678L, s.cellSeed)
    }

    @Test
    fun decodesAllStateCodes() {
        val expected = listOf(
            0 to TerminalState.SEARCH,
            1 to TerminalState.SYNCED,
            2 to TerminalState.ATTACHING,
            3 to TerminalState.IDLE,
            4 to TerminalState.GRANTED,
        )
        for ((code, state) in expected) {
            val raw = bench.copyOf().also { it[0] = code.toByte() }
            assertEquals(state, TerminalStatus.decode(raw).state)
        }
    }

    @Test
    fun unknownCodesAreKeptNotRejected() {
        val raw = bench.copyOf().also {
            it[0] = 9
            it[1] = 7
            it[2] = 0xFF.toByte()
        }
        val s = TerminalStatus.decode(raw)
        assertNull(s.state)
        assertNull(s.band)
        assertNull(s.tier)
        assertEquals(255, s.tierCode)
        assertEquals("Unknown (9)", s.stateLabel)
    }

    @Test
    fun u32FieldsAreUnsigned() {
        val raw = bench.copyOf()
        for (i in 8 until 20) raw[i] = 0xFF.toByte()
        raw[12] = 0xFE.toByte()
        val s = TerminalStatus.decode(raw)
        assertEquals(0xFFFFFFFFL, s.tmid)
        assertEquals(0xFFFFFFFEL, s.frame)
        assertEquals(4294967295L, s.cellSeed)
    }

    @Test
    fun i16FieldsAreSigned() {
        val raw = bench.copyOf()
        raw[4] = 0x00; raw[5] = 0x80.toByte() // -32768
        raw[6] = 0xF6.toByte(); raw[7] = 0xFF.toByte() // -10 qdB = -2.5 dB
        val s = TerminalStatus.decode(raw)
        assertEquals(-32768, s.rssiDbm)
        assertEquals(-2.5, s.snrDb, 0.0)
    }

    @Test
    fun flrcReportsZeroSnr() {
        val raw = bench.copyOf().also { it[1] = 1; it[2] = 0; it[6] = 0; it[7] = 0 }
        val s = TerminalStatus.decode(raw)
        assertEquals(Band.BAND_2G4, s.band)
        assertEquals(Tier.NEAR, s.tier)
        assertEquals(0.0, s.snrDb, 0.0)
        assertEquals("– (FLRC)", s.snrLabel)
    }

    @Test
    fun shortValueIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { TerminalStatus.decode(ByteArray(19)) }
        assertNull(TerminalStatus.decodeOrNull(ByteArray(0)))
    }

    @Test
    fun longerValueIsAcceptedForForwardCompatibility() {
        val s = TerminalStatus.decode(bench + byteArrayOf(1, 2, 3, 4))
        assertEquals(TerminalState.GRANTED, s.state)
    }

    @Test
    fun encodeRoundTripsAndMatchesFirmwarePacking() {
        val s = TerminalStatus.decode(bench)
        assertEquals(Hex.format(bench), Hex.format(s.encode()))
        val t = TerminalStatus(3, 1, 1, -120, -40, 0xDEADBEEFL, 0x80000000L, 1L)
        assertEquals(t, TerminalStatus.decode(t.encode()))
    }

    /** Contract v2: byte 3 is lc_sig_state_t, 0 (not activated) to 8 (releasing). */
    @Test
    fun byteThreeIsTheSignallingState() {
        assertEquals(SigState.NOT_ACTIVATED, TerminalStatus.decode(bench).sig)
        for (st in SigState.entries) {
            val raw = bench.copyOf().also { it[3] = st.code.toByte() }
            val s = TerminalStatus.decode(raw)
            assertEquals(st, s.sig)
            assertEquals(st.code, s.encode()[GattContract.STATUS_SIG].toInt())
        }
        assertEquals(listOf(4, 5, 6, 7, 8), SigState.entries.filter { it.hasCall }.map { it.code })
        val registered = bytes("04-00-02-03-cc-ff-32-00-88-04-ad-76-10-27-00-00-78-56-34-12")
        assertEquals(SigState.REGISTERED, TerminalStatus.decode(registered).sig)
        assertEquals("Registered", TerminalStatus.decode(registered).sigLabel)
    }

    @Test
    fun unknownSignallingStateIsKept() {
        val s = TerminalStatus.decode(bench.copyOf().also { it[3] = 9 })
        assertNull(s.sig)
        assertEquals("Unknown (9)", s.sigLabel)
    }

    /** Searching with nothing heard (rssi 0, BLE STATUS's only "nothing heard" sentinel) reads "No signal", not "0 dBm". */
    @Test
    fun searchingWithNothingHeardReadsNoSignal() {
        val raw = bench.copyOf().also { it[0] = TerminalState.SEARCH.code.toByte(); it[4] = 0; it[5] = 0 }
        val s = TerminalStatus.decode(raw)
        assertEquals("Search", s.stateLabel)
        assertEquals("No signal", s.signalLabel)
        assertEquals("no reading to show, not a fake 0.00 dB", "–", s.snrLabel)
    }

    /** Searching with a packet heard shows the usual reading. */
    @Test
    fun searchingWithAPacketHeardShowsTheRssi() {
        val raw = bench.copyOf().also { it[0] = TerminalState.SEARCH.code.toByte() } // bench's rssi is -52
        val s = TerminalStatus.decode(raw)
        assertEquals("Search", s.stateLabel)
        assertEquals("-52 dBm", s.signalLabel)
        assertEquals("%.2f dB".format(s.snrDb), s.snrLabel)
    }

    /** Outside SEARCH the terminal is synced to a cell, so 0 dBm (if it ever happened) would be a real reading, not the sentinel. */
    @Test
    fun aRealZeroRssiOutsideSearchIsNotHiddenAsNoSignal() {
        val raw = bench.copyOf().also { it[0] = TerminalState.GRANTED.code.toByte(); it[4] = 0; it[5] = 0 }
        val s = TerminalStatus.decode(raw)
        assertEquals("0 dBm", s.signalLabel)
    }
}
