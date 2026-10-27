package systems.kaula.aura

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.*

sealed interface RulePlan {
    data class Ready(val draft: RuleDraft) : RulePlan
    data class Clarify(val message: String) : RulePlan
}
data class RuleDraft(val request: String, val condition: Condition, val policy: Policy, val assumptions: List<String>, val plannerModel: String) {
    fun asDisabledRule(id: String, deviceId: String) = LocalRule(id=id,enabled=false,source=Source(deviceId),condition=condition,policy=policy,text=request).validate()
}
fun interface RulePlanner {
    suspend fun propose(request: String): RulePlan
    suspend fun converse(request: String,conversation: List<RuleChatTurn>): RulePlan {
        require(conversation.isEmpty()) { "This planner does not support follow-up messages" }
        return propose(request)
    }
}
class BackendRulePlanner(private val base: String, private val token: String, private val deviceId: String, private val provider: String = "openai") : RulePlanner {
    override suspend fun propose(request: String) = converse(request,emptyList())
    override suspend fun converse(request: String,conversation: List<RuleChatTurn>) = withContext(Dispatchers.IO) {
        Api.previewRule(base,token,request,deviceId,consent=true,provider=provider,conversation=conversation)
    }
}
@Serializable private data class PlannerClass(@SerialName("class_id") val classId: String,val label: String)
@Serializable private data class PlannerEnvelope(
    @SerialName("schema_version") val schemaVersion: Int,
    val status: String, val mode: String,
    @SerialName("planner_version") val plannerVersion: String,
    @SerialName("planner_model") val plannerModel: String,
    val proposal: JsonObject?=null, val classes: List<PlannerClass> = emptyList(),
    val assumptions: List<String> = emptyList(), val message: String?=null)

/** Treat even authenticated-backend/LLM output as untrusted data. Never save from this decoder. */
object RulePlanCodec {
    const val MAX_BYTES=262144
    fun decode(text: String, request: String, deviceId: String, expectedProvider: String? = null): RulePlan {
        require(text.toByteArray(Charsets.UTF_8).size<=MAX_BYTES)
        var depth=0;var quoted=false;var escaped=false
        text.forEach { c ->
            if(quoted) { if(escaped) escaped=false else if(c=='\\') escaped=true else if(c=='"') quoted=false }
            else when(c) { '"' -> quoted=true; '{','[' -> { depth++;require(depth<=32) } }
            if(!quoted && (c=='}' || c==']')) { depth--;require(depth>=0) }
        }
        require(!quoted && depth==0)
        val envelope=RuleCodec.json.decodeFromString<PlannerEnvelope>(text)
        require(envelope.schemaVersion==2 && envelope.mode in setOf("openai","anthropic") && envelope.plannerVersion=="aura-${envelope.mode}-rule-planner-v1")
        require(expectedProvider==null || envelope.mode==expectedProvider)
        require(Regex("[A-Za-z0-9._:-]{1,80}").matches(envelope.plannerModel))
        if(envelope.status=="clarification") {
            require(envelope.proposal==null && envelope.classes.isEmpty() && envelope.assumptions.isEmpty())
            val message=requireNotNull(envelope.message)
            require(message.length in 1..240)
            return RulePlan.Clarify(message)
        }
        require(envelope.status=="ready" && envelope.message==null)
        val proposal=requireNotNull(envelope.proposal)
        require("id" !in proposal && "remote" !in proposal)
        require(proposal["schema_version"]?.jsonPrimitive?.intOrNull==2)
        val rule=RuleCodec.decode(JsonObject(proposal+mapOf("id" to JsonPrimitive("preview"))).toString())
        require(!rule.enabled && !rule.remote && rule.source.deviceId==deviceId && rule.text==request)
        require(rule.unavailableReason(true)==null)
        val expected=when(val c=rule.condition) {
            is Condition.Rms -> emptySet()
            is Condition.Audio -> c.classId?.let { setOf(it) } ?: requireNotNull(SoundCatalog.event(c.eventType)).classIds
        }
        require(envelope.classes.map { it.classId }.toSet()==expected && envelope.classes.size==expected.size)
        require(envelope.classes.all { SoundCatalog.classLabels[it.classId]==it.label })
        require(envelope.assumptions.size<=12 && envelope.assumptions.all { it.length<=240 })
        return RulePlan.Ready(RuleDraft(request,rule.condition,rule.policy,envelope.assumptions,envelope.plannerModel))
    }
}

@Serializable data class RuleChatTurn(val role: String,val text: String) {
    fun validate(): RuleChatTurn {
        require(role in setOf("user","assistant") && text.isNotBlank() && text.length<=if(role=="user") 240 else 512)
        return this
    }
}
fun validateConversation(turns: List<RuleChatTurn>) {
    require(turns.size<=12 && turns.size%2==0)
    turns.forEachIndexed { i,turn -> turn.validate();require(turn.role==if(i%2==0) "assistant" else "user") }
}
fun RuleDraft.chatSummary(): String = when(val c=condition) {
    is Condition.Audio -> "Proposed ${c.eventType} (${c.classId ?: "mapped classes"}): score ${c.minScore}, release ${c.releaseScore}, EMA ${c.emaAlpha}, persistence ${c.minDurationMs} ms, gap ${c.maxGapMs} ms; cooldown ${policy.cooldownMs} ms, notify ${policy.notify}. Awaiting your approval."
    is Condition.Rms -> "Proposed RMS above ${c.threshold}; cooldown ${policy.cooldownMs} ms, notify ${policy.notify}. Awaiting your approval."
}
