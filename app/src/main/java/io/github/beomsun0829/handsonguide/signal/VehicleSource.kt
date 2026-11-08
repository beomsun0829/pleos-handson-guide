package io.github.beomsun0829.handsonguide.signal

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.CruiseControl
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.WiperSwitch
import ai.pleos.playground.vehicle.constant.telltale.TelltaleColor
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.VehicleIgnitionState
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Reads real vehicle values into the [SignalHub]. Read-only: this class never calls a setter.
 *
 * Speed, gear, parking brake, outside temperature and night mode come from AAOS
 * CarPropertyManager (Pleos policy: use the platform API where one exists). Everything else
 * comes from the Pleos Vehicle SDK, polled because several values (wiper switch, light switch)
 * have no listener and polling keeps one simple failure path.
 */
class VehicleSource(private val context: Context, private val hub: SignalHub, private val scope: CoroutineScope,
    /** True while nothing on screen reacts to the car moment to moment. */
    private val relaxed: () -> Boolean = { false }) {
    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var props: CarPropertyManager? = null
    private var uxr: CarUxRestrictionsManager? = null
    private var pollJob: Job? = null
    @Volatile private var stopped = false
    private var connectionJob: Job? = null
    private val callbackThread = HandlerThread("guide-car-connection").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)
    private var seatArea = SeatArea(SeatValue.ROW_1_LEFT)
    // Auto, fan speed and fan direction can sit on other areas than A/C (the emulator's A/C area 0x75 is refused for them).
    private var autoArea: SeatArea? = null
    private var fanSpeedArea: SeatArea? = null
    private var fanDirectionArea: SeatArea? = null
    private var seatForeAftArea: SeatArea? = null
    private val front = WindowArea(WindowValue.FRONT_WINDSHIELD)
    private val rear = WindowArea(WindowValue.REAR_WINDSHIELD)
    private val failures = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val disabled = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val retryAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val propertyCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) = onProperty(value)
        override fun onErrorEvent(propId: Int, zone: Int) {
            keyFor(propId)?.let { hub.put(it, null) }
        }
    }

    fun start() {
        stopped = false
        try {
            vehicle = Vehicle(context).also { it.initialize() }
        } catch (e: Exception) {
            Log.w(TAG, "Vehicle SDK init failed", e)
        }
        try {
            car = Car.createCar(WorkerConnectionContext(context, callbackHandler), callbackHandler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
                // Car explicitly dispatches this lifecycle callback on Main even when
                // createCar() ran on IO. Property subscription is synchronous binder IPC.
                // Never subscribe inside this callback: a slow vehicle service froze Main
                // and prevented Gleo receivers and input events from being dispatched.
                connectionJob?.cancel()
                if (ready && !stopped) connectionJob = scope.launch(Dispatchers.IO) { onCarReady(c) } else {
                    props = null; uxr = null
                    hub.put(Signals.VEHICLE_CONNECTED, false)
                    hub.put(Signals.IGNITION, null)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Car service unavailable", e)
        }
        discoverSeatArea()
        pollJob = scope.launch(Dispatchers.IO) {
            var tick = 0
            while (isActive) {
                pollFast()
                if (tick % 4 == 0) pollSlow()
                tick++
                // Parked on a page that only shows things: a calmer pace. Any card, drive or movement is back to 0.4 s.
                delay(if (relaxed()) RELAXED_POLL_MS else POLL_MS)
            }
        }
    }

    fun stop() {
        stopped = true
        connectionJob?.cancel()
        pollJob?.cancel()
        try { props?.unregisterCallback(propertyCallback) } catch (_: Exception) {}
        try { uxr?.unregisterListener() } catch (_: Exception) {}
        try { vehicle?.release() } catch (_: Exception) {}
        try { car?.disconnect() } catch (_: Exception) {}
        callbackThread.quitSafely()
    }

    private fun onCarReady(c: Car) {
        if (stopped) return
        val p = c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        props = p
        hub.put(Signals.VEHICLE_CONNECTED, true)
        if (p != null) {
            subscribe(p, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_UI)
            subscribe(p, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            subscribe(p, VehiclePropertyIds.IGNITION_STATE, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            subscribe(p, VehiclePropertyIds.PARKING_BRAKE_ON, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            subscribe(p, VehiclePropertyIds.NIGHT_MODE, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            subscribe(p, VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        }
        try {
            val u = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            uxr = u
            u?.let {
                hub.put(Signals.UXR_DO, it.currentCarUxRestrictions.isRequiresDistractionOptimization)
                it.registerListener { r -> hub.put(Signals.UXR_DO, r.isRequiresDistractionOptimization) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "UX restrictions unavailable", e)
        }
    }

    private fun subscribe(p: CarPropertyManager, id: Int, rate: Float) {
        try {
            val ok = p.registerCallback(propertyCallback, id, rate)
            if (!ok) Log.i(TAG, "property $id not subscribable")
        } catch (e: Exception) {
            Log.i(TAG, "property $id unavailable: ${e.message}")
        }
    }

    private fun onProperty(value: CarPropertyValue<*>) {
        val key = keyFor(value.propertyId) ?: return
        if (value.status != CarPropertyValue.STATUS_AVAILABLE) { hub.put(key, null); return }
        val raw = value.value
        val mapped: Any? = when (value.propertyId) {
            VehiclePropertyIds.PERF_VEHICLE_SPEED -> (raw as? Float)?.let { kotlin.math.abs(it) * 3.6 }
            VehiclePropertyIds.GEAR_SELECTION -> gearName(raw as? Int)
            VehiclePropertyIds.IGNITION_STATE -> when (raw as? Int) {
                VehicleIgnitionState.LOCK -> "LOCK"
                VehicleIgnitionState.OFF -> "OFF"
                VehicleIgnitionState.ACC -> "ACC"
                VehicleIgnitionState.ON -> "ON"
                VehicleIgnitionState.START -> "START"
                else -> null
            }
            VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE -> (raw as? Float)?.toDouble()
            else -> raw
        }
        hub.put(key, mapped)
    }

    private fun keyFor(id: Int): String? = when (id) {
        VehiclePropertyIds.PERF_VEHICLE_SPEED -> Signals.SPEED
        VehiclePropertyIds.GEAR_SELECTION -> Signals.GEAR
        VehiclePropertyIds.IGNITION_STATE -> Signals.IGNITION
        VehiclePropertyIds.PARKING_BRAKE_ON -> Signals.PARKING_BRAKE
        VehiclePropertyIds.NIGHT_MODE -> Signals.NIGHT
        VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE -> Signals.OUTSIDE_TEMP
        else -> null
    }

    private fun discoverSeatArea() {
        val v = vehicle ?: return
        fun driverFirst(areas: Collection<SeatArea>) = areas.firstOrNull { SeatValue.ROW_1_LEFT in it.values } ?: areas.firstOrNull()
        fun failed(e: Exception) = Log.i(TAG, "HVAC area discovery failed: ${e.message}")
        try {
            val hvac = v.getHVAC()
            hvac.checkHvacAcCapabilities({ areas -> driverFirst(areas)?.let { seatArea = it } }, ::failed)
            hvac.checkHvacAutoCapabilities({ areas -> autoArea = driverFirst(areas) }, ::failed)
            hvac.checkHvacFanSpeedCapabilities({ areas -> fanSpeedArea = driverFirst(areas.keys) }, ::failed)
            hvac.checkHvacFanDirectionCapabilities({ areas -> fanDirectionArea = driverFirst(areas.keys) }, ::failed)
            v.getSeat().checkSeatForeAftCapabilities({ areas -> seatForeAftArea = driverFirst(areas.keys) }, ::failed)
        } catch (e: Exception) {
            Log.i(TAG, "HVAC capability check unavailable: ${e.message}")
        }
    }

    /** Every read goes through here so one unsupported value cannot spam errors. */
    private fun read(key: String, call: (onValue: (Any?) -> Unit, onError: (Exception) -> Unit) -> Unit) {
        if (stopped || key in disabled || System.currentTimeMillis() < (retryAt[key] ?: 0)) return
        try {
            call({ value ->
                if (stopped) return@call
                failures.remove(key)
                retryAt.remove(key)
                if (disabled.remove(key)) hub.put(Signals.UNSUPPORTED_FIELDS,disabled.toSet())
                hub.put(key, value)
            }, { e ->
                if (stopped) return@call
                hub.put(key, null)
                recordFailure(key, e)
            })
        } catch (e: Exception) {
            hub.put(key, null)
            recordFailure(key, e)
        }
    }

    private fun recordFailure(key: String, error: Exception) {
        if (error is SecurityException || error is UnsupportedOperationException) {
            disabled += key
            hub.put(Signals.UNSUPPORTED_FIELDS,disabled.toSet())
            Log.i(TAG, "unsupported $key: ${error.message}")
            return
        }
        val count = failures.merge(key, 1, Int::plus) ?: 1
        // SDK getters can fail while the service is binding or reconnecting. Such failures
        // must not permanently remove every capability from the current app process.
        if (count >= MAX_FAILURES) retryAt[key] = System.currentTimeMillis() + 5_000
        if (count == 1 || count == MAX_FAILURES) Log.i(TAG, "retry $key after service error: ${error.message}")
    }

    private fun pollFast() {
        val v = vehicle ?: return
        read(Signals.BRAKE) { ok, err -> v.getBrake().getCurrentBrakePedalStatus({ ok(it) }, { err(it) }) }
        read(Signals.STEERING) { ok, err -> v.getSteeringWheel().getCurrentSteeringWheelAngle({ ok(it?.toDouble()) }, { err(it) }) }
        read(Signals.TURN_SIGNAL) { ok, err -> v.getTurnSignal().getCurrentTurnSignalState({ ok(it?.name) }, { err(it) }) }
        read(Signals.CRUISE) { ok, err -> v.getDrivingMode().getCruiseControlState({ ok(cruiseName(it)) }, { err(it) }) }
        read(Signals.TELLTALES) { ok, err ->
            v.getSafety().getTelltalesInfo({ t ->
                ok(t?.telltales?.map { d ->
                    Telltale(d.type.name, d.state.name, TelltaleColor.entries.getOrNull(d.color)?.name ?: "GRAY")
                } ?: emptyList<Telltale>())
            }, { err(it) })
        }
    }

    private fun pollSlow() {
        val v = vehicle ?: return
        val hvac = v.getHVAC()
        val light = v.getLight()
        val seat = v.getSeat()
        read(Signals.WIPER_SWITCH) { ok, err -> v.getWiper().getCurrentWiperSwitch(front, { ok(wiperName(it)) }, { err(it) }) }
        read(Signals.WIPER_ON) { ok, err -> v.getWiper().getCurrentWiperState(front, { ok(it?.name == "ON") }, { err(it) }) }
        read(Signals.LIGHT_SWITCH) { ok, err -> light.getLowBeamLightSwitch({ ok(lightSwitchName(it)) }, { err(it) }) }
        read(Signals.LOW_BEAM) { ok, err -> light.getCurrentLowBeamLightState({ ok(it == LightState.ON) }, { err(it) }) }
        read(Signals.HIGH_BEAM) { ok, err -> light.getCurrentHighBeamLightState({ ok(it == LightState.ON) }, { err(it) }) }
        read(Signals.TAIL_LAMP) { ok, err -> light.getCurrentTailLampLightState({ ok(it == LightState.ON) }, { err(it) }) }
        read(Signals.FRONT_FOG) { ok, err -> light.getCurrentFrontFogLightState({ ok(it == LightState.ON) }, { err(it) }) }
        read(Signals.DEFROST_FRONT) { ok, err -> hvac.getHvacDefrosterState(front, { ok(it) }, { err(it) }) }
        read(Signals.DEFROST_REAR) { ok, err -> hvac.getHvacDefrosterState(rear, { ok(it) }, { err(it) }) }
        read(Signals.AC) { ok, err -> hvac.getHvacAcState(seatArea, { ok(it) }, { err(it) }) }
        read(Signals.RECIRC) { ok, err -> hvac.getHvacRecirculationState(seatArea, { ok(it) }, { err(it) }) }
        read(Signals.AUTO_RECIRC) { ok, err -> hvac.getHvacAutoRecirculationState(seatArea, { ok(it) }, { err(it) }) }
        read(Signals.HVAC_AUTO) { ok, err -> hvac.getHvacAutoState(autoArea ?: seatArea, { ok(it) }, { err(it) }) }
        read(Signals.FAN) { ok, err -> hvac.getHvacFanSpeed(fanSpeedArea ?: seatArea, { ok(it) }, { err(it) }) }
        read(Signals.FAN_DIRECTION) { ok, err -> hvac.getHvacFanDirection(fanDirectionArea ?: seatArea, { ok(it?.name) }, { err(it) }) }
        read(Signals.AIR_CLEANER) { ok, err -> hvac.getHvacAirCleanerState(seatArea, { ok(it) }, { err(it) }) }
        val driver = SeatArea(SeatValue.ROW_1_LEFT)
        val passenger = SeatArea(SeatValue.ROW_1_RIGHT)
        read(Signals.SEAT_HEAT) { ok, err -> seat.getHvacSeatTemperature(driver, { ok(it) }, { err(it) }) }
        read(Signals.BELT) { ok, err -> seat.getCurrentSeatBeltBuckledState(driver, { ok(it) }, { err(it) }) }
        read(Signals.OCCUPIED_PASSENGER) { ok, err -> seat.getCurrentSeatOccupancy(passenger, { ok(it) }, { err(it) }) }
        read(Signals.SEAT_FORE_AFT) { ok, err -> seat.getCurrentSeatForeAftPosition(seatForeAftArea ?: driver, { ok(it) }, { err(it) }) }
        read(Signals.SEAT_BACKREST) { ok, err -> seat.getCurrentSeatBackrestAnglePosition(driver, { ok(it) }, { err(it) }) }
        read(Signals.SEAT_HEIGHT) { ok, err -> seat.getCurrentSeatHeightPosition(driver, { ok(it) }, { err(it) }) }
        read(Signals.HEADREST_HEIGHT) { ok, err -> seat.getCurrentSeatHeadrestHeightAftPosition(driver, { ok(it) }, { err(it) }) }
        // Door position: 0 is shut, anything above is open.
        read(Signals.DOOR_DRIVER_OPEN) { ok, err -> v.getDoor().getCurrentDoorPosition(DoorArea(DoorValue.ROW_1_LEFT), { ok(it?.let { p -> p > 0 }) }, { err(it) }) }
        read(Signals.TRUNK_OPEN) { ok, err -> v.getDoor().getCurrentDoorPosition(DoorArea(DoorValue.REAR), { ok(it?.let { p -> p > 0 }) }, { err(it) }) }
        read(Signals.LOW_WASHER) { ok, err -> v.getSafety().hasLowWasherFluidWarning({ ok(it) }, { err(it) }) }
        read(Signals.CRUISE_TARGET) { ok, err -> v.getDrivingMode().getCruiseControlTargetSpeed({ ok(it?.toDouble()?.times(3.6)) }, { err(it) }) }
    }

    companion object {
        private const val TAG = "GuideVehicle"
        private const val POLL_MS = 400L
        private const val RELAXED_POLL_MS = 1_200L
        private const val MAX_FAILURES = 3

        fun gearName(raw: Int?): String? = when (raw) {
            null -> null
            4 -> "P"
            2 -> "R"
            1 -> "N"
            8 -> "D"
            else -> if (raw >= 16) "D" else "UNKNOWN"
        }

        fun cruiseName(c: CruiseControl?): String? = when (c) {
            null -> null
            CruiseControl.ACTIVATED -> "ACTIVATED"
            CruiseControl.ENABLED -> "ENABLED"
            CruiseControl.USER_OVERRIDE -> "OVERRIDE"
            CruiseControl.SUSPENDED -> "SUSPENDED"
            CruiseControl.FORCED_DEACTIVATION_WARNING -> "FORCED_OFF"
            else -> "OFF"
        }

        fun wiperName(w: WiperSwitch?): String? = when (w) {
            null -> null
            WiperSwitch.OFF -> "OFF"
            WiperSwitch.MIST -> "MIST"
            WiperSwitch.AUTO -> "AUTO"
            WiperSwitch.CONTINUOUS_LEVEL_1, WiperSwitch.CONTINUOUS_LEVEL_2 -> "LOW"
            WiperSwitch.CONTINUOUS_LEVEL_3, WiperSwitch.CONTINUOUS_LEVEL_4, WiperSwitch.CONTINUOUS_LEVEL_5 -> "HIGH"
            WiperSwitch.INTERMITTENT_LEVEL_1, WiperSwitch.INTERMITTENT_LEVEL_2, WiperSwitch.INTERMITTENT_LEVEL_3,
            WiperSwitch.INTERMITTENT_LEVEL_4, WiperSwitch.INTERMITTENT_LEVEL_5 -> "INT"
            else -> "OFF"
        }

        fun lightSwitchName(s: LightSwitch?): String? = when (s) {
            null -> null
            LightSwitch.OFF -> "OFF"
            LightSwitch.ON -> "ON"
            LightSwitch.DAYTIME_RUNNING -> "DRL"
            LightSwitch.AUTOMATIC -> "AUTO"
            else -> null
        }
    }
}
