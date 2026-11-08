package io.github.beomsun0829.handsonguide.ui

import io.github.beomsun0829.handsonguide.scene.RoadSceneView
import io.github.beomsun0829.handsonguide.scene.SceneScript
import io.github.beomsun0829.handsonguide.scene.FogInput
import io.github.beomsun0829.handsonguide.scene.ParkingInput
import io.github.beomsun0829.handsonguide.scene.ParkingPlayback
import io.github.beomsun0829.handsonguide.tutorial.SceneNarration
import io.github.beomsun0829.handsonguide.signal.Signals
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun RoadScenePanel(cardId: String, signals: Signals, modifier: Modifier = Modifier, narration: SceneNarration? = null, parking: ParkingInput? = null) {
    val scriptId = SceneScript.cards[cardId] ?: return
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var foreground by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var retry by remember(scriptId) { mutableIntStateOf(0) }
    var failed by remember(scriptId,retry) { mutableStateOf(false) }
    val script by produceState<SceneScript?>(null,scriptId,retry) {
        value = runCatching {
            withContext(Dispatchers.IO) { context.assets.open("scenes/$scriptId.json").bufferedReader().use { SceneScript.parse(it.readText()) } }
        }.getOrElse { failed=true; null }
    }
    DisposableEffect(owner) {
        val observer=LifecycleEventObserver { _,_ -> foreground=owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val current = script
    if (failed || current==null || current.id!=scriptId) {
        Column(modifier.background(c.surface).padding(24.dp), verticalArrangement=Arrangement.Center, horizontalAlignment=Alignment.CenterHorizontally) {
            Glyph("lane",c.info,120.dp)
            Text(if(failed) "장면을 불러오지 못했어요" else "장면을 준비하고 있어요",style=GuideType.body,color=c.textSub)
            if(failed) TextButton(onClick={retry++}) { Text("다시 불러오기",style=GuideType.label,color=c.info) }
        }
        return
    }
    var phase by remember(current) { mutableStateOf(current.phases.first()) }
    val holder=remember(current,retry) { arrayOfNulls<RoadSceneView>(1) }
    val safe=if(current.kind=="parking") ParkingPlayback.canRender(signals,parking?.steps ?: 0,parking?.paused==true,parking?.pending==true)
        else signals.gear=="P" && signals.num(Signals.SPEED)==0.0 && signals.bool(Signals.UXR_DO)!=true
    val active=foreground && safe
    DisposableEffect(holder) { onDispose { holder[0]?.setActive(false) } }
    key(current.id,retry) { Column(modifier.background(c.surface)) {
        Row(Modifier.fillMaxWidth().heightIn(min=60.dp).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.SpaceBetween) {
            Text(when {
                !phase.correct -> "옳지 않은 예"
                current.kind=="parking" && phase.view=="practice" -> "직접 해보기"
                current.kind=="parking" -> "조작·원리 예시"
                current.kind=="fog" -> "작동 예시"
                else -> "이렇게 해요"
            },style=GuideType.label,color=if(phase.correct)c.ok else c.warn)
            TextButton(onClick={holder[0]?.replay()},enabled=active) { Text("처음부터",style=GuideType.label,color=c.info) }
        }
        if(current.kind=="parking" && !safe) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.Center,
                horizontalAlignment=Alignment.CenterHorizontally) {
                Glyph("brake",c.warn,100.dp)
                Text("브레이크와 주차 브레이크를 확인해요",style=GuideType.body,color=c.text)
            }
        } else AndroidView(modifier=Modifier.weight(1f).fillMaxWidth(),
            factory={ RoadSceneView(it,current,onPhase={p->phase=p},onFailure={failed=true}).also { v->holder[0]=v } },
            update={
                it.setFogInput(FogInput(signals.bool(Signals.DEFROST_FRONT),narration?.run,narration?.sentence ?: -1,narration?.finished==true))
                parking?.let(it::setParkingInput)
                it.setActive(active)
            }, onRelease={it.setActive(false)})
        Column(Modifier.fillMaxWidth().heightIn(min=104.dp).padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(phase.title,style=GuideType.title,color=c.text)
            Text(phase.caption,style=GuideType.caption,color=c.textSub)
            if(current.kind=="road") Text("작은 표시가 내 차예요",style=GuideType.caption,color=c.textSub)
        }
    } }
}
