package com.radiopoint.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.radiopoint.app.service.RadioService
import com.radiopoint.app.service.RadioState
import com.radiopoint.app.ui.MainActivity
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RadioSmokeTest {
    @get:Rule val permissions:GrantPermissionRule=GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun prepare() {
        context.stopService(Intent(context,RadioService::class.java))
        await { !RadioState.listening.value }
        context.getSharedPreferences("radio",Context.MODE_PRIVATE).edit().clear().putInt("unit",17).commit()
    }
    @After fun stop(){context.stopService(Intent(context,RadioService::class.java));await { !RadioState.listening.value }}
    @Test fun launchDoesNotAutomaticallyOpenMicrophone() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.btnSettings)).check(matches(withText("UNIT 17")))
            onView(withId(R.id.btnCustomMaps)).check(matches(isDisplayed()))
            onView(withId(R.id.btnArm)).check(matches(withText("ARM · 5 MIN")))
            Assert.assertFalse(RadioState.listening.value)
            val screenshot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir(null),"smoke.png").outputStream().use { out->screenshot.compress(Bitmap.CompressFormat.PNG,100,out) }
        }
    }
    @Test fun unitSetupRejectsZeroAndPersistsValidNumber() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.btnSettings)).perform(click())
            onView(isAssignableFrom(EditText::class.java)).perform(replaceText("0"),closeSoftKeyboard())
            onView(withText("Save")).perform(click())
            onView(isAssignableFrom(EditText::class.java)).check(matches(hasErrorText("Choose 1–255")))
            onView(isAssignableFrom(EditText::class.java)).perform(replaceText("23"),closeSoftKeyboard())
            onView(withText("Save")).perform(click())
            onView(withId(R.id.btnSettings)).check(matches(withText("UNIT 23")))
            Assert.assertEquals(23,context.getSharedPreferences("radio",Context.MODE_PRIVATE).getInt("unit",0))
        }
    }
    @Test fun explicitArmStartsForegroundMicrophoneAndDisarmStopsIt() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.btnArm)).perform(click())
            await { RadioState.listening.value }
            // The button's state is read from RadioState, so a second click stops the service.
            onView(withId(R.id.btnArm)).perform(click())
            await { !RadioState.listening.value }
            Assert.assertFalse(RadioState.transmitting.value)
        }
    }
    private fun await(predicate:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+10_000
        while(!predicate() && SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100)
        Assert.assertTrue("Timed out; radio state: ${RadioState.message.value}",predicate())
    }
}
