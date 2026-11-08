package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.signal.Signals
import io.github.beomsun0829.handsonguide.signal.Telltale
import io.github.beomsun0829.handsonguide.scene.SceneScript
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import org.json.JSONObject

/** DUMP-only controls for bundled tests. Never commands the vehicle. */
class GuideDemoProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "io.github.beomsun0829.handsonguide.DEBUG_DEMO") return
        val pending = goAsync()
        val app = context.applicationContext as GuideApp
        app.scope.launch {
            val result = JSONObject()
            try {
                app.awaitReady()
                when (intent.getStringExtra("command")) {
                    "power_fixture" -> {
                        val s=app.controller.signals.value
                        val phase=intent.getStringExtra("name")
                        val busy=app.player.running.value!=null || app.controller.hub.overlayKeys().any { it!=Signals.IGNITION }
                        if(busy) result.put("status","fail").put("reason","another_demo")
                        else if(phase=="clear") { app.controller.hub.clearOverlay(); result.put("status","cleared") }
                        else if(!app.runtimeStarted || s.gear!="P" || s.num(Signals.SPEED)!=0.0 || s.bool(Signals.UXR_DO)==true)
                            result.put("status","fail").put("reason","not_parked")
                        else if(phase !in setOf("off","on","unknown")) result.put("status","fail").put("reason","unknown_fixture")
                        else {
                            app.controller.hub.overlay(Signals.IGNITION,when(phase) { "off" -> "OFF"; "on" -> "ON"; else -> null },override=true)
                            result.put("status","injected")
                        }
                    }
                    "warning_fixture" -> {
                        val s = app.controller.signals.value
                        val phase = intent.getStringExtra("name")
                        val busy = app.player.running.value != null || app.controller.hub.overlayKeys().any {
                            it !in setOf(Signals.TELLTALES,Signals.IGNITION)
                        }
                        if (busy) result.put("status","fail").put("reason","another_demo")
                        else if (phase == "clear") { app.controller.hub.clearOverlay(); result.put("status","cleared") }
                        else if (!app.runtimeStarted || s.gear != "P" || s.num(Signals.SPEED) != 0.0 || s.bool(Signals.UXR_DO) == true)
                            result.put("status","fail").put("reason","not_parked")
                        else if (phase !in setOf("tpms","red","two","off","on","none")) result.put("status","fail").put("reason","unknown_fixture")
                        else {
                            val lights = when (phase) {
                                "none" -> emptyList()
                                "tpms" -> listOf(Telltale("TPMS","ON","YELLOW"))
                                "red" -> listOf(Telltale("ENGINE_OIL_PRESSURE","ON","RED"))
                                else -> listOf(Telltale("TPMS","ON","YELLOW"),Telltale("ENGINE","ON","YELLOW"))
                            }
                            app.controller.hub.overlay(Signals.TELLTALES,lights,override=true)
                            app.controller.hub.overlay(Signals.IGNITION,if(phase == "off") "OFF" else "ON",override=true)
                            app.controller.show(Screen.HOME)
                            result.put("status","injected")
                        }
                    }
                    // Practice-signal fixture for card scenes: turn signal LEFT/RIGHT/OFF, or hazard on/off. "clear" removes it.
                    "signal_fixture" -> {
                        when (val v = intent.getStringExtra("name")) {
                            "clear" -> { app.controller.hub.clearOverlay(); result.put("status","cleared") }
                            "LEFT", "RIGHT", "OFF" -> { app.controller.hub.overlay(Signals.TURN_SIGNAL, v, override = true); result.put("status","injected") }
                            "hazard_on", "hazard_off" -> { app.controller.hub.overlay(Signals.HAZARD, v == "hazard_on", override = true); result.put("status","injected") }
                            // Reverse screen check: gear_R / gear_P, steer:<degrees> (positive is right).
                            "gear_R", "gear_P" -> { app.controller.hub.overlay(Signals.GEAR, v.removePrefix("gear_"), override = true); result.put("status","injected") }
                            // Any signal for screen checks: key=true|false|<number>|<text>, e.g. wiperOn=true, outsideTemp=-3.
                            else -> if (v != null && "=" in v && !v.startsWith("steer:")) {
                                val (k, raw) = v.split("=", limit = 2)
                                val value: Any = raw.toBooleanStrictOrNull() ?: raw.toDoubleOrNull() ?: raw
                                app.controller.hub.overlay(k, value, override = true); result.put("status","injected")
                            } else if (v != null && v.startsWith("steer:")) {
                                app.controller.hub.overlay(Signals.STEERING, v.removePrefix("steer:").toDouble(), override = true); result.put("status","injected")
                            } else result.put("status","fail").put("reason","unknown_fixture")
                        }
                    }
                    "open_card", "close_scene" -> {
                        val s=app.controller.signals.value
                        val id=intent.getStringExtra("name")
                        if(s.gear!="P" || s.num(Signals.SPEED)!=0.0 || s.bool(Signals.UXR_DO)==true) result.put("status","fail").put("reason","not_parked")
                        else if(intent.getStringExtra("command")=="close_scene") {
                            if(app.controller.tutorial.active) app.controller.tutorial.pause()
                            app.controller.show(Screen.HOME); result.put("status","closed")
                        }
                        else if(id == null || app.controller.content.card(id) == null) result.put("status","fail").put("reason","unknown_card")
                        else { app.controller.jumpCard(id!!); result.put("status","opened") }
                    }
                    // Screen tour for UI checks: home, settings, tips, tip:<situation id>, review (the newest drive with tips).
                    "open_screen" -> {
                        val c = app.controller
                        if (c.tutorial.active) c.tutorial.pause()
                        when (val v = intent.getStringExtra("name").orEmpty()) {
                            "home" -> c.show(Screen.HOME)
                            "settings" -> c.show(Screen.SETTINGS)
                            "onboarding" -> c.show(Screen.ONBOARDING)
                            // group:<title> opens a road tile on the home by its title.
                            else -> if (v.startsWith("group:")) c.guide.road.firstOrNull { it.title == v.removePrefix("group:") }?.let { c.openPlaylist(it) }
                        }
                        result.put("status", "shown")
                    }
                    "ask" -> { app.voiceInput.ask(intent.getStringExtra("name").orEmpty()); result.put("status", "asked") }
                    "stop" -> { app.player.stop(); result.put("status", "stopped") }
                    "start" -> {
                        val s = app.controller.signals.value
                        val scenario = app.scenarios.firstOrNull { it.name == intent.getStringExtra("name") }
                        if (!app.runtimeStarted || s.gear != "P" || s.num(Signals.SPEED) != 0.0 || s.bool(Signals.UXR_DO) == true) {
                            result.put("status", "fail").put("reason", "not_parked")
                        } else if (scenario == null) result.put("status", "fail").put("reason", "unknown_scenario")
                        else { app.player.play(scenario); result.put("status", "started") }
                    }
                    else -> result.put("status", "fail").put("reason", "unknown_command")
                }
            } catch (e: Exception) {
                result.put("status", "fail").put("reason", e.javaClass.simpleName)
            } finally {
                pending.resultData = result.toString()
                pending.finish()
            }
        }
    }
}
