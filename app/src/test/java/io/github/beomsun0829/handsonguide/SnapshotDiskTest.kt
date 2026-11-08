package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.store.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class SnapshotDiskTest {
    @get:Rule val temp = TemporaryFolder()
    private class Meta : MetadataDisk {
        var value = ""
        var fail = false
        override suspend fun read() = value
        override suspend fun write(value: String) {
            if (fail) throw IOException("Simulated full disk")
            this.value = value
        }
    }
    private fun state(id: String = "c1-belt") = MemoryStore { 1000L }.apply {
        onboarded = true; experienced = true; pausedCard = "c11-speed"
        setCardResult(id,CardResult.CONFIRMED)
        settings = Settings(theme=ThemeMode.LIGHT)
        setStat("s",SituationStat(count=3,lastAt=1000))
        saveDrive(DriveRecord(500,1000,tips=listOf("s")))
    }.toJson().toString()

    private fun assertCard(state: String, id: String) {
        val mem = MemoryStore().apply { load(JSONObject(state)) }
        assertEquals(CardResult.CONFIRMED,mem.cardResult(id))
        assertEquals(1000L,mem.cardRecordedAt(id))
    }

    @Test fun migratesExistingRecordsIntoDataStoreMetadataAndThreeSeparateFiles() = runBlocking {
        val files = SnapshotFiles(temp.newFolder())
        val meta = Meta()
        val load = SnapshotDisk(files,meta).load(state())
        assertTrue(load.migrated)
        assertCard(load.state,"c1-belt")
        val metadata = SnapshotLayout.validateMetadata(meta.value)
        assertFalse(metadata.has("cards")); assertFalse(metadata.has("drives"))
        assertTrue(metadata.getJSONObject("onboarding").getBoolean("experienced"))
        assertEquals(11,metadata.getJSONObject("paused").getInt("chapter"))
        assertEquals("c11-speed",metadata.getJSONObject("paused").getString("cardId"))
        assertEquals("LIGHT",metadata.getJSONObject("settings").getString("theme"))
        assertNotNull(files.read("progress.json")); assertNotNull(files.read("coach.json")); assertNotNull(files.read("drives.json"))
        assertNull(files.read("pending.json"))
        assertCard(SnapshotDisk(files,meta).load(null).state,"c1-belt")
    }

    @Test fun restartFinishesEveryInterruptedCommitStage() = runBlocking {
        for (stage in listOf("journal","progress.json","coach.json","drives.json","metadata","committed","finished")) {
            val files = SnapshotFiles(temp.newFolder())
            val meta = Meta()
            SnapshotDisk(files,meta).load(state("old"))
            try {
                SnapshotDisk(files,meta) { if (it == stage) throw IOException("Process died at $it") }.save(state("new"))
                fail("No simulated interruption at $stage")
            } catch (_: IOException) { }
            val restored = SnapshotDisk(files,meta).load(null)
            assertCard(restored.state,"new")
            assertNull(files.read("pending.json"))
            assertEquals(3,JSONObject(meta.value).getInt("schemaVersion"))
        }
    }

    @Test fun laterJsonCorruptionRecoversTheLatestCommittedRecordWithoutLosingOtherSections() = runBlocking {
        for (name in SnapshotLayout.files) {
            val files = SnapshotFiles(temp.newFolder())
            val meta = Meta()
            SnapshotDisk(files,meta).load(state())
            files.write(name,"{broken")
            val restored = SnapshotDisk(files,meta).load(null)
            assertTrue(restored.recovered)
            assertCard(restored.state,"c1-belt")
            val mem = MemoryStore().apply { load(JSONObject(restored.state)) }
            assertEquals(3,mem.stat("s").count)
            assertEquals(listOf("s"),mem.drives.single().tips)
            assertNotNull(JSONObject(files.read(name)!!))
        }
    }

    @Test fun missingMetadataCanBeRebuiltFromCommittedSnapshotWithoutResurrectingLegacyRecords() = runBlocking {
        val files = SnapshotFiles(temp.newFolder())
        val meta = Meta()
        SnapshotDisk(files,meta).load(state("current"))
        meta.value = ""
        val restored = SnapshotDisk(files,meta).load(state("old-legacy"))
        assertTrue(restored.recovered)
        assertCard(restored.state,"current")
        assertNull(MemoryStore().apply { load(JSONObject(restored.state)) }.cardResult("old-legacy"))
    }

    @Test fun transientMetadataWriteFailureKeepsJournalAndAcceptsANewerSnapshotOnRetry() = runBlocking {
        val files = SnapshotFiles(temp.newFolder())
        val meta = Meta()
        val disk = SnapshotDisk(files,meta)
        disk.load(state("old"))
        meta.fail = true
        try { disk.save(state("failed")); fail("Expected IO failure") } catch (_: IOException) { }
        assertNotNull(files.read("pending.json"))
        meta.fail = false
        disk.save(state("newer"))
        assertCard(SnapshotDisk(files,meta).load(null).state,"newer")
        assertNull(files.read("pending.json"))
    }

    @Test fun interruptedClearCannotResurrectPreClearHistory() = runBlocking {
        val files = SnapshotFiles(temp.newFolder())
        val meta = Meta()
        SnapshotDisk(files,meta).load(state("old"))
        val empty = MemoryStore().toJson().toString()
        try {
            SnapshotDisk(files,meta) { if(it == "progress.json") throw IOException("Stop during clear") }.save(empty)
        } catch (_: IOException) { }
        val restored = MemoryStore().apply { load(JSONObject(SnapshotDisk(files,meta).load(state("old")).state)) }
        assertFalse(restored.onboarded); assertNull(restored.cardResult("old"))
        assertTrue(restored.allStats().isEmpty()); assertTrue(restored.drives.isEmpty())
        assertEquals(Settings(),restored.settings)
        assertFalse(files.read("committed.json")!!.contains("\"old\""))
    }

    @Test fun futureSchemaIsNotSilentlyReplacedWithDefaults() = runBlocking {
        val files = SnapshotFiles(temp.newFolder())
        val meta = Meta()
        SnapshotDisk(files,meta).load(state())
        val before = files.read("progress.json")!!
        val future = JSONObject(before).put("schemaVersion",999).toString()
        files.write("progress.json",future)
        try { SnapshotDisk(files,meta).load(null); fail("Expected unsupported version") }
        catch (_: UnsupportedStoreVersion) { }
        assertEquals(future,files.read("progress.json"))
    }

    @Test fun wrongGenerationIsRepairedRatherThanMixingSnapshots() = runBlocking {
        val files = SnapshotFiles(temp.newFolder())
        val meta = Meta()
        SnapshotDisk(files,meta).load(state("current"))
        val old = SnapshotLayout.parseSnapshot(SnapshotLayout.snapshot(state("stale")))
        files.write("progress.json",SnapshotLayout.part(old,"progress.json"))
        val restored = SnapshotDisk(files,meta).load(null)
        assertTrue(restored.recovered)
        assertCard(restored.state,"current")
    }

    @Test fun brokenJournalIsRetiredAfterRecoveringTheCommittedState() = runBlocking {
        val files = SnapshotFiles(temp.newFolder())
        val meta = Meta()
        SnapshotDisk(files,meta).load(state("current"))
        files.write("pending.json","{incomplete journal")
        val restored = SnapshotDisk(files,meta).load(null)
        assertTrue(restored.recovered)
        assertCard(restored.state,"current")
        assertNull(files.read("pending.json"))
    }

    @Test fun realDataStoreRestartsWithCommittedMetadata() = runBlocking {
        val directory = temp.newFolder()
        val files = SnapshotFiles(File(directory,"guide"))
        val path = File(directory,"metadata.json")
        suspend fun session(block: suspend (MetadataDisk) -> Unit) {
            val job = SupervisorJob()
            val data = DataStoreFactory.create(serializer=GuideMetadataSerializer,
                corruptionHandler=ReplaceFileCorruptionHandler { files.recoveryMetadata() },
                scope=CoroutineScope(job + Dispatchers.IO), produceFile={path})
            try {
                block(object : MetadataDisk {
                    override suspend fun read() = data.data.first()
                    override suspend fun write(value: String) { data.updateData { value } }
                })
            } finally { job.cancelAndJoin() }
        }
        session { SnapshotDisk(files,it).load(state()) }
        session { assertCard(SnapshotDisk(files,it).load(null).state,"c1-belt") }
        // The Android FileStorage rename operation cannot overwrite existing files on
        // Windows. Exercise actual corruption-handler writes on the Android emulator
        // in validate-beginner-store-upgrade.py, using the production filesystem.
    }

    @Test fun serializerRejectsCorruptionButPropagatesUnsupportedVersions() = runBlocking {
        try { GuideMetadataSerializer.readFrom(ByteArrayInputStream("broken".toByteArray())); fail("Expected corruption") }
        catch (_: CorruptionException) { }
        val meta = SnapshotLayout.metadata(SnapshotLayout.parseSnapshot(SnapshotLayout.snapshot(state())))
        val output = ByteArrayOutputStream()
        GuideMetadataSerializer.writeTo(meta,output)
        assertEquals(meta,GuideMetadataSerializer.readFrom(ByteArrayInputStream(output.toByteArray())))
        val future = JSONObject(meta).put("schemaVersion",999).toString()
        try { GuideMetadataSerializer.readFrom(ByteArrayInputStream(future.toByteArray())); fail("Expected future version") }
        catch (_: UnsupportedStoreVersion) { }
    }
}
