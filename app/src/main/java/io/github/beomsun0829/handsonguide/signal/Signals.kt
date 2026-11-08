package io.github.beomsun0829.handsonguide.signal

/**
 * Immutable view of every vehicle/navigation value the app knows at one moment.
 * Keys are the field names from the spec (§10.1). A missing key or null value means
 * "unsupported or not received yet" and UI/conditions must treat it as unknown.
 */
class Signals(
    val values: Map<String, Any?>,
    val at: Long,
    private val receivedAt: Map<String, Long>? = null,
    /** Provenance belongs to the snapshot and cannot be forged by an injected value key. */
    val isDemo: Boolean = false,
) {
    operator fun get(key: String): Any? = values[key]
    fun bool(key: String): Boolean? = values[key] as? Boolean
    fun num(key: String): Double? = (values[key] as? Number)?.toDouble()
    fun str(key: String): String? = values[key]?.toString()
    fun has(key: String): Boolean = values[key] != null
    fun receivedAt(key: String): Long? = if (!has(key)) null else receivedAt?.get(key) ?: at.takeIf { receivedAt == null }

    val gear: String? get() = str(GEAR)
    val speedKmh: Double get() = num(SPEED) ?: 0.0
    val driveState: String get() = str(DRIVE_STATE) ?: PARKED
    val isMoving: Boolean get() = driveState == MOVING
    val inDrive: Boolean get() = gear == "D" || gear == "N" && speedKmh > 0.5

    companion object {
        val EMPTY = Signals(emptyMap(), 0L)

        const val SPEED = "speedKmh"
        const val GEAR = "gear"
        const val IGNITION = "ignitionState"
        const val PARKING_BRAKE = "parkingBrake"
        const val BRAKE = "brakePressed"
        const val STEERING = "steeringDeg"
        const val TURN_SIGNAL = "turnSignal"
        const val HAZARD = "hazard"
        const val WIPER_SWITCH = "wiperSwitch"
        const val WIPER_ON = "wiperOn"
        const val LOW_BEAM = "lowBeam"
        const val LIGHT_SWITCH = "lightSwitch"
        const val HIGH_BEAM = "highBeam"
        const val TAIL_LAMP = "tailLamp"
        const val FRONT_FOG = "frontFog"
        const val DEFROST_FRONT = "defrostFront"
        const val DEFROST_REAR = "defrostRear"
        const val AC = "acOn"
        const val RECIRC = "recirc"
        const val AUTO_RECIRC = "autoRecirc"
        const val HVAC_AUTO = "hvacAuto"
        const val FAN = "fanSpeed"
        const val FAN_DIRECTION = "fanDirection"
        const val TARGET_TEMP = "targetTempC"
        const val AIR_CLEANER = "airCleaner"
        const val SEAT_HEAT = "seatHeatDriver"
        const val BELT = "beltDriver"
        const val OCCUPIED_PASSENGER = "occupiedPassenger"
        const val SEAT_FORE_AFT = "seatForeAft"
        const val SEAT_BACKREST = "seatBackrest"
        const val SEAT_HEIGHT = "seatHeight"
        const val HEADREST_HEIGHT = "headrestHeight"
        const val MIRROR_FOLDED = "mirrorFolded"
        const val DOOR_DRIVER_OPEN = "doorDriverOpen"
        const val TRUNK_OPEN = "trunkOpen"
        const val CRUISE = "cruise"
        const val CRUISE_TARGET = "cruiseTargetKmh"
        const val TELLTALES = "telltales"
        const val LOW_WASHER = "lowWasher"
        const val OUTSIDE_TEMP = "outsideTempC"
        const val NIGHT = "nightMode"
        const val FUEL = "fuelPct"
        const val SOC = "socPct"
        const val RANGE = "rangeKm"
        const val UXR_DO = "uxrRequiresDO"
        const val NAV_ACTIVE = "navActive"
        const val NAV_CONNECTED = "navConnected"
        const val NAV_ROUTING = "navRouting"
        const val NAV_DESTINATION = "navDestination"
        const val NAV_REMAIN_DISTANCE = "navRemainDistance"
        const val NAV_REMAIN_TIME = "navRemainTime"
        const val NAV_ROUTE_ID = "navRouteId"
        const val NAV_ROUTE_INDEX = "navRouteIndex"
        const val NAV_UNPROTECTED = "navUnprotectedLeft"
        const val NAV_CAUTIONS = "navCautions"
        const val NAV_CAMERAS = "navCameras"
        const val NAV_SCHOOL_ZONE_DISTANCE = "navSchoolZoneDistance"
        const val NAV_ROAD_ADDRESS = "navRoadAddress"
        const val NAV_LOCATION_AT = "navLocationUpdatedAt"
        const val NAV_TURN_TYPE = "navNextTurnType"
        const val NAV_TURN_DIST = "navNextTurnDistance"
        const val NAV_TURN_DESC = "navNextTurnDesc"
        const val NAV_SPEED_LIMIT = "navSpeedLimit"
        const val NAV_TURN_PASSED = "navTurnPassed"
        const val VEHICLE_CONNECTED = "vehicleConnected"
        const val UNSUPPORTED_FIELDS = "unsupportedFields"
        const val CALL_ACTIVE = "callActive"
        const val GLEO_SPEAKING = "gleoSpeaking"
        const val NAV_SPOKEN_AT = "navSpokenAt"
        const val LATITUDE = "latitude"
        const val LONGITUDE = "longitude"

        // Derived (§10.2)
        const val DRIVE_STATE = "driveState"
        const val ACCEL = "accelMs2"
        const val HARD_BRAKE = "hardBrake"
        const val TURNING = "turning"
        const val IS_NIGHT = "isNight"
        const val RAIN_LIKELY = "rainLikely"
        const val RECIRC_MIN = "recircMinutes"
        const val DRIVING_MIN = "drivingMinutes"
        const val SPEED_OVER = "speedOverKmh"
        const val TT_RED = "ttRed"
        const val TT_AMBER = "ttAmber"

        const val PARKED = "PARKED"
        const val STOPPED = "STOPPED"
        const val MOVING = "MOVING"
    }
}

/** One instrument-cluster warning light as reported by the vehicle. */
data class Telltale(val type: String, val state: String, val color: String) {
    val isOn: Boolean get() = state == "ON" || state == "BLINK"
}
