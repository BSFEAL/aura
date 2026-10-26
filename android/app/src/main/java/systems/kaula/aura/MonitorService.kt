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

class MonitorService : Service() {
    companion object {
        val active = mutableStateOf(false)
        val level = mutableDoubleStateOf(0.0)
        val error = mutableStateOf("")
        const val MONITOR = "aura_monitor"
        const val ALERTS = "aura_alerts"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var worker: Job? = null
    private fun visibleNotifications(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(MONITOR)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(MONITOR, "Active microphone monitoring", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(ALERTS, "Sound-level alerts", NotificationManager.IMPORTANCE_DEFAULT))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (intent?.action != "START" || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ||
            !visibleNotifications()) {
            error.value = "Microphone and visible notifications are required"; stopSelf(); return START_NOT_STICKY
        }
        if (worker != null) return START_NOT_STICKY
        val stop = PendingIntent.getService(this, 1, Intent(this, MonitorService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, MONITOR).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("AURA microphone active").setContentText("Local amplitude monitoring · tap Stop to end")
            .setOngoing(true).setContentIntent(open).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(1, notification)
        } catch (e: Exception) { error.value = "Cannot start monitoring: ${e.message}"; stopSelf(); return START_NOT_STICKY }
        active.value = true; error.value = ""
        worker = scope.launch(Dispatchers.IO) {
            var recorder: AudioRecord? = null
            val gate = ThresholdGate()
            try {
                val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0) { "Unsupported microphone format" }
                recorder = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 4096))
                check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
                recorder.startRecording()
                check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
                val samples = ShortArray(2048)
                while (isActive) {
                    check(visibleNotifications()) { "Active monitoring notifications were disabled" }
                    val count = recorder.read(samples, 0, samples.size)
                    check(count >= 0) { "Microphone read failed: $count" }
                    val rms = Signal.rms(samples, count)
                    withContext(Dispatchers.Main) { level.value = rms }
                    for (rule in Store.rules(this@MonitorService).filter { it.enabled }) {
                        if (gate.shouldNotify(rule.id, rms, rule.threshold, SystemClock.elapsedRealtime())) {
                            withContext(Dispatchers.Main) { Store.addEvent(this@MonitorService, rule, rms) }
                            if (Store.notifications(this@MonitorService) && getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
                                getSystemService(NotificationManager::class.java).notify(rule.id.hashCode(), Notification.Builder(this@MonitorService, ALERTS)
                                    .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("AURA sound-level threshold exceeded")
                                    .setContentText("Uncalibrated RMS %.3f".format(rms)).setContentIntent(open).setAutoCancel(true).build())
                            }
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { withContext(Dispatchers.Main) { error.value = "Monitoring stopped: ${e.message}" } }
            finally {
                try { recorder?.stop() } catch (_: Exception) { }
                recorder?.release()
                withContext(NonCancellable + Dispatchers.Main) { active.value = false; level.value = 0.0; stopSelf() }
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        worker?.cancel(); scope.cancel(); active.value = false; level.value = 0.0
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
