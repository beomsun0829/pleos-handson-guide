package io.github.beomsun0829.handsonguide.scene

import org.json.JSONArray
import org.json.JSONObject

data class Vec3(val x: Float, val y: Float, val z: Float)
data class SceneCamera(val eye: Vec3, val target: Vec3, val fov: Float)
data class CarKey(val time: Float, val x: Float, val z: Float, val yaw: Float, val signal: Boolean, val brake: Boolean)
data class SceneCar(val id: String, val color: List<Float>, val keys: List<CarKey>) {
    fun sample(time: Float): CarPose {
        val left = keys.lastOrNull { it.time <= time } ?: keys.first()
        val right = keys.firstOrNull { it.time > time } ?: keys.last()
        val amount = if (right.time == left.time) 0f else ((time-left.time)/(right.time-left.time)).coerceIn(0f,1f)
        val smooth = amount*amount*(3-2*amount)
        fun mix(a: Float, b: Float) = a+(b-a)*smooth
        return CarPose(id, mix(left.x,right.x), mix(left.z,right.z), mix(left.yaw,right.yaw), color, left.signal, left.brake)
    }
}
data class CarPose(val id: String, val x: Float, val z: Float, val yaw: Float, val color: List<Float>, val signal: Boolean, val brake: Boolean)
data class ScenePhase(val start: Float, val end: Float, val correct: Boolean, val title: String, val caption: String, val cars: List<SceneCar>,
    val view: String? = null, val camera: SceneCamera? = null)
data class SceneFrame(val scriptId: String, val phaseIndex: Int, val phase: ScenePhase, val time: Float, val cars: List<CarPose>, val camera: SceneCamera,
    val fog: FogVisual? = null, val parking: ParkingVisual? = null)

data class SceneScript(val id: String, val model: String, val duration: Float, val camera: SceneCamera, val phases: List<ScenePhase>, val kind: String = "road") {
    fun sample(elapsed: Float): SceneFrame {
        val time = if (elapsed.isFinite()) ((elapsed % duration)+duration)%duration else 0f
        val index = phases.indexOfLast { time >= it.start }.coerceAtLeast(0)
        val phase = phases[index]
        return if (kind == "fog") FogTimeline.frame(this,index,time-phase.start)
        else if (kind == "parking") ParkingTimeline.frame(this,index,time-phase.start)
        else SceneFrame(id,index,phase,time-phase.start,phase.cars.map { it.sample(time-phase.start) },camera)
    }

    companion object {
        val cards = mapOf("c11-speed" to "lane-speed", "c11-wait" to "lane-wait", "c6-front" to "front-fog", "c4-park" to "parking-order")
        fun parse(text: String): SceneScript {
            val o = JSONObject(text)
            val kind = o.optString("sceneKind","road").also { require(it in setOf("road","fog","parking")) }
            fun finite(value: Double): Float { require(value.isFinite() && value in -10000.0..10000.0); return value.toFloat() }
            fun vec(a: JSONArray): Vec3 { require(a.length()==3); return Vec3(finite(a.getDouble(0)),finite(a.getDouble(1)),finite(a.getDouble(2))) }
            val c = o.getJSONObject("camera")
            val camera = SceneCamera(vec(c.getJSONArray("eye")),vec(c.getJSONArray("target")),finite(c.getDouble("fov")))
            require(camera.fov in 15f..90f && camera.eye != camera.target)
            fun shot(p: JSONObject): SceneCamera? = p.optJSONObject("camera")?.let {
                SceneCamera(vec(it.getJSONArray("eye")),vec(it.getJSONArray("target")),finite(it.getDouble("fov"))).also { camera ->
                    require(camera.fov in 15f..90f && camera.eye != camera.target)
                }
            }
            val a = o.getJSONArray("phases")
            val phases = (0 until a.length()).map { i ->
                val p = a.getJSONObject(i)
                val start = finite(p.getDouble("start")); val end = finite(p.getDouble("end"))
                require(end-start in 3f..6f)
                require(p.getString("kind") in setOf("correct","incorrect"))
                val objects = p.optJSONArray("cars") ?: JSONArray()
                val cars = (0 until objects.length()).map { j ->
                    val car = objects.getJSONObject(j)
                    val color = car.getJSONArray("color")
                    require(color.length()==4)
                    val rgba = (0..3).map { finite(color.getDouble(it)).also { v -> require(v in 0f..1f) } }
                    val keys = car.getJSONArray("keys")
                    val frames = (0 until keys.length()).map { k ->
                        val key = keys.getJSONObject(k)
                        CarKey(finite(key.getDouble("t")),finite(key.getDouble("x")),finite(key.getDouble("z")),
                            finite(key.optDouble("yaw",0.0)),key.optBoolean("signal"),key.optBoolean("brake"))
                    }
                    require(frames.size>=2 && frames.first().time==0f && frames.last().time==end-start)
                    require(frames.zipWithNext().all { (x,y) -> y.time>x.time })
                    SceneCar(car.getString("id"),rgba,frames)
                }
                require((kind != "road" && cars.isEmpty() || kind == "road" && cars.size in 2..8) && cars.map { it.id }.distinct().size==cars.size)
                if (p.getString("kind")=="incorrect") require(cars.all { it.color[3] < 1f })
                ScenePhase(start,end,p.getString("kind")=="correct",p.getString("title"),p.getString("caption"),cars,
                    p.optString("view").takeIf { it.isNotBlank() },shot(p))
            }
            val duration = finite(o.getDouble("duration"))
            require(phases.isNotEmpty() && phases.first().start==0f && phases.last().end==duration && duration>0)
            require(phases.zipWithNext().all { (x,y) -> x.end==y.start })
            phases.forEachIndexed { i,p -> if (!p.correct) require(phases.getOrNull(i+1)?.correct==true) }
            if (kind == "fog") {
                require(phases.map { it.view } == listOf("driver","controls","clear","practice"))
                require(phases.all { it.correct && it.camera != null })
            }
            if (kind == "parking") {
                require(phases.map { it.view } == listOf("stop","parking-brake","neutral","release","park","pawl-load","brake-load","practice"))
                require(phases.all { it.camera != null })
                require(phases.map { it.correct } == listOf(true,true,true,true,true,false,true,true))
            }
            val model = o.getString("model")
            require(model.matches(Regex("[a-z0-9-]+\\.glb")))
            return SceneScript(o.getString("id"),model,duration,camera,phases,kind)
        }
    }
}
