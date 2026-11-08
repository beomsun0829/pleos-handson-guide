package io.github.beomsun0829.handsonguide.scene

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Choreographer
import android.view.MotionEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.acos

/** Frame counters are observational only; debug probes can prove that hidden scenes stop. */
object SceneDiagnostics {
    val active = AtomicInteger()
    val frames = AtomicLong()
    val failures = AtomicInteger()
    @Volatile var scriptId: String? = null
    @Volatile var elapsed = 0f
    @Volatile var phaseIndex = 0
    @Volatile var yaw = 0f
    @Volatile var bounds = listOf(0,0,0,0)
    @Volatile var fogOpacity: Float? = null
    @Volatile var defrostButton: Boolean? = null
    @Volatile var parkingFocus: String? = null
    @Volatile var parkingGear: String? = null
    @Volatile var parkingBrake: Boolean? = null
    @Volatile var footBrake: Boolean? = null
    @Volatile var parkingLive = false
}

class RoadSceneView(context: Context, private val script: SceneScript,
    private val onPhase: (ScenePhase) -> Unit, private val onFailure: () -> Unit) : GLSurfaceView(context), Choreographer.FrameCallback {
    private val roadRenderer = RoadRenderer(context,script.model,script.kind=="parking") { post { onFailure() } }
    private var running = false
    private var elapsed = 0f
    private var lastFrame = 0L
    private var lastDraw = 0L
    private var shownPhase = -1
    private var downX = 0f
    private var downYaw = 0f
    private var dragged = false
    private var dragging = false
    private var releaseAt = 0L
    private var releaseYaw = 0f
    private var yaw = 0f
    private val fogPlayback = script.takeIf { it.kind == "fog" }?.let(::FogPlayback)
    private var fogInput = FogInput()
    private val parkingPlayback = script.takeIf { it.kind=="parking" }?.let(::ParkingPlayback)
    private var parkingInput = ParkingInput()
    private var shownContent: ScenePhase? = null

    init {
        contentDescription = (when(script.kind) { "fog" -> "앞유리 김서림 3D 작동 예시."; "parking" -> "세우는 순서와 주차 브레이크 원리 3D 예시."; else -> "차선 변경 3D 장면." }) +
            " 누르면 처음부터 다시 보고, 좌우로 끌면 시점을 조금 돌려볼 수 있어요."
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8,8,8,8,16,0)
        preserveEGLContextOnPause = true
        setRenderer(roadRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        onPause()
    }

    fun setActive(value: Boolean) {
        if (running==value) return
        running=value
        roadRenderer.allowed=value
        lastFrame=0L; lastDraw=0L
        if (value) {
            SceneDiagnostics.active.incrementAndGet()
            onResume()
            Choreographer.getInstance().postFrameCallback(this)
        } else {
            Choreographer.getInstance().removeFrameCallback(this)
            SceneDiagnostics.active.decrementAndGet()
            onPause()
        }
    }

    fun setFogInput(input: FogInput) { fogInput=input }
    fun setParkingInput(input: ParkingInput) { parkingInput=input }
    fun replay() { elapsed=0f; shownPhase=-1; shownContent=null; yaw=0f; releaseAt=0L; lastFrame=0L; fogPlayback?.replay(); parkingPlayback?.replay() }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val delta=if(lastFrame!=0L) ((frameTimeNanos-lastFrame)/1_000_000_000f).coerceIn(0f,.2f) else 0f
        elapsed += delta
        lastFrame=frameTimeNanos
        if (!dragging && releaseAt!=0L) yaw=releaseYaw*(1-(frameTimeNanos-releaseAt)/1_200_000_000f).coerceIn(0f,1f)
        val fogFrame=fogPlayback?.advance(delta,fogInput)
        val parkingFrame=parkingPlayback?.advance(delta,parkingInput)
        if (lastDraw==0L || frameTimeNanos-lastDraw>=33_000_000) {
            lastDraw=frameTimeNanos
            val frame=fogFrame ?: parkingFrame ?: script.sample(elapsed)
            if (shownPhase!=frame.phaseIndex || shownContent!=frame.phase) {
                shownPhase=frame.phaseIndex; shownContent=frame.phase; onPhase(frame.phase)
            }
            roadRenderer.frame=frame
            roadRenderer.orbitYaw=yaw
            SceneDiagnostics.scriptId=script.id
            SceneDiagnostics.elapsed=elapsed
            SceneDiagnostics.phaseIndex=frame.phaseIndex
            SceneDiagnostics.yaw=yaw
            SceneDiagnostics.fogOpacity=frame.fog?.opacity
            SceneDiagnostics.defrostButton=frame.fog?.buttonOn
            SceneDiagnostics.parkingFocus=frame.parking?.focus
            SceneDiagnostics.parkingGear=frame.parking?.gear
            SceneDiagnostics.parkingBrake=frame.parking?.parkingBrake
            SceneDiagnostics.footBrake=frame.parking?.footBrake
            SceneDiagnostics.parkingLive=frame.parking?.live==true
            val location=IntArray(2); getLocationOnScreen(location)
            SceneDiagnostics.bounds=listOf(location[0],location[1],width,height)
            requestRender()
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!running) return false
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX=event.x; downYaw=yaw; dragged=false; dragging=true; releaseAt=0L }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x-downX)>8*resources.displayMetrics.density) dragged=true
                yaw=(downYaw+(event.x-downX)/width.coerceAtLeast(1)*40).coerceIn(-24f,24f)
            }
            MotionEvent.ACTION_UP -> {
                dragging=false
                if (!dragged) performClick() else { releaseAt=System.nanoTime(); releaseYaw=yaw }
            }
            MotionEvent.ACTION_CANCEL -> { dragging=false; releaseAt=System.nanoTime(); releaseYaw=yaw }
        }
        return true
    }

    override fun performClick(): Boolean { replay(); return super.performClick() }
    override fun onDetachedFromWindow() { setActive(false); super.onDetachedFromWindow() }
}

private class RoadRenderer(private val context: Context, private val asset: String, private val exterior: Boolean, private val failed: () -> Unit) : GLSurfaceView.Renderer {
    @Volatile var frame: SceneFrame? = null
    @Volatile var orbitYaw = 0f
    @Volatile var allowed = false
    private data class Part(val data: RoadPrimitive, val buffer: FloatBuffer)
    private var meshes = emptyMap<String,List<Part>>()
    private var program=0
    private var ready=false
    private var width=1
    private var height=1
    private val model=FloatArray(16)
    private val view=FloatArray(16)
    private val projection=FloatArray(16)
    private val pv=FloatArray(16)
    private val mvp=FloatArray(16)
    private val pose=FloatArray(16)
    private var position=0; private var normal=0; private var modelLoc=0; private var mvpLoc=0; private var colorLoc=0; private var lightLoc=0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            fun load(name: String)=context.assets.open("scenes/$name").use { RoadModel.parse(it.readBytes()) }.mapValues { (_,mesh) ->
                mesh.primitives.map { p -> Part(p,ByteBuffer.allocateDirect(p.vertices.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(p.vertices); position(0) }) }
            }
            meshes=load(asset)
            if(exterior) meshes=meshes+load("road-kit.glb").filterKeys { it !in meshes }
            require("box" in meshes && ("sedan" in meshes || "cabin" in meshes))
            fun shader(kind:Int, source:String):Int {
                val s=GLES20.glCreateShader(kind)
                GLES20.glShaderSource(s,source); GLES20.glCompileShader(s)
                val status=IntArray(1); GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,status,0)
                check(status[0]!=0) { GLES20.glGetShaderInfoLog(s) }; return s
            }
            val vertex=shader(GLES20.GL_VERTEX_SHADER,"""
                attribute vec3 aPosition; attribute vec3 aNormal;
                uniform mat4 uMvp; uniform mat4 uModel;
                varying vec3 vNormal;
                void main(){gl_Position=uMvp*vec4(aPosition,1.0);vNormal=mat3(uModel)*aNormal;}
            """.trimIndent())
            val fragment=shader(GLES20.GL_FRAGMENT_SHADER,"""
                precision mediump float; varying vec3 vNormal;
                uniform vec4 uColor; uniform float uUnlit;
                void main(){float l=0.55+0.45*max(dot(normalize(vNormal),normalize(vec3(0.4,1.0,0.5))),0.0);
                gl_FragColor=vec4(uColor.rgb*mix(l,1.0,uUnlit),uColor.a);}
            """.trimIndent())
            program=GLES20.glCreateProgram()
            GLES20.glAttachShader(program,vertex); GLES20.glAttachShader(program,fragment); GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
            val status=IntArray(1); GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,status,0)
            check(status[0]!=0) { GLES20.glGetProgramInfoLog(program) }
            position=GLES20.glGetAttribLocation(program,"aPosition"); normal=GLES20.glGetAttribLocation(program,"aNormal")
            modelLoc=GLES20.glGetUniformLocation(program,"uModel"); mvpLoc=GLES20.glGetUniformLocation(program,"uMvp")
            colorLoc=GLES20.glGetUniformLocation(program,"uColor"); lightLoc=GLES20.glGetUniformLocation(program,"uUnlit")
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA,GLES20.GL_ONE_MINUS_SRC_ALPHA)
            ready=true
        } catch (_:Exception) { fail() }
    }

    override fun onSurfaceChanged(gl: GL10?, width:Int, height:Int) {
        this.width=width.coerceAtLeast(1); this.height=height.coerceAtLeast(1)
        GLES20.glViewport(0,0,this.width,this.height)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!allowed || !ready) return
        val f=frame ?: return
        try {
            if(f.fog!=null) GLES20.glClearColor(.42f,.64f,.79f,1f) else GLES20.glClearColor(.055f,.085f,.12f,1f)
            GLES20.glDepthMask(true)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            GLES20.glUseProgram(program)
            val c=f.camera; val angle=orbitYaw*Math.PI.toFloat()/180
            val dx=c.eye.x-c.target.x; val dz=c.eye.z-c.target.z
            Matrix.setLookAtM(view,0,c.target.x+dx*cos(angle)+dz*sin(angle),c.eye.y,
                c.target.z+dz*cos(angle)-dx*sin(angle),c.target.x,c.target.y,c.target.z,0f,1f,0f)
            Matrix.perspectiveM(projection,0,c.fov,width.toFloat()/height,if(f.fog!=null || f.parking?.diagram==false) .025f else .1f,150f)
            Matrix.multiplyMM(pv,0,projection,0,view,0)
            if(f.parking!=null) {
                parkingScene(f)
                GLES20.glDepthMask(true)
                check(GLES20.glGetError()==GLES20.GL_NO_ERROR)
                SceneDiagnostics.frames.incrementAndGet()
                return
            }
            if(f.fog!=null) {
                fogScene(f)
                GLES20.glDepthMask(true)
                check(GLES20.glGetError()==GLES20.GL_NO_ERROR)
                SceneDiagnostics.frames.incrementAndGet()
                return
            }
            box(0f,-.10f,3f,18f,.12f,56f,listOf(.10f,.18f,.18f,1f))
            box(0f,-.015f,3f,7.4f,.08f,50f,listOf(.18f,.23f,.30f,1f))
            for (x in listOf(-3.5f,3.5f)) box(x,.04f,3f,.10f,.02f,50f,listOf(.85f,.9f,.94f,1f))
            val offset=(f.time*3)%4
            for (i in -7..8) box(0f,.04f,i*4f+offset,.10f,.02f,2f,listOf(.86f,.91f,.96f,1f))
            val cars=f.cars.sortedBy { it.z }
            for (car in cars) {
                // Ground contact and body are separate draws; no moving decoration outside this parked view.
                box(car.x,.045f,car.z,2.03f,.015f,5.05f,listOf(0f,0f,0f,.16f))
                Matrix.setIdentityM(model,0); Matrix.translateM(model,0,car.x,0f,car.z); Matrix.rotateM(model,0,car.yaw,0f,1f,0f)
                GLES20.glDepthMask(car.color[3]>=1f)
                draw("sedan",car.color,car.brake,car.signal && (f.time*3).toInt()%2==0)
                if(car.id=="ego") {
                    box(car.x,2.05f,car.z,.32f,.32f,.32f,
                        if(f.phase.correct) listOf(.12f,.7f,1f,1f) else listOf(1f,.62f,.12f,1f))
                }
            }
            GLES20.glDepthMask(true)
            check(GLES20.glGetError()==GLES20.GL_NO_ERROR)
            SceneDiagnostics.frames.incrementAndGet()
        } catch (_:Exception) { fail() }
    }

    private fun box(x:Float,y:Float,z:Float,w:Float,h:Float,l:Float,color:List<Float>) {
        Matrix.setIdentityM(model,0); Matrix.translateM(model,0,x,y,z); Matrix.scaleM(model,0,w,h,l)
        GLES20.glDepthMask(color[3]>=1f)
        draw("box",color,false,false)
    }

    private fun fogScene(f: SceneFrame) {
        val fog=f.fog!!
        box(0f,-.04f,-11f,24f,.08f,30f,listOf(.24f,.36f,.29f,1f))
        box(0f,.01f,-11f,5.5f,.03f,28f,listOf(.24f,.29f,.34f,1f))
        for(x in listOf(-2.7f,2.7f)) box(x,.04f,-11f,.10f,.03f,28f,listOf(.9f,.93f,.93f,1f))
        for(z in -2 downTo -25 step 4) box(0f,.04f,z.toFloat(),.09f,.02f,1.8f,listOf(.94f,.91f,.67f,1f))
        box(-4.8f,1.8f,-10f,2.8f,3.6f,7f,listOf(.54f,.64f,.69f,1f))
        box(4.6f,1.3f,-8f,2.4f,2.6f,6f,listOf(.68f,.66f,.58f,1f))
        box(3.2f,.8f,-4.6f,.08f,1.6f,.08f,listOf(.40f,.47f,.49f,1f))
        box(3.2f,1.68f,-4.6f,.65f,.5f,.05f,listOf(.16f,.45f,.70f,1f))
        Matrix.setIdentityM(model,0); draw("cabin",listOf(1f,1f,1f,1f),false,false)
        Matrix.setIdentityM(model,0); draw("wheel",listOf(1f,1f,1f,1f),false,false)
        val press=fog.handPress ?: 0f
        val buttonZ=-.405f-.008f*press
        box(.26f,.74f,-.42f,.21f,.14f,.015f,
            if(fog.buttonOn) listOf(.14f,.75f,.46f,1f) else listOf(.13f,.54f,.90f,1f))
        Matrix.setIdentityM(model,0); Matrix.translateM(model,0,.26f,.74f,buttonZ)
        draw("button",if(fog.buttonOn) listOf(.09f,.47f,.29f,1f) else listOf(.10f,.16f,.23f,1f),false,false)
        draw("defrost-symbol",listOf(1f,1f,1f,1f),false,false)
        for (x in listOf(.03f,.49f)) box(x,.74f,-.408f,.16f,.10f,.025f,listOf(.11f,.16f,.21f,1f))
        if(fog.handPress!=null) {
            Matrix.setIdentityM(model,0); Matrix.translateM(model,0,.26f,.74f,buttonZ+.163f-.17f*press)
            Matrix.rotateM(model,0,60f,1f,0f,0f)
            draw("hand",listOf(1f,1f,1f,1f),false,false)
        }
        // Geometry is behind the cabin. Depth testing keeps fog off the dashboard and hand.
        Matrix.setIdentityM(model,0); GLES20.glDepthMask(false)
        draw("windshield",listOf(.86f,.91f,.94f,fog.opacity),false,false)
        if(fog.airflow) {
            for(x in listOf(-.48f,0f,.48f)) {
                val color=listOf(.24f,.78f,1f,.85f)
                line(Vec3(x,.92f,-1.01f),Vec3(x,1.42f,-1.25f),.012f,color)
                line(Vec3(x,1.42f,-1.25f),Vec3(x-.045f,1.34f,-1.21f),.012f,color)
                line(Vec3(x,1.42f,-1.25f),Vec3(x+.045f,1.34f,-1.21f),.012f,color)
                val p=(f.time*.45f)%1f
                box(x,.92f+.50f*p,-1.01f-.24f*p,.025f,.04f,.025f,listOf(.65f,.94f,1f,1f))
            }
        }
    }

    private fun line(a: Vec3,b: Vec3,width: Float,color: List<Float>) {
        val dx=b.x-a.x; val dy=b.y-a.y; val dz=b.z-a.z
        val length=sqrt(dx*dx+dy*dy+dz*dz)
        Matrix.setIdentityM(model,0); Matrix.translateM(model,0,(a.x+b.x)/2,(a.y+b.y)/2,(a.z+b.z)/2)
        if(abs(dx)+abs(dz)>.00001f) Matrix.rotateM(model,0,acos((dy/length).coerceIn(-1f,1f))*180f/Math.PI.toFloat(),dz,0f,-dx)
        else if(dy<0) Matrix.rotateM(model,0,180f,1f,0f,0f)
        Matrix.scaleM(model,0,width,length,width); draw("box",color,false,false)
    }

    private fun parkingScene(f: SceneFrame) {
        val p=f.parking!!
        if(p.diagram) { parkingLoad(p); return }
        box(0f,.02f,-.40f,2f,.08f,1.8f,listOf(.055f,.075f,.095f,1f))
        box(-.42f,.30f,-.92f,.8f,.6f,.08f,listOf(.075f,.10f,.13f,1f))
        Matrix.setIdentityM(model,0); draw("cabin",listOf(1f,1f,1f,1f),false,false)
        Matrix.setIdentityM(model,0); draw("wheel",listOf(1f,1f,1f,1f),false,false)
        val white=listOf(.92f,.97f,1f,1f)
        if(p.focus=="foot") {
            val pressed=p.footBrake==true
            Matrix.setIdentityM(model,0); Matrix.translateM(model,0,-.35f,if(pressed) .23f else .25f,if(pressed) -.75f else -.70f)
            Matrix.rotateM(model,0,if(pressed) -28f else -14f,1f,0f,0f); Matrix.scaleM(model,0,.14f,.19f,.028f)
            draw("box",listOf(.32f,.53f,.64f,1f),false,false)
            box(-.12f,.23f,-.75f,.065f,.22f,.025f,listOf(.13f,.18f,.23f,1f))
            if(p.footBrake!=null) {
                Matrix.setIdentityM(pose,0); Matrix.translateM(pose,0,-.35f,.095f,-.29f)
                if(pressed) Matrix.rotateM(pose,0,-12f,1f,0f,0f)
                localBox(pose,0f,.03f,-.11f,.12f,.07f,.25f,listOf(.16f,.20f,.26f,1f))
                localBox(pose,0f,.08f,if(pressed) -.27f else -.22f,.12f,.09f,.17f,listOf(.24f,.30f,.37f,1f))
                localBox(pose,0f,.14f,.015f,.085f,.23f,.10f,listOf(.22f,.39f,.57f,1f))
            }
        } else if(p.focus=="gear") {
            for ((letter,x) in listOf("P" to .08f,"N" to .38f)) {
                Matrix.setIdentityM(model,0); Matrix.translateM(model,0,x,.74f,-.405f)
                draw("button",if(p.gear==letter) listOf(.08f,.49f,.31f,1f) else listOf(.11f,.17f,.24f,1f),false,false)
                model.copyInto(pose); symbol(pose,letter,.060f,white)
                if(p.targetGear==letter) box(x,.672f,-.382f,.12f,.008f,.006f,listOf(.20f,.65f,1f,1f))
            }
            parkingHand(if(p.targetGear=="P") .08f else .38f,p.handAmount,false)
        } else {
            Matrix.setIdentityM(model,0); Matrix.translateM(model,0,.26f,.74f,-.405f)
            Matrix.rotateM(model,0,p.handAmount*18f,1f,0f,0f)
            draw("button",when(p.parkingBrake) { true -> listOf(.09f,.49f,.30f,1f); false -> listOf(.12f,.20f,.28f,1f); null -> listOf(.27f,.30f,.34f,1f) },false,false)
            model.copyInto(pose); symbol(pose,"P",.050f,white,ring=true)
            parkingHand(.26f,p.handAmount,true)
        }
    }

    private fun parkingHand(x: Float,amount: Float,pull: Boolean) {
        Matrix.setIdentityM(model,0)
        Matrix.translateM(model,0,x,.74f+if(pull) .025f*amount else 0f,
            if(pull) -.26f-.13f*amount else -.24f-.17f*amount)
        Matrix.rotateM(model,0,60f,1f,0f,0f)
        draw("hand",listOf(1f,1f,1f,1f),false,false)
    }

    private fun parkingLoad(p: ParkingVisual) {
        val green=listOf(.18f,.80f,.48f,1f); val red=listOf(1f,.30f,.20f,1f)
        Matrix.setIdentityM(model,0); Matrix.translateM(model,0,0f,-.08f,0f); Matrix.rotateM(model,0,12f,1f,0f,0f)
        Matrix.scaleM(model,0,5.7f,.12f,7f); draw("box",listOf(.25f,.31f,.36f,1f),false,false)
        Matrix.setIdentityM(model,0); Matrix.rotateM(model,0,12f,1f,0f,0f)
        GLES20.glDepthMask(!p.pawlLoaded)
        draw("sedan",if(p.pawlLoaded) listOf(.64f,.67f,.69f,.42f) else listOf(.15f,.56f,.86f,1f),false,false)
        GLES20.glDepthMask(true)
        // Expanded mechanism alongside the vehicle; it is an explanatory diagram, not a measured load.
        val gearColor=if(p.pawlLoaded) red else listOf(.48f,.56f,.63f,1f)
        for(i in 0..23) {
            val a=i*Math.PI.toFloat()/12f
            val b=(i+1)*Math.PI.toFloat()/12f
            line(Vec3(1.45f,.58f+.30f*cos(a),-.35f+.30f*sin(a)),
                Vec3(1.45f,.58f+.30f*cos(b),-.35f+.30f*sin(b)),.035f,gearColor)
            if(i%2==0) box(1.45f,.58f+.34f*cos(a),-.35f+.34f*sin(a),.10f,.075f,.075f,gearColor)
        }
        box(1.45f,.98f,-.35f,.12f,.27f,.12f,gearColor)
        val clamp=if(p.parkingBrake==true) green else listOf(.31f,.35f,.38f,1f)
        box(1.20f,.32f,1.55f,.15f,.34f,.24f,clamp)
        box(1.20f,.42f,1.43f,.15f,.10f,.16f,clamp)
        val z=if(p.pawlLoaded) -.35f else 1.55f
        val x=if(p.pawlLoaded) 1.45f else 1.20f
        val color=if(p.pawlLoaded) red else green
        line(Vec3(x,2.2f,z),Vec3(x,1.13f,z),.045f,color)
        line(Vec3(x,1.13f,z),Vec3(x-.16f,1.38f,z),.045f,color)
        line(Vec3(x,1.13f,z),Vec3(x+.16f,1.38f,z),.045f,color)
    }

    private fun localBox(parent: FloatArray,x:Float,y:Float,z:Float,w:Float,h:Float,l:Float,color:List<Float>) {
        parent.copyInto(model); Matrix.translateM(model,0,x,y,z); Matrix.scaleM(model,0,w,h,l)
        draw("box",color,false,false)
    }

    private fun symbol(parent: FloatArray,letter: String,size: Float,color: List<Float>,ring: Boolean=false) {
        fun point(x:Float,y:Float):Vec3 {
            val out=FloatArray(4); Matrix.multiplyMV(out,0,parent,0,floatArrayOf(x*size,y*size,.017f,1f),0)
            return Vec3(out[0],out[1],out[2])
        }
        fun stroke(x:Float,y:Float,a:Float,b:Float) = line(point(x,y),point(a,b),.003f,color)
        stroke(-.28f,-.4f,-.28f,.4f)
        if(letter=="N") { stroke(-.28f,.4f,.28f,-.4f); stroke(.28f,-.4f,.28f,.4f) }
        else { stroke(-.28f,.4f,.27f,.4f); stroke(.27f,.4f,.27f,0f); stroke(.27f,0f,-.28f,0f) }
        if(ring) {
            for(i in 0..23) {
                val a=i*Math.PI.toFloat()/12; val b=(i+1)*Math.PI.toFloat()/12
                stroke(.66f*cos(a),.66f*sin(a),.66f*cos(b),.66f*sin(b))
            }
            for(start in listOf(-60f,120f)) for(i in 0..7) {
                val a=(start+i*15)*Math.PI.toFloat()/180; val b=(start+(i+1)*15)*Math.PI.toFloat()/180
                stroke(.91f*cos(a),.91f*sin(a),.91f*cos(b),.91f*sin(b))
            }
        }
    }

    private fun draw(name:String,tint:List<Float>,braking:Boolean,blinking:Boolean) {
        Matrix.multiplyMM(mvp,0,pv,0,model,0)
        GLES20.glUniformMatrix4fv(mvpLoc,1,false,mvp,0)
        GLES20.glUniformMatrix4fv(modelLoc,1,false,model,0)
        for (part in meshes.getValue(name)) {
            val p=part.data
            val color=if(p.tintable) tint else p.color.take(3)+tint[3]
            val lit=p.role=="unlit" || p.role=="brake" && braking || p.role=="indicator" && blinking
            val dim=if(p.role=="brake" && !braking || p.role=="indicator" && !blinking) .22f else 1f
            GLES20.glUniform4f(colorLoc,color[0]*dim,color[1]*dim,color[2]*dim,color[3])
            GLES20.glUniform1f(lightLoc,if(lit) 1f else 0f)
            part.buffer.position(0); GLES20.glVertexAttribPointer(position,3,GLES20.GL_FLOAT,false,24,part.buffer)
            part.buffer.position(3); GLES20.glVertexAttribPointer(normal,3,GLES20.GL_FLOAT,false,24,part.buffer)
            GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(normal)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,p.vertices.size/6)
        }
    }

    private fun fail() { if (ready || meshes.isNotEmpty()) ready=false; SceneDiagnostics.failures.incrementAndGet(); failed() }
}
