package org.opencell.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The LGPL 2.1 notice and licence text travel inside every APK, next to libcodec2.so (voice spec §7, R14). */
@RunWith(AndroidJUnit4::class)
class ThirdPartyNoticesTest {
    private fun asset(path: String): String =
        ApplicationProvider.getApplicationContext<Application>().assets.open(path).use { it.reader().readText() }

    @Test
    fun theApkCarriesCodec2sLicenceAndNotice() {
        assertTrue(asset("licenses/codec2/COPYING").contains("GNU LESSER GENERAL PUBLIC LICENSE"))
        val notice = asset("licenses/codec2/NOTICE")
        for (needed in listOf("Codec 2", "LGPL", "310777b1c6f1af0bc7c72f5b32f80f6fd9136962", "libcodec2.so", "https://github.com/drowe67/codec2")) {
            assertTrue("NOTICE lacks $needed", notice.contains(needed))
        }
    }
}
