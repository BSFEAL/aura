package systems.kaula.aura

import android.Manifest
import android.app.NotificationManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class UiLifecycleTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            java.io.FileInputStream(it.fileDescriptor).bufferedReader().use { reader->reader.readText() }
        }
    }
    @Test fun deniedPermissionNeverActivatesMicrophone() {
        val pkg=compose.activity.packageName
        assertNotEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,compose.activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        compose.onNodeWithText("Start monitoring",useUnmergedTree=true).performClick()
        compose.waitForIdle()
        assertFalse(MonitorService.active.value)
        shell("input keyevent 4")
    }
    @Test fun explicitStartStopAndForegroundNotification() {
        shell("pm grant ${compose.activity.packageName} ${Manifest.permission.RECORD_AUDIO}")
        compose.onNodeWithText("Start monitoring",useUnmergedTree=true).performClick()
        compose.waitUntil(20000) { MonitorService.modelLoaded.value }
        val manager=compose.activity.getSystemService(NotificationManager::class.java)
        compose.waitUntil(10000) { manager.activeNotifications.any { it.id==1 } }
        assertTrue("Active service must publish the ongoing notification",manager.activeNotifications.any { it.id==1 })
        compose.onNodeWithText("Stop monitoring",useUnmergedTree=true).performClick()
        compose.waitUntil(20000) { !MonitorService.active.value }
        compose.onNodeWithText("Start monitoring",useUnmergedTree=true).assertIsDisplayed()
        compose.waitUntil(10000) { manager.activeNotifications.none { it.id==1 } }
        assertFalse("Stopped service notification must be removed",manager.activeNotifications.any { it.id==1 })
    }
    @Test fun ruleEditingAndEventRendering() {
        compose.onAllNodesWithText("Rules").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("Save rule").performScrollTo().performClick()
        compose.onAllNodesWithText("Edit").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("Save changes").performScrollTo().performClick()
        compose.runOnIdle {
            Store.saveEvent(compose.activity,LocalEvent(id="ui-test-event",ruleId="test",eventType="dog_bark",classId="/m/05tny_",label="Possible Dog barking",startMs=1780000000000,peakScore=.8f,meanScore=.7f,modelVersion=ModelSpec.VERSION,provenance="model_detected"))
        }
        compose.onAllNodesWithText("Events").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("Possible Dog barking").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("model_detected · ended").assertIsDisplayed()
    }
}
