package systems.kaula.aura

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

object AuraColors {
    val Background=Color(0xFF030B13)
    val Surface=Color(0xFF0A192A)
    val Border=Color(0xFF19354D)
    val Cyan=Color(0xFF00C5FA)
    val Blue=Color(0xFF2688FF)
    val Indigo=Color(0xFF8B6EFF)
    val Muted=Color(0xFFAFBED6)
}
@Composable fun AuraTheme(content: @Composable ()->Unit) {
    MaterialTheme(colorScheme=darkColorScheme(primary=AuraColors.Cyan,onPrimary=Color(0xFF001522),secondary=AuraColors.Indigo,
        background=AuraColors.Background,onBackground=Color(0xFFEAF1FF),surface=AuraColors.Surface,onSurface=Color(0xFFEAF1FF),
        surfaceVariant=Color(0xFF10263C),onSurfaceVariant=AuraColors.Muted,outline=AuraColors.Border,outlineVariant=AuraColors.Border,
        primaryContainer=Color(0xFF073D5A),onPrimaryContainer=Color(0xFFEAF1FF),secondaryContainer=Color(0xFF252344),onSecondaryContainer=Color(0xFFEAF1FF),
        surfaceContainer=AuraColors.Surface,surfaceContainerHigh=AuraColors.Surface,surfaceContainerHighest=AuraColors.Surface),
        shapes=Shapes(small=RoundedCornerShape(10.dp),medium=RoundedCornerShape(16.dp),large=RoundedCornerShape(22.dp)),content=content)
}
/** Small vector marks drawn in code; no bitmap, icon font or extra icon dependency. */
@Composable fun AuraIcon(kind: String, modifier: Modifier=Modifier.size(24.dp), tint: Color=AuraColors.Cyan) {
    Canvas(modifier) {
        val w=size.width; val h=size.height
        fun line(x: Float,y: Float,a: Float,b: Float)=drawLine(tint,androidx.compose.ui.geometry.Offset(w*x,h*y),androidx.compose.ui.geometry.Offset(w*a,h*b),w*.065f,StrokeCap.Round)
        when(kind) {
            "Dashboard" -> { val p=Path().apply { moveTo(w*.15f,h*.45f);lineTo(w*.5f,h*.15f);lineTo(w*.85f,h*.45f);lineTo(w*.85f,h*.85f);lineTo(w*.6f,h*.85f);lineTo(w*.6f,h*.6f);lineTo(w*.4f,h*.6f);lineTo(w*.4f,h*.85f);lineTo(w*.15f,h*.85f);close() };drawPath(p,tint,style=Stroke(w*.06f)) }
            "Live","mic" -> { drawRoundRect(tint,androidx.compose.ui.geometry.Offset(w*.36f,h*.12f),androidx.compose.ui.geometry.Size(w*.28f,h*.5f),androidx.compose.ui.geometry.CornerRadius(w*.14f));drawArc(tint,0f,180f,false,androidx.compose.ui.geometry.Offset(w*.2f,h*.25f),androidx.compose.ui.geometry.Size(w*.6f,h*.5f),style=Stroke(w*.065f));line(.5f,.75f,.5f,.9f);line(.35f,.9f,.65f,.9f) }
            "Events" -> { drawRoundRect(tint,androidx.compose.ui.geometry.Offset(w*.23f,h*.15f),androidx.compose.ui.geometry.Size(w*.54f,h*.7f),androidx.compose.ui.geometry.CornerRadius(w*.05f),style=Stroke(w*.06f));line(.36f,.36f,.64f,.36f);line(.36f,.52f,.64f,.52f);line(.36f,.68f,.55f,.68f) }
            "dog_bark" -> { drawOval(tint,androidx.compose.ui.geometry.Offset(w*.3f,h*.48f),androidx.compose.ui.geometry.Size(w*.4f,h*.36f));listOf(.18f to .36f,.4f to .23f,.64f to .23f,.84f to .39f).forEach { (x,y)->drawCircle(tint,w*.105f,androidx.compose.ui.geometry.Offset(w*x,h*y)) } }
            "running_water","rain" -> { val p=Path().apply { moveTo(w*.5f,h*.1f);cubicTo(w*.4f,h*.35f,w*.15f,h*.55f,w*.25f,h*.76f);cubicTo(w*.4f,h*1f,w*.8f,h*.9f,w*.77f,h*.65f);cubicTo(w*.74f,h*.48f,w*.6f,h*.3f,w*.5f,h*.1f) };drawPath(p,tint) }
            "alarm_siren","doorbell" -> { drawArc(tint,180f,180f,true,androidx.compose.ui.geometry.Offset(w*.22f,h*.2f),androidx.compose.ui.geometry.Size(w*.56f,h*.85f));line(.2f,.7f,.8f,.7f);drawCircle(tint,w*.08f,androidx.compose.ui.geometry.Offset(w*.5f,h*.86f)) }
            "Settings","Rules" -> { drawCircle(tint,w*.27f,style=Stroke(w*.07f));repeat(6) { i -> val angle=i*Math.PI/3;val x=kotlin.math.cos(angle).toFloat();val y=kotlin.math.sin(angle).toFloat();line(.5f+x*.28f,.5f+y*.28f,.5f+x*.4f,.5f+y*.4f) };drawCircle(tint,w*.07f) }
            else -> repeat(5) { i->val x=.15f+i*.17f;val length=if(i%2==0) .3f else .65f;line(x,.5f-length/2,x,.5f+length/2) }
        }
    }
}
@Composable fun AuraPanel(modifier: Modifier=Modifier, content: @Composable ColumnScope.()->Unit) {
    Card(modifier.fillMaxWidth(),border=BorderStroke(1.dp,AuraColors.Border),colors=CardDefaults.cardColors(containerColor=AuraColors.Surface)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
    }
}
@Composable fun AuraNavigation(selected: String, rail: Boolean, onSelect: (String)->Unit) {
    val pages=listOf("Dashboard","Live","Rules","Events","Settings")
    val current=if(selected in pages) selected else "Settings"
    if(rail) NavigationRail(modifier=Modifier.fillMaxHeight().testTag("tablet-navigation"),containerColor=AuraColors.Background) {
        Spacer(Modifier.height(16.dp))
        pages.forEach { page-> NavigationRailItem(selected=current==page,onClick={onSelect(page)},icon={AuraIcon(page,tint=if(current==page) AuraColors.Cyan else AuraColors.Muted)},label={Text(page)}) }
    } else NavigationBar(modifier=Modifier.testTag("phone-navigation"),containerColor=AuraColors.Background,tonalElevation=0.dp) {
        pages.forEach { page-> NavigationBarItem(selected=current==page,onClick={onSelect(page)},icon={AuraIcon(page,tint=if(current==page) AuraColors.Cyan else AuraColors.Muted)},label={Text(page,fontSize=11.sp,maxLines=1)},colors=NavigationBarItemDefaults.colors(indicatorColor=Color(0xFF102C43))) }
    }
}
@Composable fun MonitoringHero(status: String, active: Boolean, modelLoaded: Boolean, enabled: Boolean, compact: Boolean=false, onToggle: ()->Unit) {
    AuraPanel {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(10.dp).background(if(active && modelLoaded) Color(0xFF08D497) else AuraColors.Muted,CircleShape))
            Column { Text(if(active) "Monitoring ${status.lowercase()}" else status,fontWeight=FontWeight.SemiBold)
                Text(if(active) "Microphone in use · on-device processing" else "Microphone off · activate to begin",style=MaterialTheme.typography.bodySmall,color=AuraColors.Muted) }
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical=if(compact) 0.dp else 8.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(if(compact) 120.dp else 150.dp).background(Brush.radialGradient(listOf(Color(0xFF145890),Color(0xFF041729))),CircleShape),contentAlignment=Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) { drawCircle(AuraColors.Blue,style=Stroke(8.dp.toPx()));drawCircle(AuraColors.Cyan,radius=size.minDimension/2-7.dp.toPx(),style=Stroke(2.dp.toPx())) }
            AuraIcon("mic",Modifier.size(64.dp))
        }
        OutlinedButton(onClick=onToggle,enabled=enabled,modifier=Modifier.widthIn(min=220.dp).heightIn(min=48.dp),border=BorderStroke(1.dp,AuraColors.Cyan)) { Text(if(active) "Stop monitoring" else "Start monitoring") }
    }
}
@Composable fun EventSummary(event: LocalEvent, onSelect: ()->Unit) {
    OutlinedCard(onClick=onSelect,modifier=Modifier.fillMaxWidth(),border=BorderStroke(1.dp,AuraColors.Border),colors=CardDefaults.outlinedCardColors(containerColor=AuraColors.Surface)) {
        Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            AuraIcon(event.eventType,Modifier.size(32.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(event.label,fontWeight=FontWeight.SemiBold)
                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(event.startMs)),style=MaterialTheme.typography.bodySmall,color=AuraColors.Muted)
                Text("${event.provenance} · ${event.state}",style=MaterialTheme.typography.labelSmall,color=AuraColors.Cyan)
                if(event.restoredFromBackup) Text("Restored metadata · unverified · no alert replayed",style=MaterialTheme.typography.labelSmall)
            }
            Text(event.peakScore?.let { "%.2f".format(it) } ?: "RMS",color=AuraColors.Cyan)
        }
    }
}
@Composable fun DetailLine(label: String,value: String) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp)) { Text(label,Modifier.weight(1f),color=AuraColors.Muted,style=MaterialTheme.typography.bodyMedium);Text(value,Modifier.weight(1.2f),style=MaterialTheme.typography.bodyMedium) }
}
@Composable fun EventDetails(event: LocalEvent,onBack: ()->Unit) {
    TextButton(onClick=onBack) { Text("← Event history") }
    AuraPanel { Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) { AuraIcon(event.eventType,Modifier.size(40.dp));Column { Text(event.label,style=MaterialTheme.typography.titleLarge);Text("${event.provenance} · ${event.state}",color=AuraColors.Cyan) } } }
    AuraPanel {
        DetailLine("Start time",DateFormat.getDateTimeInstance().format(Date(event.startMs)))
        DetailLine("End time",event.endMs?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "Active / not ended")
        DetailLine("Duration",if(event.endMs==null) "Updates when ended" else "${event.durationMs} ms")
        DetailLine("Peak score",event.peakScore?.let { "%.3f (uncalibrated)".format(it) } ?: "Not applicable")
        DetailLine("Average score",event.meanScore?.let { "%.3f (uncalibrated)".format(it) } ?: "Not applicable")
        DetailLine("RMS","%.3f (amplitude)".format(event.rms))
        DetailLine("Class ID",event.classId ?: "rms_above")
        DetailLine("Model",event.modelVersion ?: "None")
        DetailLine("Threshold version",event.thresholdVersion ?: "None")
        DetailLine("Device",event.deviceId)
        DetailLine("Notification",event.notificationStatus)
    }
    if(event.restoredFromBackup) Text("Restored metadata · unverified · no alert replayed")
    Text(RuleFeedback.notificationHistory(event.notificationStatus),color=AuraColors.Muted)
    Text("Metadata only. No audio was stored, so playback and spectrograms are unavailable. A model score is not a calibrated probability or proof of a real-world event.",style=MaterialTheme.typography.bodySmall,color=AuraColors.Muted)
}

/** Bounded session-only trace of observed top scores; never contains or stores audio. */
@Composable fun LiveScoreChart(scores: List<ClassScore>,active: Boolean) {
    val points=remember { mutableStateListOf<Pair<Long,Float>>() }
    LaunchedEffect(scores,active) {
        if(!active || scores.isEmpty()) points.clear()
        else if(scores.isNotEmpty()) {
            val now=android.os.SystemClock.elapsedRealtime()
            points.add(now to scores.first().score.coerceIn(0f,1f))
            while(points.size>60 || (points.isNotEmpty() && now-points.first().first>30000)) points.removeAt(0)
        }
    }
    AuraPanel {
        Text("Score over time",style=MaterialTheme.typography.titleMedium)
        Text("Top-class raw score per received update · session-only · up to 30 s",style=MaterialTheme.typography.bodySmall,color=AuraColors.Muted)
        if(points.size<2) Text("Waiting for live measurements",color=AuraColors.Muted)
        else Canvas(Modifier.fillMaxWidth().height(100.dp).semantics { contentDescription="Recent uncalibrated top-class score trace" }) {
            repeat(3) { i->drawLine(AuraColors.Border,androidx.compose.ui.geometry.Offset(0f,size.height*i/2),androidx.compose.ui.geometry.Offset(size.width,size.height*i/2)) }
            val span=(points.last().first-points.first().first).coerceAtLeast(1)
            val path=Path()
            points.forEachIndexed { i,(time,score)->val x=size.width*(time-points.first().first)/span;val y=size.height*(1-score);if(i==0) path.moveTo(x,y) else path.lineTo(x,y) }
            drawPath(path,AuraColors.Cyan,style=Stroke(2.dp.toPx(),cap=StrokeCap.Round))
        }
        Text("0–1 model output · class may change between updates",style=MaterialTheme.typography.labelSmall,color=AuraColors.Muted)
    }
}
