package io.github.beomsun0829.handsonguide.signal

/** SDK-independent navigation input; distances are metres, times are seconds. */
data class NavTurn(val type: String, val distance: Double, val description: String)
data class NavCaution(val id: String, val type: String, val distance: Int)
data class NavCamera(val id: String, val type: String, val distance: Int, val speedLimit: Int,
    val active: Boolean, val overSpeed: Boolean)

/** Maintains unknown values and expires stale guidance instead of treating it as current. */
class NavigationState {
    private var connected = false
    private var active: Boolean? = null
    private var routing = false
    private var activityAt = Long.MIN_VALUE
    private var turns: List<NavTurn> = emptyList()
    private var turnsAt = Long.MIN_VALUE
    private var cautions: List<NavCaution> = emptyList()
    private var cautionsAt = Long.MIN_VALUE
    private var cameras: List<NavCamera> = emptyList()
    private var camerasAt = Long.MIN_VALUE
    private var limit: Int? = null
    private var limitAt = Long.MIN_VALUE
    private var latitude: Double? = null
    private var longitude: Double? = null
    private var address: String? = null
    private var routeId: String? = null
    private var routeIndex: Int? = null
    private var locationAt = Long.MIN_VALUE
    private var destination: String? = null
    private var remainDistance: Int? = null
    private var remainTime: Int? = null
    private var destinationAt = Long.MIN_VALUE

    fun connection(value: Boolean) {
        connected = value
        if (!value) {
            active = null; routing = false; clearRoute()
            cautions = emptyList(); cameras = emptyList(); limit = null
            latitude = null; longitude = null; address = null; routeId = null; routeIndex = null
            activityAt = Long.MIN_VALUE; turnsAt = Long.MIN_VALUE; cautionsAt = Long.MIN_VALUE
            camerasAt = Long.MIN_VALUE; limitAt = Long.MIN_VALUE; locationAt = Long.MIN_VALUE; destinationAt = Long.MIN_VALUE
        }
    }

    fun status(status: String, now: Long) {
        active = when (status) {
            "ROUTE_DRIVE" -> true
            "INACTIVE", "INITIALIZING", "FREE_DRIVE" -> false
            else -> null
        }
        activityAt = now
        if (active != true) clearRoute()
    }

    fun routeState(mode: String, isRouting: Boolean, now: Long) {
        routing = isRouting
        active = when (mode) {
            "ROUTE_GUIDING", "SIMULATION_GUIDING" -> true
            "NOT_WORKING", "SAFE_GUIDING" -> false
            else -> null
        }
        activityAt = now
        if (active != true) clearRoute()
    }

    fun routeChanging(now: Long) { routing = true; active = null; activityAt = now; clearRoute() }
    fun routeCancelled(now: Long) { active = false; routing = false; activityAt = now; clearRoute() }

    private fun clearRoute() {
        turns = emptyList(); destination = null; remainDistance = null; remainTime = null
        routeId = null; routeIndex = null
    }

    fun turns(list: List<NavTurn>, now: Long) {
        turns = if (active == true) list.filter { it.distance.isFinite() && it.distance >= 0 } else emptyList()
        turnsAt = now
    }
    fun cautions(list: List<NavCaution>, now: Long) {
        cautions = list.filter { it.distance >= 0 }.distinctBy { it.id to it.type }; cautionsAt = now
    }
    fun cameras(list: List<NavCamera>, now: Long) {
        cameras = list.filter { it.distance >= 0 }.distinctBy { it.id to it.type }; camerasAt = now
    }
    fun speedLimit(value: Int, now: Long) { limit = value.takeIf { it in 1..200 }; limitAt = now }

    fun location(lat: Double, lon: Double, roadAddress: String, id: String?, index: Int?, now: Long) {
        val valid = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
        latitude = lat.takeIf { valid }; longitude = lon.takeIf { valid }
        address = roadAddress.takeIf { valid && it.isNotBlank() }
        routeId = id?.takeIf { active == true && it.isNotBlank() }
        routeIndex = index?.takeIf { active == true && it >= 0 }
        locationAt = now
    }

    fun destination(name: String, distance: Int, seconds: Int, now: Long) {
        if (active != true) return
        destination = name.takeIf { it.isNotBlank() }
        remainDistance = distance.takeIf { it >= 0 }; remainTime = seconds.takeIf { it >= 0 }
        destinationAt = now
    }

    fun snapshot(now: Long): Map<String, Any?> {
        fun fresh(at: Long, ttl: Long) = at != Long.MIN_VALUE && now >= at && now - at <= ttl
        val routeActive = active.takeIf { connected && fresh(activityAt, ROUTE_TTL) }
        val routeReady = routeActive == true
        val turn = turns.takeIf { routeReady && fresh(turnsAt, LIVE_TTL) }?.minByOrNull { it.distance }
        val nearCautions = cautions.takeIf { connected && fresh(cautionsAt, LIVE_TTL) }
        val nearCameras = cameras.takeIf { connected && fresh(camerasAt, LIVE_TTL) }
        val locationFresh = connected && fresh(locationAt, LIVE_TTL)
        val destinationFresh = routeReady && fresh(destinationAt, ROUTE_TTL)
        return mapOf(
            Signals.NAV_CONNECTED to connected,
            Signals.NAV_ACTIVE to routeActive,
            Signals.NAV_ROUTING to (routing && connected),
            Signals.NAV_TURN_TYPE to turn?.let { normalizeTurn(it.type) },
            Signals.NAV_TURN_DIST to turn?.distance,
            Signals.NAV_TURN_DESC to turn?.description?.takeIf { it.isNotBlank() },
            Signals.NAV_UNPROTECTED to turn?.let { normalizeTurn(it.type) == "LEFT" && it.description.contains("비보호") },
            Signals.NAV_SPEED_LIMIT to limit.takeIf { connected && fresh(limitAt, ROUTE_TTL) },
            Signals.NAV_CAUTIONS to nearCautions,
            Signals.NAV_CAMERAS to nearCameras,
            Signals.NAV_SCHOOL_ZONE_DISTANCE to nearCautions?.filter { it.type == "SCHOOL_ZONE" }?.minOfOrNull { it.distance },
            Signals.LATITUDE to latitude.takeIf { locationFresh },
            Signals.LONGITUDE to longitude.takeIf { locationFresh },
            Signals.NAV_ROAD_ADDRESS to address.takeIf { locationFresh },
            Signals.NAV_LOCATION_AT to locationAt.takeIf { locationFresh },
            Signals.NAV_ROUTE_ID to routeId.takeIf { routeReady && locationFresh },
            Signals.NAV_ROUTE_INDEX to routeIndex.takeIf { routeReady && locationFresh },
            Signals.NAV_DESTINATION to destination.takeIf { destinationFresh },
            Signals.NAV_REMAIN_DISTANCE to remainDistance.takeIf { destinationFresh },
            Signals.NAV_REMAIN_TIME to remainTime.takeIf { destinationFresh },
        )
    }

    companion object {
        const val LIVE_TTL = 10_000L
        const val ROUTE_TTL = 15_000L
        fun normalizeTurn(type: String): String = when {
            type in setOf("TURN_LEFT", "TURN_SHARP_L") -> "LEFT"
            type in setOf("TURN_RIGHT", "TURN_SHARP_R") -> "RIGHT"
            type == "TURN_UTURN" -> "UTURN"
            type == "TURN_DEVIDE_MERGE" -> "MERGE"
            type.startsWith("TURN_ENTER_IC_") -> "HIGHWAY_ENTER"
            type.startsWith("TURN_EXIT_IC_") -> "HIGHWAY_EXIT"
            type.startsWith("TURN_ENTER_ROUNDABOUT_") -> "ROUNDABOUT"
            type == "TURN_EXIT_ROUNDABOUT" -> "ROUNDABOUT_EXIT"
            type == "TURN_KEEP_L" -> "KEEP_LEFT"
            type == "TURN_KEEP_R" -> "KEEP_RIGHT"
            type == "TURN_TUNNEL" -> "TUNNEL"
            type == "TURN_TOLL" -> "TOLL"
            type == "TURN_ENTER_SERVICEAREA" || type == "TURN_RESTAREA" -> "REST_AREA"
            type == "TURN_ARRIVE" -> "ARRIVE"
            type == "TURN_WAY" -> "WAYPOINT"
            type == "TURN_STRAIGHT" -> "STRAIGHT"
            else -> "UNKNOWN"
        }
    }
}
