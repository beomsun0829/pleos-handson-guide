package io.github.beomsun0829.handsonguide.scene

import io.github.beomsun0829.handsonguide.signal.Signals

data class ParkingVisual(val focus: String, val parkingBrake: Boolean?, val footBrake: Boolean?, val gear: String?,
    val handAmount: Float = 0f, val diagram: Boolean = false, val pawlLoaded: Boolean = false, val live: Boolean = false,
    val targetGear: String? = null)

object ParkingTimeline {
    fun frame(script: SceneScript,index: Int,time: Float): SceneFrame {
        val phase=script.phases[index]
        val t=time.coerceIn(0f,phase.end-phase.start)
        val press=FogTimeline.press(t)
        val visual=when(index) {
            0 -> ParkingVisual("foot",false,true,"P")
            1 -> ParkingVisual("parking-brake",t>=1.6f,true,"P",press)
            2 -> ParkingVisual("gear",true,true,if(t>=1.6f) "N" else "P",press,targetGear="N")
            3 -> ParkingVisual("foot",true,t<1f,"N")
            4 -> ParkingVisual("gear",true,true,if(t>=1.6f) "P" else "N",press,targetGear="P")
            5 -> ParkingVisual("load",false,false,"P",diagram=true,pawlLoaded=true)
            6 -> ParkingVisual("load",true,false,"P",diagram=true)
            else -> ParkingVisual("parking-brake",null,null,null)
        }
        return SceneFrame(script.id,index,phase,t,emptyList(),phase.camera!!,parking=visual)
    }
}

data class ParkingInput(val gear: String? = null,val parkingBrake: Boolean? = null,val footBrake: Boolean? = null,
    val speed: Double? = null,val restricted: Boolean = false,val steps: Int = 0,val paused: Boolean = false,
    val pending: Boolean = false,val complete: Boolean = false,val narrationRun: Int? = null,
    val sentence: Int = -1,val narrationFinished: Boolean = false)

class ParkingPlayback(private val script: SceneScript) {
    private var elapsed=0f
    private var run: Int? = null
    private var sentence=-1
    private var sentenceAt=0f
    private var manual=false
    private var live=false
    private var last: ParkingInput? = null
    private var completedAt: Float? = null
    private var handAt: Float? = null

    fun replay() { elapsed=0f; manual=true; live=false; completedAt=null; handAt=null }

    fun advance(delta: Float,input: ParkingInput): SceneFrame {
        elapsed+=delta.takeIf { it.isFinite() }?.coerceIn(0f,.2f) ?: 0f
        if(run!=input.narrationRun) { run=input.narrationRun; sentence=-1; manual=false; elapsed=0f; completedAt=null; handAt=null }
        val previous=last
        val changed=previous!=null && (
            changedKnown(previous.gear,input.gear) || changedKnown(previous.parkingBrake,input.parkingBrake) ||
                changedKnown(previous.footBrake,input.footBrake) || input.steps>previous.steps)
        if(changed || previous==null && (input.steps>0 || input.complete)) {
            live=true; manual=false
            if(previous?.parkingBrake==false && input.parkingBrake==true || changedKnown(previous?.gear,input.gear)) handAt=elapsed
        }
        if(input.complete && previous?.complete!=true && live) completedAt=elapsed
        last=input
        if(live) {
            val completed=completedAt
            if(completed!=null && elapsed-completed<3f) {
                val f=ParkingTimeline.frame(script,6,elapsed-completed)
                return f.copy(phase=f.phase.copy(title="주차 순서를 확인했어요",caption="주차 브레이크가 먼저 무게를 받는 원리 예시예요."),parking=f.parking!!.copy(live=true))
            }
            return practice(input)
        }
        if(!manual && input.narrationRun!=null && !input.narrationFinished && input.sentence>=0) {
            if(sentence!=input.sentence) { sentence=input.sentence; sentenceAt=elapsed }
            val index=sentence.coerceAtMost(7)
            if(index==7) return practice(input)
            return ParkingTimeline.frame(script,index,(elapsed-sentenceAt).coerceAtMost(script.phases[index].end-script.phases[index].start-.01f))
        }
        if((!manual && input.narrationFinished) || elapsed>=script.phases[7].start) return practice(input)
        return script.sample(elapsed)
    }

    private fun practice(input: ParkingInput): SceneFrame {
        val focus=when {
            input.pending -> "parking-brake"
            input.complete -> "gear"
            input.footBrake!=true && input.steps<2 -> "foot"
            input.parkingBrake!=true || input.steps==0 -> "parking-brake"
            input.steps==1 || input.steps>=3 -> "gear"
            else -> "foot"
        }
        val index=when(focus) { "foot" -> 0; "gear" -> 2; else -> 1 }
        val caption=when {
            input.pending -> "차에서 신호를 받으면 조작 상태를 표시해요."
            input.complete -> "주차 브레이크와 P 상태를 확인했어요."
            focus=="foot" && input.steps<2 -> "브레이크를 밟고 완전히 멈춰요."
            input.steps==0 -> "브레이크를 밟은 채 주차 브레이크를 걸어요."
            input.steps==1 -> "주차 브레이크를 건 채 N으로 놓아요."
            input.steps==2 -> "N에서 페달을 떼고, 차가 그대로인지 확인해요."
            else -> "안 밀리는 것을 확인한 뒤 P로 넣어요."
        }
        val phase=script.phases[7].copy(title=if(input.complete) "순서를 확인했어요" else "직접 해볼 차례예요",caption=caption)
        val hand=handAt?.let { t -> FogTimeline.press(1f+(elapsed-t)*2f).takeIf { elapsed-t<1f } } ?: 0f
        return SceneFrame(script.id,7,phase,0f,emptyList(),script.phases[index].camera!!,
            parking=ParkingVisual(focus,input.parkingBrake,input.footBrake,input.gear,hand,live=true,
                targetGear=if(input.steps>=3 || input.complete) "P" else "N"))
    }

    private fun changedKnown(a: Any?,b: Any?) = a!=null && b!=null && a!=b

    companion object {
        fun canRender(s: Signals,steps: Int,paused: Boolean,pending: Boolean): Boolean {
            if(s.num(Signals.SPEED)!=0.0 || s.bool(Signals.UXR_DO)==true || paused) return false
            if(s.gear=="P") return true
            return s.gear=="N" && !pending && s.bool(Signals.PARKING_BRAKE)==true &&
                (s.bool(Signals.BRAKE)==true || steps>=2 && s.bool(Signals.BRAKE)==false)
        }
    }
}
