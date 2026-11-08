package io.github.beomsun0829.handsonguide.store

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

enum class StoreState { LOADING, READY, RECOVERED, READ_ERROR, WRITE_ERROR }

/** Core DataStore accepts immutable JSON metadata strings, validated before each read/write. */
object GuideMetadataSerializer : Serializer<String> {
    override val defaultValue = ""
    override suspend fun readFrom(input: InputStream): String {
        val text = input.readBytes().toString(Charsets.UTF_8)
        if (text.isEmpty()) throw CorruptionException("Empty guide metadata")
        try { SnapshotLayout.validateMetadata(text) }
        catch (e: UnsupportedStoreVersion) { throw e }
        catch (e: Exception) { throw CorruptionException("Invalid guide metadata",e) }
        return text
    }
    override suspend fun writeTo(t: String, output: OutputStream) {
        if (t.isNotEmpty()) SnapshotLayout.validateMetadata(t)
        output.write(t.toByteArray(Charsets.UTF_8))
    }
}

/**
 * The engine owns MemoryStore. Only immutable snapshots are sent to IO, in save order.
 * Initialization must finish before controllers can read or mutate persisted state.
 */
class FileStore private constructor(
    private val memory: MemoryStore,
    private val disk: SnapshotDisk,
    private val readLegacy: () -> String?,
    private val clearLegacy: () -> Unit,
) : Store by memory {
    private val _state = MutableStateFlow(StoreState.LOADING)
    val state: StateFlow<StoreState> = _state
    private val saveMutex = Mutex()
    private var initialized = false

    suspend fun initialize() {
        if (initialized) return
        try {
            val loaded = withContext(Dispatchers.IO) { disk.load(readLegacy()).also { clearLegacy() } }
            memory.load(preferencesOnly(org.json.JSONObject(loaded.state)))
            initialized = true
            _state.value = if (loaded.recovered) StoreState.RECOVERED else StoreState.READY
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _state.value = StoreState.READ_ERROR; throw e }
    }

    suspend fun persist() = saveMutex.withLock {
        check(initialized) { "Guide store is still loading" }
        val snapshot = preferencesOnly(memory.toJson()).toString()
        try {
            withContext(Dispatchers.IO) { disk.save(snapshot); clearLegacy() }
            _state.value = StoreState.READY
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            // Retain in-memory changes and the journal; the next save retries the latest state.
            _state.value = StoreState.WRITE_ERROR
            Log.w("GuideStore","Could not persist guide state",e)
        }
    }

    companion object {
        fun create(context: Context): FileStore {
            val app = context.applicationContext
            val files = SnapshotFiles(File(app.filesDir,"guide"))
            val dataStore = DataStoreFactory.create(
                serializer = GuideMetadataSerializer,
                corruptionHandler = ReplaceFileCorruptionHandler { files.recoveryMetadata() },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                produceFile = { File(app.filesDir,"datastore/guide-metadata.json") },
            )
            val metadata = object : MetadataDisk {
                override suspend fun read() = dataStore.data.first()
                override suspend fun write(value: String) { dataStore.updateData { value } }
            }
            return FileStore(MemoryStore(),SnapshotDisk(files,metadata),
                readLegacy = { app.getSharedPreferences("guide",Context.MODE_PRIVATE).getString("state",null) },
                clearLegacy = {
                    val prefs = app.getSharedPreferences("guide",Context.MODE_PRIVATE)
                    if (prefs.contains("state") && !prefs.edit().remove("state").commit())
                        throw IOException("Could not retire legacy guide state")
                })
        }
    }
}

/**
 * Only settings and a few flags reach the disk. Card results, tip counts, pending cards and drive records
 * live for this run only: a lookup keeps no history, and older files lose theirs on the next save.
 */
private val RECORD_KEYS = listOf("cards", "stats", "pending", "drives")
internal fun preferencesOnly(o: org.json.JSONObject) = o.apply { RECORD_KEYS.forEach { remove(it) } }
