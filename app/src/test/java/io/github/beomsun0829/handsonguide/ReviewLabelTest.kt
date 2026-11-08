package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.signal.Signals
import io.github.beomsun0829.handsonguide.store.MemoryStore
import org.junit.Assert.*
import org.junit.Test

class ReviewLabelTest {
    @Test fun templatedCardKeepsWhatItSaidThroughSaveAndLoad() = EngineFixture(setOf("TELLTALE_AMBER")).use { f ->
        f.drive()
        f.emit(400, Signals.SPEED to 60, Signals.TT_AMBER to "TPMS")
        assertEquals("타이어 공기압", f.coach.currentDrive()!!.labels["TELLTALE_AMBER"])
        val record = f.coach.endDrive()!!
        f.store.saveDrive(record)
        val reloaded = MemoryStore().apply { load(f.store.toJson()) }
        assertEquals(mapOf("TELLTALE_AMBER" to "타이어 공기압"), reloaded.drives.single { it.startAt == record.startAt }.labels)
    }
}
