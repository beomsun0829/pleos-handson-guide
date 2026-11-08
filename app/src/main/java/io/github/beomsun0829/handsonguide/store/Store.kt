package io.github.beomsun0829.handsonguide.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class CardResult { CONFIRMED, READ, SKIPPED, UNSUPPORTED }
enum class GuideLevel { BEGINNER, FAMILIAR, MINIMAL }
enum class ThemeMode { VEHICLE, DARK, LIGHT }
enum class LearningLevel { FIRST, LEARNING, BRIEF }
enum class DriveSource { REAL, DEMO, MIXED, UNKNOWN }

data class SituationStat(val count: Int = 0, val okStreak: Int = 0, val lastMissed: Boolean = false, val lastAt: Long = 0,
    val level: LearningLevel? = null,
    /** Encounters in the current learning cycle; lifetime count and review history remain intact. */
    val learningCount: Int = count)

data class Miss(val situationId: String, val at: Long, val lat: Double? = null, val lon: Double? = null,
    val description: String? = null)

data class DriveRecord(
    val startAt: Long,
    val endAt: Long = 0,
    val firstTips: List<String> = emptyList(),
    val tips: List<String> = emptyList(),
    val misses: List<Miss> = emptyList(),
    /** Estimated from speed samples. Null means the complete distance is not known. */
    val km: Double? = null,
    val source: DriveSource = DriveSource.UNKNOWN,
    /** What a templated card actually said, e.g. TELLTALE_AMBER → "타이어 공기압". */
    val labels: Map<String, String> = emptyMap(),
)

data class Settings(
    val coachingOn: Boolean = true,
    val guideLevel: GuideLevel = GuideLevel.BEGINNER,
    val voiceOn: Boolean = true,
    val tutorialReadAloud: Boolean = false,   // cards are read on screen; no read-aloud control in the page
    val speechRate: Float = 1.0f,
    val navDedupe: Boolean = true,
    val theme: ThemeMode = ThemeMode.VEHICLE,
)

/** Persistent state (spec §13). Unknown legacy timestamps remain null. */
interface Store {
    val version: StateFlow<Int>
    var onboarded: Boolean
    var experienced: Boolean
    val onboardingAt: Long?
    var pausedCard: String?
    var basicsAnnounced: Boolean
    var firstDriveIntroduced: Boolean
    var quietUntil: Long
    var settings: Settings
    fun cardResult(id: String): CardResult?
    fun cardRecordedAt(id: String): Long?
    fun setCardResult(id: String, r: CardResult)
    fun stat(id: String): SituationStat
    fun setStat(id: String, s: SituationStat)
    fun allStats(): Map<String, SituationStat>
    fun resetStat(id: String)
    val pendingCards: Set<String>
    fun addPending(ids: Collection<String>)
    fun removePending(id: String)
    val drives: List<DriveRecord>
    fun saveDrive(d: DriveRecord)
    fun clearAll()
}

class MemoryStore(private val clock: () -> Long = System::currentTimeMillis) : Store {
    private val _v = MutableStateFlow(0)
    override val version: StateFlow<Int> = _v
    protected fun bump() { _v.value++ }
    override var onboardingAt: Long? = null
        private set
    override var onboarded = false; set(v) {
        if (v && !field) onboardingAt = clock()
        if (!v) onboardingAt = null
        field = v; bump()
    }
    override var experienced = false; set(v) { field = v; bump() }
    override var pausedCard: String? = null; set(v) { field = v; bump() }
    override var basicsAnnounced = false; set(v) { field = v; bump() }
    override var firstDriveIntroduced = false; set(v) { field = v; bump() }
    override var quietUntil = 0L; set(v) { field = v; bump() }
    override var settings = Settings(); set(v) { field = v; bump() }
    private val cards = HashMap<String, CardResult>()
    private val cardTimes = HashMap<String, Long>()
    private val stats = HashMap<String, SituationStat>()
    private val pending = LinkedHashSet<String>()
    private val driveList = ArrayList<DriveRecord>()
    override fun cardResult(id: String) = cards[id]
    override fun cardRecordedAt(id: String) = cardTimes[id]
    override fun setCardResult(id: String, r: CardResult) {
        // A confirmation is never downgraded by a later skip or re-read.
        if (cards[id] == CardResult.CONFIRMED && r != CardResult.CONFIRMED) return
        cards[id] = r
        cardTimes[id] = clock()
        if (r != CardResult.SKIPPED) pending.remove(id)
        bump()
    }
    override fun stat(id: String) = stats[id] ?: SituationStat()
    override fun setStat(id: String, s: SituationStat) { stats[id] = s; bump() }
    override fun allStats(): Map<String, SituationStat> = stats.toMap()
    override fun resetStat(id: String) {
        val previous=stats[id] ?: return
        stats[id]=previous.copy(okStreak=0,lastMissed=false,level=LearningLevel.FIRST,learningCount=0)
        bump()
    }
    override val pendingCards: Set<String> get() = pending.toSet()
    override fun addPending(ids: Collection<String>) {
        ids.filter { cards[it] == null || cards[it] == CardResult.SKIPPED }.forEach { pending.add(it) }; bump()
    }
    override fun removePending(id: String) { pending.remove(id); bump() }
    override val drives: List<DriveRecord> get() = driveList.toList()
    override fun saveDrive(d: DriveRecord) {
        driveList.removeAll { it.startAt == d.startAt }
        driveList.add(d)
        driveList.sortBy { it.startAt }
        while (driveList.size > 30) driveList.removeAt(0)
        bump()
    }
    override fun clearAll() {
        cards.clear(); cardTimes.clear(); stats.clear(); pending.clear(); driveList.clear()
        onboarded = false; experienced = false; pausedCard = null; basicsAnnounced = false
        firstDriveIntroduced = false; quietUntil = 0L
        settings = Settings()
        bump()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", 2)
        put("onboarded", onboarded); put("experienced", experienced); put("pausedCard", pausedCard ?: JSONObject.NULL)
        put("onboardingAt", onboardingAt ?: JSONObject.NULL)
        put("basicsAnnounced", basicsAnnounced)
        put("firstDriveIntroduced", firstDriveIntroduced); put("quietUntil", quietUntil)
        put("settings", JSONObject().apply {
            put("coachingOn", settings.coachingOn)
            put("guideLevel", settings.guideLevel.name); put("voiceOn", settings.voiceOn); put("speechRate", settings.speechRate.toDouble())
            put("tutorialReadAloud", settings.tutorialReadAloud)
            put("navDedupe", settings.navDedupe)
            put("theme", settings.theme.name)
        })
        put("cards", JSONObject().apply { cards.forEach { (k, v) ->
            put(k, JSONObject().put("result", v.name).put("at", cardTimes[k] ?: JSONObject.NULL))
        } })
        put("stats", JSONObject().apply {
            stats.forEach { (k, s) -> put(k, JSONObject().put("count", s.count).put("okStreak", s.okStreak)
                .put("lastMissed", s.lastMissed).put("lastAt", s.lastAt).put("level",s.level?.name ?: JSONObject.NULL)
                .put("learningCount",s.learningCount)) }
        })
        put("pending", JSONArray(pending.toList()))
        put("drives", JSONArray().apply {
            driveList.forEach { d ->
                put(JSONObject().put("startAt", d.startAt).put("endAt", d.endAt)
                    .put("km",d.km ?: JSONObject.NULL).put("source",d.source.name)
                    .put("firstTips", JSONArray(d.firstTips)).put("tips", JSONArray(d.tips))
                    .put("misses", JSONArray().apply { d.misses.forEach { m ->
                        put(JSONObject().put("id", m.situationId).put("at", m.at)
                            .put("lat", m.lat ?: JSONObject.NULL).put("lon", m.lon ?: JSONObject.NULL)
                            .put("description", m.description ?: JSONObject.NULL))
                    } })
                    .put("labels", JSONObject(d.labels)))
            }
        })
    }

    fun load(o: JSONObject) {
        cards.clear(); cardTimes.clear(); stats.clear(); pending.clear(); driveList.clear()
        settings = Settings()
        onboarded = o.optBoolean("onboarded"); experienced = o.optBoolean("experienced")
        // Loading old progress must not invent a completion date of today.
        onboardingAt = (o.opt("onboardingAt") as? Number)?.toLong()?.takeIf { onboarded && it > 0 }
        pausedCard = o.optString("pausedCard").takeIf { it.isNotEmpty() && it != "null" }
        if (pausedCard == "c3-nocross") pausedCard = "c3-noCross"
        basicsAnnounced = o.optBoolean("basicsAnnounced", false)
        firstDriveIntroduced = o.optBoolean("firstDriveIntroduced", false)
        quietUntil = o.optLong("quietUntil", 0)
        o.optJSONObject("settings")?.let { s ->
            settings = Settings(
                coachingOn = s.optBoolean("coachingOn", true),
                guideLevel = runCatching { GuideLevel.valueOf(s.optString("guideLevel")) }.getOrDefault(GuideLevel.BEGINNER),
                voiceOn = s.optBoolean("voiceOn", true),
                tutorialReadAloud = false,
                speechRate = s.optDouble("speechRate", 1.0).takeIf { it.isFinite() && it in .5..2.0 }?.toFloat() ?: 1f,
                navDedupe = s.optBoolean("navDedupe", true),
                theme = runCatching { ThemeMode.valueOf(s.optString("theme")) }.getOrDefault(ThemeMode.VEHICLE),
            )
        }
        o.optJSONObject("cards")?.let { c -> c.keys().forEach { k ->
            val id = if (k == "c3-nocross") "c3-noCross" else k
            val entry = c.optJSONObject(k)
            val result = runCatching { CardResult.valueOf(entry?.optString("result") ?: c.optString(k)) }.getOrNull()
            if (result != null && (cards[id] != CardResult.CONFIRMED || result == CardResult.CONFIRMED)) {
                cards[id] = result
                cardTimes.remove(id)
                (entry?.opt("at") as? Number)?.toLong()?.takeIf { it > 0 }?.let { cardTimes[id] = it }
            }
        } }
        o.optJSONObject("stats")?.let { c ->
            c.keys().forEach { k ->
                val s = c.optJSONObject(k) ?: return@forEach
                stats[k] = SituationStat(s.optInt("count").coerceAtLeast(0), s.optInt("okStreak").coerceAtLeast(0),
                    s.optBoolean("lastMissed"), s.optLong("lastAt").coerceAtLeast(0),
                    runCatching { LearningLevel.valueOf(s.optString("level")) }.getOrNull(),
                    s.optInt("learningCount",s.optInt("count")).coerceIn(0,s.optInt("count").coerceAtLeast(0)))
            }
        }
        o.optJSONArray("pending")?.let { a -> (0 until a.length()).forEach {
            val id = (a.opt(it) as? String)?.takeIf { id -> id.isNotBlank() } ?: return@forEach
            val normalized = if (id == "c3-nocross") "c3-noCross" else id
            if (cards[normalized] == null || cards[normalized] == CardResult.SKIPPED) pending.add(normalized)
        } }
        o.optJSONArray("drives")?.let { a ->
            (0 until a.length()).forEach { i ->
                val d = a.optJSONObject(i) ?: return@forEach
                val start = d.optLong("startAt").takeIf { it > 0 } ?: return@forEach
                val end = d.optLong("endAt").takeIf { it == 0L || it >= start } ?: return@forEach
                fun strs(key: String) = d.optJSONArray(key)?.let { x ->
                    (0 until x.length()).mapNotNull { x.opt(it) as? String }.filter { it.isNotBlank() }.distinct()
                } ?: emptyList()
                val misses = d.optJSONArray("misses")?.let { x -> (0 until x.length()).mapNotNull { j ->
                    val m = x.optJSONObject(j) ?: return@mapNotNull null
                    val id = (m.opt("id") as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    // Wall-clock corrections and legacy records can cross drive boundaries.
                    val at = (m.opt("at") as? Number)?.toLong()?.takeIf { it > 0 } ?: return@mapNotNull null
                    val lat = (m.opt("lat") as? Number)?.toDouble()?.takeIf { it.isFinite() && it in -90.0..90.0 }
                    val lon = (m.opt("lon") as? Number)?.toDouble()?.takeIf { it.isFinite() && it in -180.0..180.0 }
                    Miss(id, at, if (lon != null) lat else null, if (lat != null) lon else null,
                        (m.opt("description") as? String)?.takeIf { it.isNotBlank() })
                } } ?: emptyList()
                driveList.removeAll { it.startAt == start }
                val source = runCatching { DriveSource.valueOf(d.optString("source")) }.getOrDefault(DriveSource.UNKNOWN)
                val km = (d.opt("km") as? Number)?.toDouble()?.takeIf {
                    it.isFinite() && it >= 0 && source == DriveSource.REAL
                }
                val labels = d.optJSONObject("labels")?.let { o ->
                    o.keys().asSequence().mapNotNull { k -> (o.opt(k) as? String)?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap()
                } ?: emptyMap()
                driveList.add(DriveRecord(start, end, strs("firstTips"), strs("tips"), misses, km, source, labels))
            }
        }
        driveList.sortBy { it.startAt }
        while (driveList.size > 30) driveList.removeAt(0)
        bump()
    }
}

