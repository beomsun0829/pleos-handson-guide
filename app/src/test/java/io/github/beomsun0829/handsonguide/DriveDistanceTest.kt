package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.coach.DriveDistance
import io.github.beomsun0829.handsonguide.signal.Signals
import io.github.beomsun0829.handsonguide.store.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DriveDistanceTest {
    private fun sample(at: Long, speed: Double?, received: Long = at, demo: Boolean = false, gear: String = "D") =
        Signals(mapOf(Signals.SPEED to speed,Signals.GEAR to gear,Signals.VEHICLE_CONNECTED to true),at,
            mapOf(Signals.SPEED to received),demo)

    @Test fun continuousSpeedSamplesEstimateDistanceWithTrapezoidalIntegration() {
        val meter = DriveDistance()
        meter.observe(sample(1000,0.0))
        meter.observe(sample(2000,36.0))
        meter.observe(sample(3000,36.0))
        meter.observe(sample(4000,0.0,gear="P"))
        assertEquals(.020,meter.km!!,.000001)
    }

    @Test fun repeatedCallbacksAndStoppedTimeDoNotAddDistance() {
        val meter = DriveDistance()
        meter.observe(sample(1000,36.0))
        repeat(5) { meter.observe(sample(1000,36.0)) }
        meter.observe(sample(2000,0.0))
        meter.observe(sample(3000,0.0))
        assertEquals(.005,meter.km!!,.000001)
    }

    @Test fun missingStaleInvalidOrDemoSpeedNeverBecomesACompleteDistance() {
        for (bad in listOf(sample(2000,null),sample(3000,36.0,received=1000),sample(2000,Double.NaN),
            sample(2000,Double.POSITIVE_INFINITY),sample(2000,-1.0),sample(2000,999.0),sample(2000,36.0,demo=true))) {
            val meter = DriveDistance()
            meter.observe(sample(1000,36.0)); meter.observe(bad)
            meter.observe(sample(4000,36.0)); meter.observe(sample(5000,36.0))
            assertNull("Must not present partial distance: ${bad.values}",meter.km)
        }
    }

    @Test fun clockJumpsAndLongObservationGapsInvalidateInsteadOfInventingDistance() {
        for (at in listOf(999L,100_000L)) {
            val meter = DriveDistance()
            meter.observe(sample(1000,36.0)); meter.observe(sample(at,36.0))
            meter.observe(sample(at+1000,36.0))
            assertNull(meter.km)
        }
    }

    @Test fun aSingleSampleDoesNotProveADistance() {
        val meter = DriveDistance()
        meter.observe(sample(1000,0.0))
        assertNull(meter.km)
    }

    @Test fun driveEndIncludesTheLastDecelerationAndPersistsTheEstimate() = EngineFixture(emptySet()).use { f ->
        f.emit(100,Signals.GEAR to "D",Signals.SPEED to 36.0)
        f.emit(1000,Signals.SPEED to 36.0)
        f.emit(1000,Signals.SPEED to 0.0,Signals.GEAR to "P")
        val drive = f.store.drives.single()
        assertEquals(.015,drive.km!!,.000001)
        assertEquals(DriveSource.REAL,drive.source)
        val restored = MemoryStore().apply { load(f.store.toJson()) }.drives.single()
        assertEquals(drive,restored)
    }

    @Test fun unchangedButFreshRealReceiptsKeepTheDistanceValidWhileTicksDoNotRefreshIt() = EngineFixture(emptySet()).use { f ->
        f.emit(100,Signals.GEAR to "D",Signals.SPEED to 36.0)
        f.emit(1000,Signals.SPEED to 36.0)
        f.emit(1000,Signals.SPEED to 36.0)
        assertEquals(.020,f.coach.currentDrive()!!.km!!,.000001)
        f.emit(1000)
        f.emit(1000) // Last speed receipt is now stale, despite fresh hub ticks.
        assertNull(f.coach.currentDrive()!!.km)
        f.emit(100,Signals.SPEED to 0.0,Signals.GEAR to "P")
        assertNull(f.store.drives.single().km)
    }

    @Test fun demoAndMixedTripsCannotSaveInjectedSpeedsAsRealKilometers() = EngineFixture(emptySet()).use { f ->
        f.hub.overlay(Signals.GEAR,"D",override=true)
        f.hub.overlay(Signals.SPEED,100.0,override=true)
        f.emit(100)
        assertTrue(f.hub.signals.value.isDemo)
        assertFalse(f.hub.realSignals.value.isDemo)
        f.emit(1000)
        assertEquals(DriveSource.DEMO,f.coach.currentDrive()!!.source)
        f.hub.clearOverlay(); f.emit(100)
        assertNull(f.store.drives.single().km)
        assertEquals(DriveSource.DEMO,f.store.drives.single().source)
        f.emit(100,Signals.GEAR to "D",Signals.SPEED to 36.0)
        f.emit(1000,Signals.SPEED to 36.0)
        f.hub.overlay(Signals.SPEED,100.0,override=true); f.emit(100)
        f.hub.clearOverlay(); f.emit(100,Signals.GEAR to "P",Signals.SPEED to 0.0)
        assertEquals(DriveSource.MIXED,f.store.drives.last().source)
        assertNull(f.store.drives.last().km)
    }

    @Test fun oldRecordsKeepUnknownDistanceAndSourceAndInvalidNumbersAreNotLoaded() {
        val store = MemoryStore().apply { load(JSONObject("""{"drives":[
            {"startAt":100},
            {"startAt":200,"km":-1,"source":"REAL"},
            {"startAt":300,"km":999,"source":"DEMO"},
            {"startAt":400,"km":1.25,"source":"REAL"}
        ]}""")) }
        assertEquals(DriveSource.UNKNOWN,store.drives[0].source)
        assertNull(store.drives[0].km); assertNull(store.drives[1].km); assertNull(store.drives[2].km)
        assertEquals(1.25,store.drives[3].km!!,0.0)
    }
}
