package org.opencell.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencell.core.fakes.FakeCodecs

/** Codec ids, the ranking and what a phone offers (codec-negotiation spec N1-N4, §7.1; OpenVoxNB spec V1-V3). */
class CodecChoiceTest {
    private val codec2 = CodecInfo(CodecId.CODEC2_1200)
    private val lpcnet = CodecInfo(CodecId.LPCNET_1733)

    /** An experimental codec with a reserved id: what OpenVoxNB is until its bitstream freezes. */
    private val trial = CodecInfo(7, experimental = true)

    @Test
    fun theCapsBitIsIdMinusOne() {
        assertEquals(0x01, CodecId.bit(CodecId.CODEC2_1200))
        assertEquals(0x02, CodecId.bit(CodecId.LPCNET_1733))
        assertEquals(0x80, CodecId.bit(8))
        assertEquals(0, CodecId.bit(CodecId.NONE))
        assertEquals(0, CodecId.bit(9))
        assertEquals(0x03, CodecId.caps(listOf(1, 2)))
        assertTrue(CodecId.inCaps(0x03, 2))
        assertFalse(CodecId.inCaps(0x01, 2))
        assertFalse(CodecId.inCaps(0xFF, CodecId.NONE))
    }

    @Test
    fun theBestSharedCodecWinsByTheFixedRanking() {
        assertEquals(CodecId.LPCNET_1733, CodecId.best(0x03))
        assertEquals(CodecId.CODEC2_1200, CodecId.best(0x01))
        assertEquals(CodecId.NONE, CodecId.best(0))
        assertEquals(CodecId.NONE, CodecId.best(0x80)) // a bit this app has no codec for is ignored
    }

    @Test
    fun automaticOffersEveryCodecButExperimentalOnes() {
        assertEquals(0x03, CodecChoice.allowedCaps(CodecPreference.Automatic, listOf(codec2, lpcnet)))
        assertEquals(0x01, CodecChoice.allowedCaps(CodecPreference.Automatic, listOf(codec2, trial)))
        // Nothing but experimental codecs: Automatic offers them rather than nothing.
        assertEquals(0x40, CodecChoice.allowedCaps(CodecPreference.Automatic, listOf(trial)))
    }

    @Test
    fun onlyOneCodecOffersJustThatOne() {
        assertEquals(0x40, CodecChoice.allowedCaps(CodecPreference.Only(7), listOf(codec2, trial)))
        assertEquals(0x01, CodecChoice.allowedCaps(CodecPreference.Only(1), listOf(codec2, lpcnet)))
    }

    @Test
    fun onlyACodecThisBuildLacksReadsAsAutomatic() {
        assertEquals(0x01, CodecChoice.allowedCaps(CodecPreference.Only(CodecId.LPCNET_1733), listOf(codec2)))
    }

    @Test
    fun theAnswerIsTheBestCodecBothSidesAllow() {
        assertEquals(CodecId.LPCNET_1733, CodecChoice.answer(offered = 0x03, allowed = 0x03))
        assertEquals(CodecId.CODEC2_1200, CodecChoice.answer(offered = 0x03, allowed = 0x01))
        assertEquals(CodecId.NONE, CodecChoice.answer(offered = 0x02, allowed = 0x01))
    }

    @Test
    fun labels() {
        assertEquals("Codec2 1200", codec2.choiceLabel)
        assertEquals("LPCNet 1733", lpcnet.label)
        assertEquals("codec 7 (experimental)", trial.choiceLabel)
    }

    @Test
    fun aFactoryListsTheCodecsItMakes() {
        assertEquals(listOf(codec2, lpcnet), FakeCodecs(setOf(2, 1)).codecs)
        assertEquals(emptyList<CodecInfo>(), VoiceCodecFactory.NONE.codecs)
    }
}
