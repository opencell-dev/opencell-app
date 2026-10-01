package org.opencell.app

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.opencell.app.audio.TonePlanSetting
import org.opencell.app.ui.MainActivity
import org.opencell.core.voice.TonePlans
import org.robolectric.annotation.Config
import java.util.Locale

/** The call progress tones setting (voice spec §6.4). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class CallTonesTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @After
    fun tearDown() {
        compose.activity.graph.tonePlan.set(TonePlanSetting.AUTO)
        compose.activity.graph.repository.disconnect()
    }

    @Test
    fun automaticFollowsTheRegionAndAChoiceIsKept() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("t", Context.MODE_PRIVATE)
        assertEquals(TonePlans.UK, TonePlanSetting(prefs) { Locale.UK }.plan.value)
        assertEquals(TonePlans.NORTH_AMERICA, TonePlanSetting(prefs) { Locale.US }.plan.value)
        TonePlanSetting(prefs) { Locale.US }.set("uk")
        assertEquals(TonePlans.UK, TonePlanSetting(prefs) { Locale.US }.plan.value) // kept across restarts
    }

    @Test
    fun thePhoneMenuChoosesThePlan() {
        compose.connectDemoAndOpenPhone()
        compose.activateDemo()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Call tones").performClick()
        compose.onNodeWithText("Automatic (North American)").assertExists() // Robolectric's locale is en-US
        compose.onNodeWithText("United Kingdom").performClick()
        compose.onNodeWithText("Done").performClick()
        assertEquals(TonePlans.UK, compose.activity.graph.tonePlan.plan.value)
    }
}
