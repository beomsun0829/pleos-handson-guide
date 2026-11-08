package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.store.CardResult
import io.github.beomsun0829.handsonguide.store.DriveRecord
import io.github.beomsun0829.handsonguide.store.MemoryStore
import io.github.beomsun0829.handsonguide.store.SituationStat
import io.github.beomsun0829.handsonguide.store.preferencesOnly
import org.junit.Assert.*
import org.junit.Test

class PreferencesOnlyTest {
    @Test fun onlySettingsReachTheDisk() {
        val m = MemoryStore()
        m.settings = m.settings.copy(voiceOn = false)
        m.setCardResult("c4-gear", CardResult.CONFIRMED)
        m.setStat("TURN_PREP", SituationStat(count = 2))
        m.saveDrive(DriveRecord(startAt = 1, endAt = 2, tips = listOf("TURN_PREP")))
        val back = MemoryStore().apply { load(preferencesOnly(m.toJson())) }
        assertFalse(back.settings.voiceOn)
        assertNull(back.cardResult("c4-gear"))
        assertTrue(back.allStats().isEmpty())
        assertTrue(back.drives.isEmpty())
    }
}
