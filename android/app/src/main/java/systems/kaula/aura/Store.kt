package systems.kaula.aura

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// App-private storage; no raw audio. Bounded event history. Synchronized with the worker thread.
data class LocalRule(val id: String, val text: String, val threshold: Double, val enabled: Boolean = true, val remote: Boolean = false)
data class LocalEvent(val id: String, val ruleId: String, val rms: Double, val time: Long)
object Store {
    val revision = mutableIntStateOf(0)
    private fun prefs(context: Context) = context.getSharedPreferences("aura", Context.MODE_PRIVATE)
    @Synchronized fun rules(context: Context): List<LocalRule> {
        val array = JSONArray(prefs(context).getString("rules", "[]"))
        return (0 until array.length()).map { val r = array.getJSONObject(it); LocalRule(r.getString("id"), r.getString("text"), r.getDouble("threshold"), r.getBoolean("enabled"), r.optBoolean("remote")) }
    }
    @Synchronized fun saveRules(context: Context, rules: List<LocalRule>) {
        val array = JSONArray()
        rules.forEach { array.put(JSONObject().put("id", it.id).put("text", it.text).put("threshold", it.threshold).put("enabled", it.enabled).put("remote", it.remote)) }
        prefs(context).edit().putString("rules", array.toString()).apply()
        revision.value++
    }
    @Synchronized fun events(context: Context): List<LocalEvent> {
        val array = JSONArray(prefs(context).getString("events", "[]"))
        return (0 until array.length()).map { val e = array.getJSONObject(it); LocalEvent(e.getString("id"), e.getString("ruleId"), e.getDouble("rms"), e.getLong("time")) }
    }
    @Synchronized fun addEvent(context: Context, rule: LocalRule, rms: Double) {
        val events = listOf(LocalEvent(UUID.randomUUID().toString(), rule.id, rms, System.currentTimeMillis())) + events(context)
        val array = JSONArray()
        events.take(200).forEach { array.put(JSONObject().put("id", it.id).put("ruleId", it.ruleId).put("rms", it.rms).put("time", it.time)) }
        prefs(context).edit().putString("events", array.toString()).apply()
        revision.value++
    }
    fun setting(context: Context, key: String) = prefs(context).getString(key, "") ?: ""
    fun setting(context: Context, key: String, value: String) { prefs(context).edit().putString(key, value).apply() }
    fun notifications(context: Context) = prefs(context).getBoolean("alerts", true)
    fun notifications(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean("alerts", enabled).apply() }
}
