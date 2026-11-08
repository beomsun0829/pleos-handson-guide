package io.github.beomsun0829.handsonguide.store

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Immutable strings cross the IO boundary; mutable JSONObject instances never enter DataStore. */
interface MetadataDisk {
    suspend fun read(): String
    suspend fun write(value: String)
}

class UnsupportedStoreVersion(version: Int) : IOException("Unsupported guide store version: $version")

object SnapshotLayout {
    const val VERSION = 3
    val files = listOf("progress.json", "coach.json", "drives.json")

    fun checkVersion(json: JSONObject) {
        val version = json.optInt("schemaVersion", -1)
        if (version > VERSION) throw UnsupportedStoreVersion(version)
        require(version == VERSION) { "Invalid store schema" }
        require(json.optString("generation").isNotBlank()) { "Missing store generation" }
    }

    fun snapshot(state: String): String = JSONObject().put("schemaVersion", VERSION)
        .put("generation", UUID.randomUUID().toString()).put("state", JSONObject(state)).toString()

    fun parseSnapshot(text: String): JSONObject = JSONObject(text).also {
        checkVersion(it)
        val state = it.getJSONObject("state")
        state.getJSONObject("cards"); state.getJSONObject("stats"); state.getJSONArray("drives")
        state.getJSONObject("settings"); state.getJSONArray("pending")
    }

    fun metadata(snapshot: JSONObject): String {
        val state = snapshot.getJSONObject("state")
        val paused = state.optString("pausedCard").takeUnless { it.isBlank() || it == "null" }
        return envelope(snapshot).apply {
            put("onboarding", JSONObject().put("completed", state.optBoolean("onboarded"))
                .put("experienced", state.optBoolean("experienced"))
                .put("at", state.opt("onboardingAt") ?: JSONObject.NULL))
            put("paused", paused?.let { JSONObject().put("cardId", it)
                .put("chapter", Regex("^c(\\d+)-").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: JSONObject.NULL) }
                ?: JSONObject.NULL)
            put("pendingCards", state.getJSONArray("pending"))
            put("settings", state.getJSONObject("settings"))
            listOf("basicsAnnounced", "firstDriveIntroduced", "quietUntil").forEach { put(it,state.opt(it)) }
        }.toString()
    }

    fun validateMetadata(text: String) = JSONObject(text).also {
        checkVersion(it)
        it.getJSONObject("onboarding"); it.getJSONObject("settings"); it.getJSONArray("pendingCards")
        require(it.isNull("paused") || it.optJSONObject("paused") != null)
    }

    fun part(snapshot: JSONObject, name: String): String = envelope(snapshot).apply {
        val state = snapshot.getJSONObject("state")
        when (name) {
            "progress.json" -> put("cards", state.getJSONObject("cards"))
            "coach.json" -> put("situations", state.getJSONObject("stats"))
            "drives.json" -> put("drives", state.getJSONArray("drives"))
            else -> error("Unknown store file")
        }
    }.toString()

    fun restoreMetadata(text: String): JSONObject {
        val meta = validateMetadata(text)
        val onboarding = meta.getJSONObject("onboarding")
        return JSONObject().put("schemaVersion", 2)
            .put("onboarded", onboarding.optBoolean("completed"))
            .put("experienced", onboarding.optBoolean("experienced"))
            .put("onboardingAt", onboarding.opt("at") ?: JSONObject.NULL)
            .put("pausedCard", meta.optJSONObject("paused")?.opt("cardId") ?: JSONObject.NULL)
            .put("pending", meta.getJSONArray("pendingCards")).put("settings",meta.getJSONObject("settings"))
            .also { state -> listOf("basicsAnnounced", "firstDriveIntroduced", "quietUntil").forEach { state.put(it,meta.opt(it)) } }
    }

    private fun envelope(snapshot: JSONObject) = JSONObject().put("schemaVersion", VERSION)
        .put("generation", snapshot.getString("generation"))
}

/** Same-directory atomic replacement after flushing bytes. A failed replacement leaves the old file. */
class SnapshotFiles(private val directory: File) {
    fun exists(name: String) = File(directory,name).exists()
    fun read(name: String): String? {
        val file = File(directory,name)
        if (!file.exists()) return null
        if (file.length() > 4 * 1024 * 1024) return null
        return file.readText(Charsets.UTF_8)
    }

    fun write(name: String, text: String) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create guide store directory")
        val target = File(directory,name)
        val temp = File(directory,"$name.tmp")
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8)); stream.fd.sync()
            }
            Files.move(temp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }

    fun delete(name: String) {
        val file = File(directory,name)
        if (file.exists() && !file.delete()) throw IOException("Cannot finish guide store transaction")
    }

    fun snapshot(name: String): JSONObject? = read(name)?.let { text ->
        try { SnapshotLayout.parseSnapshot(text) }
        catch (e: UnsupportedStoreVersion) { throw e }
        catch (_: IllegalArgumentException) { null }
        catch (_: org.json.JSONException) { null }
    }

    /** Used only by DataStore's corruption handler, before normal loading begins. */
    fun recoveryMetadata(): String = (snapshot("pending.json") ?: snapshot("committed.json"))
        ?.let(SnapshotLayout::metadata) ?: ""
}

data class DiskLoad(val state: String, val recovered: Boolean, val migrated: Boolean)

/**
 * One journal coordinates DataStore metadata and the three JSON files.
 * Acknowledged saves finish all files before retiring the journal. Restart replays an
 * interrupted transaction. The latest committed snapshot repairs later file corruption.
 */
class SnapshotDisk(
    private val files: SnapshotFiles,
    private val metadata: MetadataDisk,
    private val checkpoint: (String) -> Unit = {},
) {
    private val mutex = Mutex()

    suspend fun load(legacy: String?): DiskLoad = mutex.withLock {
        val pending = files.snapshot("pending.json")
        val brokenJournal = pending == null && files.exists("pending.json")
        pending?.let {
            commit(it)
            return@withLock DiskLoad(it.getJSONObject("state").toString(), recovered=true, migrated=false)
        }
        val metaText = metadata.read()
        val backup = files.snapshot("committed.json")
        if (metaText.isBlank()) {
            if (backup != null) {
                commit(backup)
                return@withLock DiskLoad(backup.getJSONObject("state").toString(),true,false)
            }
            val memory = MemoryStore()
            if (legacy != null) memory.load(JSONObject(legacy))
            val snapshot = SnapshotLayout.parseSnapshot(SnapshotLayout.snapshot(memory.toJson().toString()))
            commit(snapshot)
            return@withLock DiskLoad(snapshot.getJSONObject("state").toString(),false,legacy != null)
        }
        val meta = SnapshotLayout.validateMetadata(metaText)
        val state = SnapshotLayout.restoreMetadata(metaText)
        var recovered = brokenJournal
        for (name in SnapshotLayout.files) {
            val key = when(name) { "progress.json" -> "cards"; "coach.json" -> "situations"; else -> "drives" }
            val part = files.read(name)?.let {
                try { JSONObject(it).also(SnapshotLayout::checkVersion) }
                catch (e: UnsupportedStoreVersion) { throw e }
                catch (_: IllegalArgumentException) { null }
                catch (_: org.json.JSONException) { null }
            }
            val value = if (part?.optString("generation") == meta.getString("generation")) part.opt(key) else null
            val valid = if (name == "drives.json") value is JSONArray else value is JSONObject
            val stateKey = if(key == "situations") "stats" else key
            if (valid) state.put(stateKey,value)
            else {
                recovered = true
                val safeBackup = backup?.takeIf { it.getString("generation") == meta.getString("generation") }
                state.put(stateKey, safeBackup?.getJSONObject("state")?.opt(stateKey)
                    ?: if (name == "drives.json") JSONArray() else JSONObject())
            }
        }
        // Normalize recoverable individual entries using the same legacy-aware reader.
        val normalized = MemoryStore().apply { load(state) }.toJson().toString()
        if (recovered) commit(SnapshotLayout.parseSnapshot(SnapshotLayout.snapshot(normalized)))
        DiskLoad(normalized,recovered,false)
    }

    suspend fun save(state: String) = mutex.withLock {
        commit(SnapshotLayout.parseSnapshot(SnapshotLayout.snapshot(state)))
    }

    private suspend fun commit(snapshot: JSONObject) {
        val raw = snapshot.toString()
        files.write("pending.json",raw); checkpoint("journal")
        for (name in SnapshotLayout.files) { files.write(name,SnapshotLayout.part(snapshot,name)); checkpoint(name) }
        metadata.write(SnapshotLayout.metadata(snapshot)); checkpoint("metadata")
        files.write("committed.json",raw); checkpoint("committed")
        files.delete("pending.json"); checkpoint("finished")
    }
}
