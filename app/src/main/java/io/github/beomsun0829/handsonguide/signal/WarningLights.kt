package io.github.beomsun0829.handsonguide.signal

/** The parked panel and driving coach share the same warning/indicator exclusions. */
object WarningLights {
    fun active(list: List<Telltale>, state: String): List<Telltale> = list.filter {
        it.isOn && it.color in setOf("RED","YELLOW") && it.type !in INDICATORS &&
            !(state == Signals.PARKED && it.type in PARKED_NORMAL) && it.type != "SEAT_BELT"
    }

    fun observed(s: Signals): List<Telltale>? {
        val list = s[Signals.TELLTALES] as? List<*>
        if (list != null) return active(list.filterIsInstance<Telltale>(),s.driveState)
        val derived = listOfNotNull(
            s.str(Signals.TT_RED)?.let { Telltale(it,"ON","RED") },
            s.str(Signals.TT_AMBER)?.let { Telltale(it,"ON","YELLOW") },
        )
        return derived.takeIf { it.isNotEmpty() }?.let { active(it,s.driveState) }
    }

    private val INDICATORS = setOf(
        "TURN_SIGNAL_LEFT", "TURN_SIGNAL_RIGHT", "HEADLAMP_LOW", "HEADLAMP_HIGH", "POSITION_LAMP", "FRONT_FOG_LAMP",
        "REAR_FOG_LAMP", "AUTO_HOLD", "AUTO_HIGH_BEAM", "LED_HEADLAMP", "XEV_READY", "ISG", "I_PEDAL", "HEV_MODE",
        "DRIVE_MODE", "DRIVE_MODE_SPORT", "DRIVE_MODE_ECO", "DRIVE_MODE_COMFORT", "DRIVE_MODE_TERRAIN", "ESC_SPORT",
        "LFA", "LKA", "ICC", "LCA_LEFT", "LCA_RIGHT", "FCA", "ELK", "DRIVER_ATTENTION", "PASSENGER_AIRBAG_OFF",
        "PASSENGER_AIRBAG_ON", "EV_CHARGING_CONNECTOR", "EV_BATTERY_CONDITIONING", "CREEP_OFF", "GREEN_ZONE",
    )
    private val PARKED_NORMAL = setOf("PARKING_BRAKE", "ELECTRONIC_PARKING_BRAKE", "DOOR_OPEN", "TRUNK_OPEN")
}
