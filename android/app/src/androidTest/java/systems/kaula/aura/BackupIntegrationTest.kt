package systems.kaula.aura

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupIntegrationTest {
    @Test fun restoreCommitsMetadataWithoutCredentialsActivationOrAlertReplay() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=context.getSharedPreferences("aura",0)
        prefs.edit().clear().putString("localDeviceId","destination-phone").putString("base","https://private.invalid").putString("deviceId","server-device").putString("synced:existing","old-sync").commit()
        val rule=LocalRule(id="restore-rule",condition=Condition.Rms(.15,true),source=Source("original-phone"),remote=true)
        val history=LocalEvent(id="restore-event",ruleId=rule.id,deviceId="original-phone",startMs=1000,endMs=2000,durationMs=1000,rms=.2,notificationStatus="posted")
        val incoming=LocalBackup(createdAtMs=3000,deviceId="original-phone",rules=listOf(rule),events=listOf(history))
        try {
            val manager=context.getSystemService(android.app.NotificationManager::class.java)
            val before=manager.activeNotifications.map { it.id }.toSet()
            val plan=Store.restore(context,incoming)
            assertEquals(1,plan.addedRules);assertEquals(1,plan.addedEvents)
            assertFalse(Store.rules(context).single().enabled)
            assertEquals("destination-phone",Store.rules(context).single().source.deviceId)
            assertEquals("original-phone",Store.events(context).single().deviceId)
            assertTrue(Store.events(context).single().restoredFromBackup)
            assertFalse(MonitorService.active.value)
            assertEquals(before,manager.activeNotifications.map { it.id }.toSet())
            assertEquals("https://private.invalid",Store.setting(context,"base"))
            assertEquals("server-device",Store.setting(context,"deviceId"))
            assertEquals("old-sync",Store.setting(context,"synced:existing"))
            assertEquals(0,Store.restore(context,incoming).addedEvents)
            val exported=String(BackupCodec.encode(Store.backup(context)))
            assertFalse(exported.contains("private.invalid"));assertFalse(exported.contains("old-sync"));assertFalse(exported.contains("server-device"))
            val oldRules=prefs.getString("rules",null);val oldEvents=prefs.getString("events",null)
            try { Store.restore(context,incoming.copy(events=listOf(history.copy(label="conflicting label"))));fail("Must reject conflict") }
            catch(_: IllegalArgumentException) {}
            assertEquals(oldRules,prefs.getString("rules",null));assertEquals(oldEvents,prefs.getString("events",null))
            prefs.edit().putString("events","corrupt").commit()
            try { Store.backup(context);fail("Must not export incomplete data") } catch(_: IllegalArgumentException) {}
        } finally { prefs.edit().clear().commit();Store.revision.intValue++ }
    }
}
