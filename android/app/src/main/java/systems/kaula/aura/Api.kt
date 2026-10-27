package systems.kaula.aura

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

object Api {
    fun previewRule(base: String, token: String, text: String, deviceId: String, consent: Boolean, provider: String = "openai"): RulePlan {
        require(provider in setOf("openai","anthropic"))
        require(consent) { "Explicit consent to send rule text to the selected provider is required" }
        require(text.isNotBlank() && text.length<=240 && token.length>=24) { "Enter rule text and the AURA backend token (at least 24 characters)" }
        val uri=URI(base.trim().trimEnd('/'))
        require(uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.userInfo==null && uri.query==null && uri.fragment==null) { "Use a trusted private HTTPS backend URL; localhost on your phone is not your computer" }
        val connection=URL("$uri/api/v2/rules/preview").openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout=8000;connection.readTimeout=28000;connection.instanceFollowRedirects=false
            connection.requestMethod="POST";connection.doOutput=true
            connection.setRequestProperty("Authorization","Bearer $token")
            connection.setRequestProperty("Content-Type","application/json")
            val body=JSONObject().put("schema_version",2).put("text",text).put("device_id",deviceId).put("provider",provider).put("consent_to_$provider",true)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code=connection.responseCode
            check(code==200) { when(code) {
                401,403 -> "AURA backend token rejected"
                429 -> "Planner busy; retry later. No rule saved"
                503 -> "Selected provider planner not configured or unavailable on the backend. No rule saved"
                502 -> "Selected provider failed or returned an invalid proposal. No rule saved"
                else -> "Rule preview failed (HTTP $code). No rule saved"
            } }
            val output=java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val block=ByteArray(8192)
                while(true) { val count=input.read(block);if(count==-1) break;require(count>0 && output.size()+count<=RulePlanCodec.MAX_BYTES);output.write(block,0,count) }
            }
            val decoded=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString()
            return RulePlanCodec.decode(decoded,text,deviceId,provider)
        } finally { connection.disconnect() }
    }
    // Explicit sync only. No automatic background networking, no audio uploads.
    fun sync(context: Context, base: String, token: String, deviceId: String): String {
        val uri = URI(base.trim().trimEnd('/'))
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) { "Use a trusted HTTPS backend URL" }
        require(token.length >= 24) { "API token must be at least 24 characters" }
        val origin = uri.toString()
        fun request(path: String, body: JSONObject? = null): String {
            val connection = URL("$origin/api/$path").openConnection() as HttpsURLConnection
            try {
                connection.connectTimeout = 8000; connection.readTimeout = 8000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    connection.requestMethod = "POST"; connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                check(connection.responseCode in 200..299) { "API request failed (${connection.responseCode})" }
                return connection.inputStream.bufferedReader().use { it.readText() }
            } finally { connection.disconnect() }
        }
        val id = if (deviceId.isBlank()) JSONObject(request("devices", JSONObject().put("name", "Android phone").put("kind", "phone"))).getString("id") else deviceId
        Store.setting(context, "deviceId", id)
        val devices = JSONArray(request("devices"))
        require((0 until devices.length()).any { devices.getJSONObject(it).getString("id") == id }) { "Device ID not registered. Clear it to register this phone." }
        // Upload each local event once per backend/device; retries can duplicate after a network interruption.
        val syncKey = "synced:$origin:$id"
        val synced = Store.setting(context, syncKey).split(',').filter { it.isNotBlank() }.toMutableSet()
        for (event in Store.events(context).reversed()) {
            if(event.id in synced || event.endMs==null) continue
            val payload=JSONObject().put("schema_version",2).put("device_id",id).put("client_event_id",event.id)
                .put("event_type",event.eventType).put("label",event.label).put("rms",event.rms)
                .put("provenance",event.provenance).put("start_at",java.time.Instant.ofEpochMilli(event.startMs).toString())
                .put("end_at",java.time.Instant.ofEpochMilli(event.endMs).toString()).put("duration_ms",event.durationMs)
                .put("notification_status",event.notificationStatus).put("state",event.state)
            if(event.modelVersion!=null) {
                payload.put("class_id",event.classId).put("peak_score",event.peakScore).put("mean_score",event.meanScore)
                    .put("model_version",event.modelVersion).put("mapping_version",event.mappingVersion).put("threshold_version",event.thresholdVersion)
            }
            // Omit rule_id for historical reports; the original local rule ID remains on the phone.
            request("v2/events",payload)
            synced.add(event.id)
            Store.setting(context,syncKey,synced.toList().takeLast(200).joinToString(","))
        }
        val array=JSONArray(request("v2/rules"))
        val remote=(0 until array.length()).map { array.getJSONObject(it) }.filter { it.getJSONObject("source").getString("device_id")==id }
            .map { RuleCodec.decode(it.toString()).copy(remote=true) }
        Store.replaceRemoteRules(context,remote)
        return "Synced ${remote.size} API rules and completed local episodes. Device: $id"
    }
}
