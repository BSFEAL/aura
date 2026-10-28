package systems.kaula.aura

import android.content.ContentValues
import android.provider.MediaStore
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real emulator renders; screenshots contain synthetic metadata only where explicitly labeled. */
@RunWith(AndroidJUnit4::class)
class AdaptiveDesignTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun shell(command: String): String {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            java.io.FileInputStream(it.fileDescriptor).bufferedReader().use { reader->return reader.readText() }
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        // Compose idle does not imply that SurfaceFlinger has presented the new window.
        // Commit a complete frame before capturing, especially after wm size/density changes.
        val committed=CountDownLatch(1)
        compose.runOnIdle {
            val decor=compose.activity.window.decorView
            decor.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            decor.invalidate()
        }
        assertTrue("A fresh hardware frame must commit before screenshot",committed.await(10,TimeUnit.SECONDS))
        android.os.SystemClock.sleep(250)
        // Scoped-storage-safe export of test PNGs; no storage permission or app code added.
        val bytes=ByteArrayOutputStream().use { output ->
            compose.onNodeWithTag("aura-app").assertIsDisplayed()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val bitmap=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output)) } finally { bitmap.recycle() }
            output.toByteArray()
        }
        val resolver=compose.activity.contentResolver
        val values=ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,"$name.png")
            put(MediaStore.Images.Media.MIME_TYPE,"image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/aura-ui-screenshots")
            put(MediaStore.Images.Media.IS_PENDING,1)
        }
        val uri=requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values))
        requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
        resolver.update(uri,ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING,0) },null,null)
        requireNotNull(resolver.openAssetFileDescriptor(uri,"r")).use { assertEquals(bytes.size.toLong(),it.length) }

    }
    @Test fun phoneScreensAndTabletNavigationRemainAccessible() {
        val originals=Store.rules(compose.activity)
        val originalEvents=Store.events(compose.activity)
        try {
            compose.onNodeWithTag("phone-navigation").assertIsDisplayed()
            compose.onNodeWithText("Start monitoring",useUnmergedTree=true).assertIsDisplayed()
            capture("phone-dashboard")
            compose.onAllNodesWithText("Rules").onFirst().performClick()
            compose.onNodeWithText("+ Create rule").assertIsDisplayed()
            capture("phone-rules")
            compose.onNodeWithText("+ Create rule").performClick()
            compose.onNodeWithText("Create a rule through chat").assertIsDisplayed()
            compose.onNodeWithText("RMS threshold").assertDoesNotExist()
            compose.onNodeWithText("Activation score (provisional)").assertDoesNotExist()
            compose.onNodeWithText("Barking example").assertDoesNotExist()
            compose.onNodeWithText("What should AURA monitor?").performScrollTo().assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,androidx.compose.ui.text.AnnotatedString("")))
            capture("phone-create-rule")
            compose.onAllNodesWithText("Live").onFirst().performClick()
            compose.onNodeWithText("Live Sound Intelligence").assertIsDisplayed()
            capture("phone-live-idle")
            compose.runOnIdle {
                Store.saveEvent(compose.activity,LocalEvent(id="design-fixture",ruleId="design-test",eventType="dog_bark",classId="/m/05tny_",label="Synthetic UI fixture · possible bark",startMs=1780000000000,endMs=1780000002000,durationMs=2000,peakScore=.8f,meanScore=.7f,modelVersion=ModelSpec.VERSION,provenance="simulated"))
            }
            compose.onAllNodesWithText("Events").onFirst().performClick()
            compose.onNodeWithText("Synthetic UI fixture · possible bark").performScrollTo().assertIsDisplayed()
            capture("phone-events-synthetic")
            compose.onNodeWithText("Synthetic UI fixture · possible bark").performClick()
            compose.onNodeWithText("Event Details").assertIsDisplayed()
            capture("phone-details-synthetic")
            compose.onAllNodesWithText("Settings").onFirst().performClick()
            capture("phone-settings")
            compose.onNodeWithText("Diagnostics",useUnmergedTree=true).performScrollTo().performClick()
            compose.onNodeWithText("No session measurements yet.").performScrollTo().assertIsDisplayed()
            capture("phone-diagnostics-idle")
            compose.onAllNodesWithText("Dashboard").onFirst().performClick()
            // A genuine emulator window resize, not a stretched phone bitmap.
            shell("wm size 1600x1000");shell("wm density 160")
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("tablet-navigation").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("tablet-navigation").assertIsDisplayed()
            compose.onNodeWithTag("phone-navigation").assertDoesNotExist()
            compose.onNodeWithText("Start monitoring",useUnmergedTree=true).assertIsDisplayed()
            capture("tablet-dashboard")
            compose.onAllNodesWithText("Settings").onFirst().performClick()
            compose.onNodeWithText("About",useUnmergedTree=true).performScrollTo().performClick()
            compose.onNodeWithText("by kaula.systems · v0.4.2 (8)").assertIsDisplayed()
            capture("tablet-about")
            assertFalse(MonitorService.active.value)
        } finally {
            shell("wm size reset");shell("wm density reset")
            compose.runOnIdle {
                Store.saveRules(compose.activity,originals)
                // Fixture cleanup uses the same atomic metadata store as restore.
                val prefs=compose.activity.getSharedPreferences("aura",0)
                prefs.edit().putString("events",RuleCodec.json.encodeToString(kotlinx.serialization.builtins.ListSerializer(LocalEvent.serializer()),originalEvents)).commit()
                Store.revision.intValue++
            }
        }
    }
}
