package org.opencell.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencell.app.R
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * The call log never leaves the phone (dial-and-recents spec §4.4): `allowBackup="false"`
 * stops cloud backup, but since Android 12 not a device-to-device transfer, so the
 * extraction rules exclude its file from both.
 */
@RunWith(AndroidJUnit4::class)
class BackupRulesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Robolectric doesn't load android:dataExtractionRules into ApplicationInfo, so this reads the manifest (tests run in the module directory). */
    @Test
    fun theManifestUsesTheRules() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("""android:dataExtractionRules="@xml/data_extraction_rules""""))
        assertTrue(manifest.contains("""android:allowBackup="false""""))
    }

    @Test
    fun theCallLogIsExcludedFromCloudBackupAndDeviceTransfer() {
        val parser = context.resources.getXml(R.xml.data_extraction_rules)
        var section: String? = null
        val excluded = mutableSetOf<String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "cloud-backup", "device-transfer" -> section = parser.name
                "exclude" -> if (parser.getAttributeValue(null, "domain") == "sharedpref") {
                    excluded += "$section:${parser.getAttributeValue(null, "path")}"
                }
            }
        }
        val file = PrefsCallLogStore.FILE + ".xml"
        assertEquals(setOf("cloud-backup:$file", "device-transfer:$file"), excluded)
    }
}
