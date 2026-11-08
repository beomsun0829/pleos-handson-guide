package io.github.beomsun0829.handsonguide.home

import io.github.beomsun0829.handsonguide.signal.Signals
import io.github.beomsun0829.handsonguide.signal.WarningLights

data class HomeWarning(val type: String, val red: Boolean, val revision: Long, val collapsed: Boolean)

/** Acknowledgements belong to this observed ignition session, not the tutorial history. */
class HomeWarnings {
    private val acknowledged = HashSet<Pair<String,Boolean>>()
    private var known = emptySet<Pair<String,Boolean>>()
    private var revision = 0L
    private var ignitionOffObserved = false
    private var current = emptyList<HomeWarning>()

    fun update(s: Signals): List<HomeWarning> {
        when (s.str(Signals.IGNITION)) {
            "OFF", "LOCK", "ACC" -> ignitionOffObserved = true
            "START", "ON" -> if (ignitionOffObserved) {
                acknowledged.clear(); revision++; ignitionOffObserved=false
            }
        }
        val observed = WarningLights.observed(s)
        if (observed == null) { current=emptyList(); return current }
        val lights = observed.sortedBy { if(it.color == "RED") 0 else 1 }.distinctBy { it.type }
        val keys = lights.map { it.type to (it.color == "RED") }.toSet()
        if (keys != known) { revision++; known=keys }
        acknowledged.retainAll(keys)
        current = lights.map { light ->
            val key = light.type to (light.color == "RED")
            HomeWarning(key.first,key.second,revision,key in acknowledged)
        }
        return current
    }

    /** A late click must not acknowledge a warning set the driver has not seen. */
    fun setCollapsed(expected: HomeWarning, collapsed: Boolean): List<HomeWarning>? {
        if (expected.revision != revision || current.none { it.type == expected.type && it.red == expected.red }) return null
        val key = expected.type to expected.red
        if (collapsed) acknowledged.add(key) else acknowledged.remove(key)
        current = current.map { it.copy(collapsed = (it.type to it.red) in acknowledged) }
        return current
    }
}
