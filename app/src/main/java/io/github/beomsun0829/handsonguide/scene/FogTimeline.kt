package io.github.beomsun0829.handsonguide.scene

data class FogVisual(val opacity: Float, val buttonOn: Boolean, val handPress: Float?, val airflow: Boolean)

object FogTimeline {
    private fun smooth(t: Float) = t.coerceIn(0f,1f).let { it*it*(3-2*it) }

    fun frame(script: SceneScript, index: Int, time: Float): SceneFrame {
        val phase = script.phases[index]
        val length = phase.end-phase.start
        val t = time.coerceIn(0f,length)
        val fog = when(index) {
            0 -> FogVisual(.84f,false,null,false)
            1 -> FogVisual(.84f,t>=1.6f,press(t),false)
            2 -> FogVisual(.84f-.80f*smooth(t/length),true,null,true)
            else -> FogVisual(.04f,false,0f,false)
        }
        val target = phase.camera!!
        val previous = script.phases.getOrNull(index-1)?.camera ?: target
        val amount = smooth(t/.7f)
        fun mix(a: Float,b: Float) = a+(b-a)*amount
        fun vec(a: Vec3,b: Vec3) = Vec3(mix(a.x,b.x),mix(a.y,b.y),mix(a.z,b.z))
        val camera = if(amount>=1f) target else SceneCamera(vec(previous.eye,target.eye),vec(previous.target,target.target),mix(previous.fov,target.fov))
        return SceneFrame(script.id,index,phase,t,emptyList(),camera,fog)
    }

    fun press(time: Float): Float = when {
        time < .6f -> 0f
        time < 1.6f -> smooth((time-.6f)/1f)
        time < 1.9f -> 1f
        time < 2.8f -> 1-smooth((time-1.9f)/.9f)
        else -> 0f
    }
}

data class FogInput(val defrostOn: Boolean? = null, val narrationRun: Int? = null,
    val sentence: Int = -1, val narrationFinished: Boolean = false)

/** Illustrative sequence -> control close-up. Only a known real switch edge starts the live effect. */
class FogPlayback(private val script: SceneScript) {
    private var elapsed = 0f
    private var initial = true
    private var lastOn: Boolean? = null
    private var liveAt: Float? = null
    private var liveOn = false
    private var run: Int? = null
    private var sentence = -1
    private var sentenceAt = 0f
    private var manual = false

    fun replay() { elapsed=0f; sentenceAt=0f; manual=true; liveAt=null }

    fun advance(delta: Float, input: FogInput): SceneFrame {
        elapsed += delta.takeIf { it.isFinite() }?.coerceIn(0f,.2f) ?: 0f
        if (input.narrationRun != run) { run=input.narrationRun; sentence=-1; manual=false; elapsed=0f; liveAt=null }
        if (input.defrostOn != null) {
            if (initial && input.defrostOn) { manual=false; elapsed=script.phases[3].start }
            else if (lastOn != null && input.defrostOn != lastOn) { liveAt=elapsed; liveOn=input.defrostOn }
            else if (lastOn == null && input.defrostOn) elapsed=script.phases[3].start
            lastOn=input.defrostOn
        }
        initial=false
        liveAt?.let { start ->
            val t=elapsed-start
            if (t<.6f) return FogTimeline.frame(script,1,1.4f+t).let {
                it.copy(phase=it.phase.copy(title="버튼을 눌렀어요",caption="차에서 받은 제상 상태를 표시해요."),
                    camera=script.phases[1].camera!!,fog=FogVisual(.84f,liveOn,FogTimeline.press(1.4f+t),false))
            }
            if (liveOn && t<4.6f) return FogTimeline.frame(script,2,(t-.6f).coerceAtLeast(0f)).let {
                it.copy(phase=it.phase.copy(title="앞유리로 바람이 가요",caption="김이 걷히는 작동 효과 예시예요."))
            }
            return practice(input.defrostOn)
        }
        if (!manual && input.narrationRun != null && !input.narrationFinished && input.sentence>=0) {
            if (sentence != input.sentence) { sentence=input.sentence; sentenceAt=elapsed }
            val index=sentence.coerceAtMost(3)
            if (index == 3) return practice(input.defrostOn)
            return FogTimeline.frame(script,index,(elapsed-sentenceAt).coerceAtMost(script.phases[index].end-script.phases[index].start-.01f)).let {
                if(input.defrostOn==true) it.copy(fog=it.fog!!.copy(buttonOn=true)) else it
            }
        }
        if ((!manual && input.narrationFinished) || elapsed>=script.phases[3].start || (!manual && input.defrostOn==true)) return practice(input.defrostOn)
        return script.sample(elapsed).let { if(input.defrostOn==true) it.copy(fog=it.fog!!.copy(buttonOn=true)) else it }
    }

    private fun practice(on: Boolean?): SceneFrame = FogTimeline.frame(script,3,4f).let {
        it.copy(phase=it.phase.copy(title=if(on==true) "켜진 버튼을 확인해요" else "직접 해볼 차례예요",
            caption=if(on==true) "실제 버튼을 다시 꺼주세요." else "앞유리 모양의 실제 김서림 버튼을 눌러보세요."),
            fog=FogVisual(.04f,on==true,0f,false))
    }
}
