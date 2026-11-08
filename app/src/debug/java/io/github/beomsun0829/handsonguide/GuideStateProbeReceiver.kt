package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.signal.Signals
import io.github.beomsun0829.handsonguide.signal.Derivations
import io.github.beomsun0829.handsonguide.scene.SceneDiagnostics
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject
import org.json.JSONArray

/** Shell/DUMP-only observation. Has no mutation path and is absent from release builds. */
class GuideStateProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "io.github.beomsun0829.handsonguide.DEBUG_STATE") return
        val app = context.applicationContext as GuideApp
        val s = app.controller.signals.value
        val actual = app.controller.hub.realSignals.value
        val json = JSONObject().put("runtimeStarted", app.runtimeStarted).put("screen", app.controller.ui.value.screen.name)
            .put("storageState",app.storageState.value.name)
        listOf(Signals.NAV_CONNECTED, Signals.NAV_ACTIVE, Signals.NAV_ROUTING, Signals.NAV_TURN_TYPE,
            Signals.NAV_TURN_DIST, Signals.NAV_UNPROTECTED, Signals.NAV_SPEED_LIMIT,
            Signals.NAV_SCHOOL_ZONE_DISTANCE, Signals.NAV_REMAIN_DISTANCE, Signals.NAV_REMAIN_TIME, Signals.NAV_LOCATION_AT).forEach {
            json.put(it, s[it] ?: JSONObject.NULL)
        }
        // Presence proves delivery into SignalHub without logging coordinates or destination names.
        json.put("hasNavigationLocation", s.has(Signals.LATITUDE) && s.has(Signals.LONGITUDE))
        json.put("hasNavigationDestination", s.has(Signals.NAV_DESTINATION))
        json.put("hasNavigationCautions", s.has(Signals.NAV_CAUTIONS))
        json.put("signalAt", s.at).put("realSignalAt", actual.at)
        fun derivedSnapshot(signals: Signals) = JSONObject().apply {
            Derivations.OUTPUT_KEYS.sorted().forEach { put(it, signals[it] ?: JSONObject.NULL) }
        }
        json.put("derived", derivedSnapshot(s)).put("realDerived", derivedSnapshot(actual))
        json.put("gear", s.gear ?: JSONObject.NULL).put("speedKmh", s.num(Signals.SPEED) ?: JSONObject.NULL)
        // Vehicle values the practice checks read; null means the car did not report it.
        json.put("vehicle", JSONObject().apply {
            listOf(Signals.DOOR_DRIVER_OPEN, Signals.TRUNK_OPEN, Signals.FAN_DIRECTION, Signals.FAN, Signals.HVAC_AUTO, Signals.MIRROR_FOLDED, Signals.SEAT_FORE_AFT,
                Signals.PARKING_BRAKE, Signals.LIGHT_SWITCH, Signals.WIPER_SWITCH, Signals.DEFROST_FRONT, Signals.HAZARD, Signals.CRUISE)
                .forEach { put(it, actual[it]?.toString() ?: JSONObject.NULL) }
            put("unsupported", JSONArray((actual[Signals.UNSUPPORTED_FIELDS] as? Collection<*>)?.map { it.toString() }?.sorted().orEmpty()))
        })
        json.put("ignitionState",s.str(Signals.IGNITION) ?: JSONObject.NULL)
            .put("realIgnitionState",actual.str(Signals.IGNITION) ?: JSONObject.NULL)
        json.put("homeWarnings",JSONArray().apply { app.controller.ui.value.homeWarnings.forEach {
            put(JSONObject().put("type",it.type).put("red",it.red).put("collapsed",it.collapsed).put("revision",it.revision))
        } })
        json.put("displayIsDemo",s.isDemo).put("realIsDemo",actual.isDemo)
        json.put("speedReceiptAgeMs",s.receivedAt(Signals.SPEED)?.let { s.at-it } ?: JSONObject.NULL)
        fun driveSummary(d: io.github.beomsun0829.handsonguide.store.DriveRecord?): Any = d?.let {
            JSONObject().put("startAt",it.startAt).put("endAt",it.endAt)
                .put("source",it.source.name).put("km",it.km ?: JSONObject.NULL)
        } ?: JSONObject.NULL
        json.put("currentDrive",driveSummary(app.controller.coach.currentDrive()))
            .put("latestDrive",driveSummary(app.controller.store.drives.lastOrNull()))
        json.put("storedLevels",JSONObject().apply { app.controller.store.allStats().forEach { (id,stat) ->
            put(id,stat.level?.name ?: JSONObject.NULL)
        } })
        json.put("coachCard", app.controller.coach.ui.value.card?.situation?.id ?: JSONObject.NULL)
        json.put("coachQuiet", app.controller.coach.isQuiet).put("coachingOn", app.controller.store.settings.coachingOn)
        json.put("demoRunning", app.player.running.value ?: JSONObject.NULL).put("demoElapsed", app.player.elapsed.value)
        json.put("injectedKeys", JSONArray(app.controller.hub.overlayKeys().sorted()))
        json.put("scene",JSONObject().put("active",SceneDiagnostics.active.get()).put("frames",SceneDiagnostics.frames.get())
            .put("failures",SceneDiagnostics.failures.get()).put("id",SceneDiagnostics.scriptId ?: JSONObject.NULL)
            .put("elapsed",SceneDiagnostics.elapsed).put("phase",SceneDiagnostics.phaseIndex)
            .put("yaw",SceneDiagnostics.yaw).put("bounds",JSONArray(SceneDiagnostics.bounds))
            .put("fogOpacity",SceneDiagnostics.fogOpacity ?: JSONObject.NULL)
            .put("defrostButton",SceneDiagnostics.defrostButton ?: JSONObject.NULL))
        json.getJSONObject("scene").put("parkingFocus",SceneDiagnostics.parkingFocus ?: JSONObject.NULL)
            .put("parkingGear",SceneDiagnostics.parkingGear ?: JSONObject.NULL)
            .put("parkingBrake",SceneDiagnostics.parkingBrake ?: JSONObject.NULL)
            .put("footBrake",SceneDiagnostics.footBrake ?: JSONObject.NULL).put("parkingLive",SceneDiagnostics.parkingLive)
        json.put("parkingBrake",s.bool(Signals.PARKING_BRAKE) ?: JSONObject.NULL)
            .put("brakePressed",s.bool(Signals.BRAKE) ?: JSONObject.NULL)
            .put("tutorialSteps",app.controller.tutorial.ui.value.sequenceDone)
            .put("practicePaused",app.controller.tutorial.ui.value.practicePaused)
        json.put("defrostFront",s.bool(Signals.DEFROST_FRONT) ?: JSONObject.NULL)
        json.put("tutorialVerify",app.controller.tutorial.ui.value.verify.name)
        json.put("powerRequired",app.controller.tutorial.ui.value.powerRequired)
            .put("ignitionState",s.str(Signals.IGNITION) ?: JSONObject.NULL)
        val narration=app.controller.tutorial.ui.value.narration
        json.put("narration",narration?.let { JSONObject().put("run",it.run).put("sentence",it.sentence).put("finished",it.finished) } ?: JSONObject.NULL)
        json.put("situationCounts", JSONObject().apply {
            listOf("UNPROTECTED_LEFT_PREP", "SCHOOL_ZONE", "ROUNDABOUT_PREP", "UTURN_PREP", "HIGHWAY_EXIT_PREP", "TUNNEL_LIGHT").forEach {
                put(it, app.controller.store.stat(it).count)
            }
        })
        resultData = json.toString()
        Log.i("GuideStateProbe", json.toString())
    }
}
