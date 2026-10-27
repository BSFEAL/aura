package systems.kaula.aura

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableDoubleStateOf
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicLong

class MonitorService : Service() {
    companion object {
        val active=mutableStateOf(false)
        val finishing=mutableStateOf(false)
        val status=mutableStateOf("Stopped")
        val level=mutableDoubleStateOf(0.0)
        val error=mutableStateOf("")
        val top=mutableStateOf<List<ClassScore>>(emptyList())
        val live=mutableStateOf<Map<String,DetectorUpdate>>(emptyMap())
        val stats=mutableStateOf("")
        val modelLoaded=mutableStateOf(false)
        const val MONITOR="aura_monitor"
        const val ALERTS="aura_alerts"
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private var worker: Job?=null
    private var resourcesReleased=false
    private var recorder: AudioRecord?=null
    private var session: SoundSession?=null
    private data class CapturedWindow(val samples: FloatArray,val mono: Long,val wall: Long,val rms: Double)
    private var generation=0L
    private var foregroundErrors=0L
    private val dropped=AtomicLong()
    private fun visibleNotifications(): Boolean {
        val manager=getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(MONITOR)?.importance!=NotificationManager.IMPORTANCE_NONE
    }
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(NotificationChannel(MONITOR,"Active microphone monitoring",NotificationManager.IMPORTANCE_LOW))
            createNotificationChannel(NotificationChannel(ALERTS,"Experimental sound-event alerts",NotificationManager.IMPORTANCE_DEFAULT))
        }
    }
    private fun stopCapture() { try { recorder?.stop() } catch(_: Exception) {} }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action=="STOP") { stopCapture(); stopSelf(); return START_NOT_STICKY }
        if(intent?.action!="START" || checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) || !visibleNotifications()) {
            status.value="Error"; error.value="Microphone and visible notifications are required"; stopSelf(); return START_NOT_STICKY
        }
        if(worker!=null || finishing.value) return START_NOT_STICKY
        val stop=PendingIntent.getService(this,1,Intent(this,MonitorService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE)
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val notification=Notification.Builder(this,MONITOR).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("AURA microphone active").setContentText("On-device sound intelligence · Stop to end")
            .setOngoing(true).setContentIntent(open).addAction(Notification.Action.Builder(null,"Stop",stop).build()).build()
        try {
            if(Build.VERSION.SDK_INT>=30) startForeground(1,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(1,notification)
        } catch(e: Exception) { foregroundErrors++; status.value="Error"; error.value="Cannot start: ${e.message}"; stopSelf(); return START_NOT_STICKY }
        active.value=true; status.value="Starting"; error.value=""; top.value=emptyList(); live.value=emptyMap(); modelLoaded.value=false; dropped.set(0)
        val run=++generation
        worker=scope.launch(Dispatchers.IO) {
            var engine: SoundSession?=null
            val queue=Channel<CapturedWindow>(1)
            var inference: Job?=null
            var captureError: String?=null
            val started=SystemClock.elapsedRealtime()
            try {
                val initStart=SystemClock.elapsedRealtime()
                val classifier=try { YamnetClassifier(this@MonitorService) } catch(e: Exception) {
                    withContext(Dispatchers.Main) { error.value="RMS-only degraded mode: ${e.message}" }; null
                } catch(e: LinkageError) { withContext(Dispatchers.Main) { error.value="RMS-only degraded mode: native runtime unavailable" }; null }
                val initMs=SystemClock.elapsedRealtime()-initStart
                withContext(Dispatchers.Main) { stats.value="16,000 Hz mono PCM16 · model init $initMs ms · ${if(classifier==null) "unavailable, RMS-only" else "loaded"} · 0 processed windows" }
                val decision=SoundSession(classifier); engine=decision; session=decision
                withContext(Dispatchers.Main) { modelLoaded.value=classifier!=null; status.value=if(classifier!=null) "Active" else "Degraded" }
                val minimum=AudioRecord.getMinBufferSize(ModelSpec.SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
                check(minimum>0) { "Unsupported microphone format" }
                val audio=AudioRecord(MediaRecorder.AudioSource.MIC,ModelSpec.SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(minimum,8192))
                recorder=audio
                check(audio.state==AudioRecord.STATE_INITIALIZED && audio.sampleRate==ModelSpec.SAMPLE_RATE) { "Microphone unavailable or wrong sample rate" }
                audio.startRecording(); check(audio.recordingState==AudioRecord.RECORDSTATE_RECORDING)
                // One inference consumer, one waiting window, no overlapping native calls. New windows are dropped when full.
                inference=launch(Dispatchers.Default) {
                    for(window in queue) {
                        val result=decision.classify(window.samples)
                        val mono=window.mono; val wall=window.wall
                        withContext(Dispatchers.Main) {
                            val rules=Store.rules(this@MonitorService)
                            decision.prune(rules,mono,audioOnly=result==null).forEach { persist(it,rules,false,open) }
                            if(result==null) {
                                status.value="Degraded"; modelLoaded.value=false; top.value=emptyList(); error.value="RMS-only degraded mode: ${decision.unavailable}"
                                live.value=live.value.filterKeys { id -> rules.any { it.id==id && it.condition is Condition.Rms } }
                            } else {
                                top.value=result.scores.sortedByDescending { it.score }.take(6)
                                val updates=live.value.toMutableMap()
                                rules.filter { it.enabled && it.condition is Condition.Audio && it.unavailableReason(true)==null }.forEach { rule ->
                                    val c=rule.condition as Condition.Audio
                                    val update=decision.evaluate(rule,SoundCatalog.score(c.eventType,result.scores,c.classId),mono,wall,window.rms,result.scores.filter { it.classId in requireNotNull(SoundCatalog.event(c.eventType)).classIds && (c.classId==null || it.classId==c.classId) }.maxByOrNull { it.score }?.classId)
                                    updates[rule.id]=update
                                    update.ended?.let { persist(it,rules,false,open) }; update.started?.let { persist(it,rules,true,open) }
                                }
                                live.value=updates.filterKeys { id->rules.any { it.id==id && it.enabled } }
                            }
                            val elapsed=(mono-started).coerceAtLeast(1)
                            val runtime=Runtime.getRuntime()
                            stats.value="16,000 Hz mono PCM16 · model ${if(modelLoaded.value) "loaded" else "unavailable"}\nInit ${initMs} ms · avg %.1f ms · p95 %d ms (last 128)\n%d windows · %.2f windows/s · %d failures · %d dropped\nJava heap %.1f MiB · process PSS %d KiB · foreground errors %d".format(
                                decision.diagnostics.averageMs,decision.diagnostics.p95Ms,decision.diagnostics.count,decision.diagnostics.count*1000.0/elapsed,decision.failures,dropped.get(),
                                (runtime.totalMemory()-runtime.freeMemory())/1048576.0,android.os.Debug.getPss(),foregroundErrors)
                        }
                    }
                }
                val buffer=AudioWindowBuffer(Store.cadence(this@MonitorService))
                val samples=ShortArray(2048)
                var zeroReads=0
                while(isActive) {
                    check(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED && visibleNotifications()) { "Microphone permission or active notifications disabled" }
                    if(Build.VERSION.SDK_INT>=29) check(audio.activeRecordingConfiguration?.isClientSilenced!=true) { "Microphone is silenced by Android or another app" }
                    val count=audio.read(samples,0,samples.size)
                    check(count>=0) { "Microphone read failed: $count" }
                    if(count==0) { zeroReads++; check(zeroReads<10) { "Microphone returned no samples" }; delay(20); continue }
                    zeroReads=0
                    val rms=Signal.rms(samples,count)
                    val mono=SystemClock.elapsedRealtime(); val wall=System.currentTimeMillis()
                    withContext(Dispatchers.Main) {
                        level.doubleValue=rms
                        val rules=Store.rules(this@MonitorService)
                        decision.prune(rules,mono).forEach { persist(it,rules,false,open) }
                        val updates=live.value.toMutableMap()
                        rules.filter { it.enabled && it.condition is Condition.Rms }.forEach { rule ->
                            val update=decision.evaluate(rule,rms.toFloat(),mono,wall,rms); updates[rule.id]=update
                            update.ended?.let { persist(it,rules,false,open) }; update.started?.let { persist(it,rules,true,open) }
                        }
                        live.value=updates.filterKeys { id->rules.any { it.id==id && it.enabled } }
                    }
                    if(classifier!=null && decision.unavailable==null) for(window in buffer.append(samples,count)) {
                        if(!queue.trySend(CapturedWindow(window,mono,wall,rms)).isSuccess) dropped.incrementAndGet()
                    }
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(currentCoroutineContext().isActive) captureError=e.message }
            finally {
                queue.close(); inference?.cancel()
                withContext(NonCancellable) {
                    inference?.join(); stopCapture(); recorder?.release(); recorder=null
                    withContext(Dispatchers.Main) {
                        engine?.finish(SystemClock.elapsedRealtime())?.forEach { persist(it,Store.rules(this@MonitorService),false,open) }
                        engine?.close(); session=null; resourcesReleased=true; finishing.value=false
                        if(generation==run) {
                            active.value=false; level.doubleValue=0.0; modelLoaded.value=false; top.value=emptyList(); live.value=emptyMap()
                            status.value=if(captureError!=null) "Error" else "Stopped"
                            if(captureError!=null) error.value="Monitoring stopped: $captureError"
                        }
                        stopSelf(startId)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }
    private fun persist(episode: Episode,rules: List<LocalRule>,notify: Boolean,open: PendingIntent) {
        val rule=rules.find { it.id==episode.ruleId }
        val old=Store.events(this).find { it.id==episode.id }
        var notificationStatus=old?.notificationStatus ?: "disabled"
        if(notify && rule?.policy?.notify==true && Store.notifications(this)) {
            val manager=getSystemService(NotificationManager::class.java)
            notificationStatus = if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(ALERTS)?.importance == NotificationManager.IMPORTANCE_NONE) {
                "permission_denied"
            } else {
                try {
                manager.notify(episode.id.hashCode(),Notification.Builder(this,ALERTS).setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(if(episode.modelVersion==null) "AURA RMS threshold exceeded" else "AURA: possible ${rule.label}")
                    .setContentText(if(episode.modelVersion==null) "Uncalibrated amplitude" else "Experimental model score; not a confirmed real-world event")
                    .setContentIntent(open).setAutoCancel(true).build()); "posted"
                } catch (_: SecurityException) {
                    "permission_denied"
                }
            }
        }
        Store.saveEvent(this,episode.toLocalEvent(rule,Store.localDeviceId(this),notificationStatus,old?.label))
    }
    override fun onDestroy() {
        finishing.value=worker!=null && !resourcesReleased
        generation++; stopCapture(); worker?.cancel(); scope.cancel(); active.value=false; level.doubleValue=0.0; modelLoaded.value=false; top.value=emptyList(); live.value=emptyMap()
        if(status.value!="Error") status.value="Stopped"
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
    }
}
