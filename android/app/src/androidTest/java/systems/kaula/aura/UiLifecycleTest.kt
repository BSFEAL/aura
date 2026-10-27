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
    private fun dismissKeyboard() {
        compose.runOnIdle {
            val activity=compose.activity
            activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken,0)
            activity.currentFocus?.clearFocus()
        }
        compose.waitUntil(5000) {
            compose.activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())!=true
        }
    }
    @Test fun backupControlsAreVisibleWithoutStartingMonitoring() {
        compose.onAllNodesWithText("Settings").onFirst().performClick()
        compose.onNodeWithText("Backup",useUnmergedTree=true).performScrollTo().performClick()
        compose.onNodeWithText("Export metadata backup").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Restore from backup").performScrollTo().assertIsEnabled()
        assertFalse(MonitorService.active.value)
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
        compose.waitUntil(20000) { !MonitorService.finishing.value }
        assertFalse("Stopped service notification must be removed",manager.activeNotifications.any { it.id==1 })
    }
    @Test fun selectingAnotherProviderResetsConsent() {
        compose.onAllNodesWithText("Rules").onFirst().performClick()
        compose.onNodeWithText("+ Create rule").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Consent to OpenAI rule text sharing").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Consent to OpenAI rule text sharing").assertIsOn()
        compose.onNodeWithText("Anthropic",useUnmergedTree=true).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Consent to Anthropic rule text sharing").performScrollTo().assertIsOff()
        compose.onNodeWithText("Send to Anthropic").performScrollTo().assertIsNotEnabled()
        assertFalse(MonitorService.active.value)
    }
    @Test fun naturalPreviewRequiresConsentAndExplicitDisabledSave() {
        val originals=Store.rules(compose.activity)
        val originalFactory=MainActivity.rulePlannerFactory
        val calls=java.util.concurrent.atomic.AtomicInteger()
        try {
            MainActivity.rulePlannerFactory={_,_,_,_-> object: RulePlanner {
                override suspend fun propose(request: String)=converse(request,emptyList())
                override suspend fun converse(request: String,conversation: List<RuleChatTurn>): RulePlan {
                    validateConversation(conversation);calls.incrementAndGet()
                    return RulePlan.Ready(RuleDraft(request,Condition.Audio("dog_bark",minDurationMs=2000),Policy(),listOf("Synthetic UI proposal only"),"mock-planner-test"))
                }
            } }
            compose.onAllNodesWithText("Rules").onFirst().performClick()
        compose.onNodeWithText("+ Create rule").performScrollTo().performClick()
            compose.onNodeWithText("Private HTTPS backend URL").performScrollTo().performTextReplacement("https://private.invalid")
            compose.onNodeWithText("AURA API token (not a provider key)").performScrollTo().performTextReplacement("test-aura-token-at-least-24")
            compose.onNodeWithText("What should AURA monitor?").performScrollTo().performTextReplacement("Notify me when my dog barks for 2 seconds")
            dismissKeyboard()
            compose.onNodeWithText("Send to OpenAI").performScrollTo().assertIsNotEnabled()
            assertEquals(0,calls.get())
            compose.onNodeWithContentDescription("Consent to OpenAI rule text sharing").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Consent to OpenAI rule text sharing").assertIsOn()
            compose.onNodeWithText("Send to OpenAI").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(10000) { calls.get()==1 }
            compose.onNodeWithText("Review proposed rule").performScrollTo().assertIsDisplayed()
            assertEquals(originals,Store.rules(compose.activity));assertFalse(MonitorService.active.value)
            compose.onNodeWithText("What should AURA monitor?").performScrollTo().performTextReplacement("Notify me when my dog barks for 2 seconds")
            dismissKeyboard()
            compose.onNodeWithText("Save disabled rule").assertDoesNotExist()
            compose.onNodeWithText("Send to OpenAI").performScrollTo().performClick()
            compose.waitUntil(10000) { calls.get()==2 }
            compose.onNodeWithText("Save disabled rule").performScrollTo().performClick()
            compose.runOnIdle {
                val added=Store.rules(compose.activity).filter { it.id !in originals.map { r->r.id } }.single()
                assertFalse(added.enabled);assertEquals("dog_bark",(added.condition as Condition.Audio).eventType)
                assertEquals("Notify me when my dog barks for 2 seconds",added.text)
                assertFalse(MonitorService.active.value)
            }
        } finally {
            MainActivity.rulePlannerFactory=originalFactory
            compose.runOnIdle { Store.saveRules(compose.activity,originals) }
        }
    }
    @Test fun chatClarificationAndRefinementNeedExplicitSave() {
        val originalFactory=MainActivity.rulePlannerFactory
        val originals=Store.rules(compose.activity)
        val calls=java.util.concurrent.atomic.AtomicInteger()
        try {
            MainActivity.rulePlannerFactory={_,_,_,_-> object: RulePlanner {
                override suspend fun propose(request: String): RulePlan = RulePlan.Clarify("Which sound should AURA monitor?")
                override suspend fun converse(request: String,conversation: List<RuleChatTurn>): RulePlan {
                    validateConversation(conversation);calls.incrementAndGet()
                    if(conversation.isEmpty()) return propose(request)
                    assertEquals("Notify me about my pet",request)
                    assertEquals("Dog barking for 2 seconds, 45 seconds cooldown",conversation.last().text)
                    return RulePlan.Ready(RuleDraft(request,Condition.Audio("dog_bark",minDurationMs=2000),Policy(45000),listOf("Synthetic UI proposal"),"test-planner"))
                }
            } }
            compose.onAllNodesWithText("Rules").onFirst().performClick()
            compose.onNodeWithText("+ Create rule").performScrollTo().performClick()
            compose.onNodeWithText("RMS threshold").assertDoesNotExist()
            compose.onNodeWithText("Barking example").assertDoesNotExist()
            compose.onNodeWithText("Private HTTPS backend URL").performScrollTo().performTextReplacement("https://private.invalid")
            compose.onNodeWithText("AURA API token (not a provider key)").performScrollTo().performTextReplacement("test-aura-token-at-least-24")
            compose.onNodeWithText("What should AURA monitor?").performScrollTo().performTextReplacement("Notify me about my pet")
            dismissKeyboard()
            compose.onNodeWithContentDescription("Consent to OpenAI rule text sharing").performScrollTo().performClick()
            compose.onNodeWithText("Send to OpenAI").performScrollTo().performClick()
            compose.waitUntil(10000) { calls.get()==1 }
            compose.onNodeWithText("Save disabled rule").assertDoesNotExist()
            compose.onNodeWithText("What should AURA monitor?").performScrollTo().performTextReplacement("Dog barking for 2 seconds, 45 seconds cooldown")
            dismissKeyboard()
            compose.onNodeWithText("Send to OpenAI").performScrollTo().performClick()
            compose.waitUntil(10000) { calls.get()==2 }
            compose.onNodeWithText("Review proposed rule").performScrollTo().assertIsDisplayed()
            assertEquals(originals,Store.rules(compose.activity))
            compose.onNodeWithText("Save disabled rule").performScrollTo().performClick()
            compose.runOnIdle {
                val added=Store.rules(compose.activity).single { it.id !in originals.map { r->r.id } }
                assertFalse(added.enabled);assertEquals(45000L,added.policy.cooldownMs)
                assertEquals(2000L,(added.condition as Condition.Audio).minDurationMs)
                assertFalse(MonitorService.active.value)
            }
        } finally {
            MainActivity.rulePlannerFactory=originalFactory
            compose.runOnIdle { Store.saveRules(compose.activity,originals) }
        }
    }
    @Test fun ruleEditingAndEventRendering() {
        compose.onAllNodesWithText("Rules").onFirst().performClick()
        compose.runOnIdle {
            Store.saveRules(compose.activity,Store.rules(compose.activity)+LocalRule(id="existing-rms-ui",condition=Condition.Rms(.15,true)))
        }
        compose.onAllNodesWithText("Edit").onFirst().performClick()
        compose.onNodeWithText("Save changes").performScrollTo().performClick()
        compose.runOnIdle {
            Store.saveRules(compose.activity,Store.rules(compose.activity)+LocalRule(id="pinned-profile-test",enabled=false,condition=Condition.Audio("rain",modelVersion="unsupported-pinned-model"),policy=Policy(45000,false),text="Retain this rule note"))
        }
        compose.onAllNodesWithText("Edit").onLast().performScrollTo().performClick()
        compose.onNodeWithText("Persistence ms (0–60000)").performScrollTo().performTextReplacement("500")
        compose.runOnIdle {
            assertEquals(3000L,(Store.rules(compose.activity).first { it.id=="pinned-profile-test" }.condition as Condition.Audio).minDurationMs)
        }
        compose.onNodeWithText("Save changes").performScrollTo().performClick()
        compose.runOnIdle {
            val pinned=Store.rules(compose.activity).first { it.id=="pinned-profile-test" }
            assertEquals("unsupported-pinned-model",(pinned.condition as Condition.Audio).modelVersion)
            assertEquals(500L,(pinned.condition as Condition.Audio).minDurationMs)
            assertEquals(45000L,pinned.policy.cooldownMs);assertFalse(pinned.policy.notify);assertFalse(pinned.enabled)
            assertEquals("Retain this rule note",pinned.text)
            assertNotNull(pinned.unavailableReason(true))
        }
        compose.runOnIdle {
            Store.saveEvent(compose.activity,LocalEvent(id="ui-test-event",ruleId="test",eventType="dog_bark",classId="/m/05tny_",label="Possible Dog barking",startMs=1780000000000,peakScore=.8f,meanScore=.7f,modelVersion=ModelSpec.VERSION,provenance="model_detected"))
        }
        compose.onAllNodesWithText("Events").onFirst().performClick()
        compose.onNodeWithText("Possible Dog barking").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("model_detected · ended").assertIsDisplayed()
        compose.onNodeWithText("Possible Dog barking").performClick()
        compose.onNodeWithText("Event Details").assertIsDisplayed()
        compose.onNodeWithText("Metadata only.",substring=true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("← Event history").performScrollTo().performClick()
    }
    @Test fun liveFeedbackExplainsMissingRulesPersistenceAndCooldown() {
        // Synthetic presentation fixture only; the separate model test uses the actual interpreter.
        val originals=Store.rules(compose.activity)
        val rule=LocalRule(id="feedback-ui",condition=Condition.Audio("dog_bark",minDurationMs=1000,maxGapMs=500,emaAlpha=1f),policy=Policy(3000))
        val detector=TemporalDetector(rule)
        detector.update(.9f,0,10000)
        try {
            compose.runOnIdle {
                Store.saveRules(compose.activity,listOf(rule))
                MonitorService.active.value=true;MonitorService.status.value="Active";MonitorService.modelLoaded.value=true
                MonitorService.top.value=listOf(ClassScore("/m/0bt9lr","Dog",.9f),ClassScore("/m/02jz0l","Water tap, faucet",.85f))
                MonitorService.live.value=mapOf(rule.id to detector.update(.9f,500,10500))
            }
            compose.onAllNodesWithText("Live").onFirst().performClick()
            compose.onNodeWithText("Dog is a broad class.",substring=true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("No matching rule.",substring=true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Candidate 500 / 1000 ms; waiting for persistence").performScrollTo().assertIsDisplayed()
            compose.runOnIdle {
                detector.update(.9f,1000,11000)
                MonitorService.live.value=mapOf(rule.id to detector.update(0f,1600,11600))
            }
            compose.onNodeWithText("Cooldown: 2400 ms remaining",substring=true).performScrollTo().assertIsDisplayed()
        } finally {
            compose.runOnIdle {
                MonitorService.active.value=false;MonitorService.status.value="Stopped";MonitorService.modelLoaded.value=false
                MonitorService.top.value=emptyList();MonitorService.live.value=emptyMap()
                Store.saveRules(compose.activity,originals)
            }
        }
    }
}
