package io.github.beomsun0829.handsonguide.content

import org.json.JSONObject

data class Chapter(val number: Int, val title: String, val icon: String)

data class Verify(
    val instruction: String,
    val hint: String?,
    val condition: Condition,
    val restore: Condition?,
    val restoreSay: String?,
    val success: String,
    val alreadyMet: String?,
    val allowAlreadyAdjusted: Boolean = false,
    /** Short action per step, top to bottom: sequence steps, then putting it back when there is a restore. */
    val steps: List<String> = emptyList(),
)

enum class Practice { NONE, GEAR, PARKING, CREEP, REVERSE }

data class Card(
    val id: String,
    val chapter: Int,
    val stage: Int,
    val order: Int,
    val title: String,
    val headline: String,
    val why: String?,
    val how: String?,
    val situationalLabel: String?,
    val situationalText: String?,
    val source: String?,
    val illustration: String?,
    val speech: List<String>,
    val verify: Verify?,
    val deeper: String?,
    val triggers: List<String>,
    val ev: Boolean,
    val practice: Practice = Practice.NONE,
    val requiresPower: Boolean = false,
) {
    /** What the voice reads when the card opens. */
    val spoken: List<String> get() = speech.ifEmpty { listOfNotNull(headline, why, how) }
}

data class SayLevels(val first: String, val learning: String, val brief: String?)

data class SituationCard(val icon: String, val word: String, val sub: String?, val tone: String)

data class Situation(
    val id: String,
    val group: String,
    val name: String,
    val priority: Int,
    val trigger: Condition,
    val end: Condition?,
    val cooldownSec: Int,
    val card: SituationCard,
    val say: SayLevels,
    val missed: Boolean,
    val drivingOnly: Boolean,
    val relatedCards: List<String>,
    val detail: String?,
    val source: String?,
    /** Situations that announce something the vehicle did (cruise released etc.). */
    val banner: Boolean,
    /** For `missed` situations: the driver did the right thing before we had to speak. */
    val okWhen: Condition?,
    /** Speak only when the trigger fires [repeatCount] times within [repeatWithinSec]. */
    val repeatCount: Int,
    val repeatWithinSec: Int,
    /** More specific guidance can supersede this situation without inventing a negative fact. */
    val suppressWhen: Condition? = null,
)

data class TelltaleInfo(
    val type: String,
    val name: String,
    val grade: String,
    val meaning: String,
    val action: String,
    val keepDriving: String,
)

class Content(
    val chapters: List<Chapter>,
    val cards: List<Card>,
    val situations: List<Situation>,
    val telltales: Map<String, TelltaleInfo>,
) {
    private val byId = cards.associateBy { it.id }
    fun card(id: String): Card? = byId[if (id == "c3-nocross") "c3-noCross" else id]
    fun chapter(n: Int): Chapter? = chapters.firstOrNull { it.number == n }
    fun cardsOf(chapter: Int, stage: Int? = null): List<Card> =
        cards.filter { it.chapter == chapter && (stage == null || it.stage == stage) }.sortedWith(compareBy({ it.stage }, { it.order }))
    fun situation(id: String): Situation? = situations.firstOrNull { it.id == id }

    companion object {
        fun parse(cardsJson: String, situationsJson: String, telltalesJson: String): Content {
            val c = JSONObject(cardsJson)
            val chapters = c.getJSONArray("chapters").objects().map {
                Chapter(it.getInt("number"), it.getString("title"), it.optString("icon", "info"))
            }
            val cards = c.getJSONArray("cards").objects().map(::parseCard)
            val situations = JSONObject(situationsJson).getJSONArray("situations").objects().map(::parseSituation)
            val telltales = JSONObject(telltalesJson).getJSONArray("telltales").objects().map {
                TelltaleInfo(it.getString("type"), it.getString("name"), it.getString("grade"), it.getString("meaning"), it.getString("action"), it.getString("keepDriving"))
            }.associateBy { it.type }
            return Content(chapters, cards, situations, telltales)
        }

        private fun parseCard(o: JSONObject): Card {
            val v = o.optJSONObject("verify")
            val situational = o.optJSONObject("situational")
            return Card(
                id = o.getString("id"),
                chapter = o.getInt("chapter"),
                stage = o.getInt("stage"),
                order = o.optInt("order", 0),
                title = o.getString("title"),
                headline = o.getString("headline"),
                why = o.optStringOrNull("why"),
                how = o.optStringOrNull("how"),
                situationalLabel = situational?.optStringOrNull("label"),
                situationalText = situational?.optStringOrNull("text"),
                source = o.optStringOrNull("source"),
                illustration = o.optStringOrNull("illustration"),
                speech = o.optJSONArray("speech")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                verify = v?.let {
                    Verify(
                        instruction = it.getString("instruction"),
                        hint = it.optStringOrNull("hint"),
                        condition = Condition.parse(it.getJSONObject("condition")),
                        restore = it.optJSONObject("restore")?.let(Condition::parse),
                        restoreSay = it.optStringOrNull("restoreSay"),
                        success = it.optString("success", "좋아요."),
                        alreadyMet = it.optStringOrNull("alreadyMet"),
                        allowAlreadyAdjusted = it.optBoolean("allowAlreadyAdjusted", false),
                        steps = it.optJSONArray("steps")?.let { a -> (0 until a.length()).map { i -> a.getString(i) } } ?: emptyList(),
                    )
                },
                deeper = o.optStringOrNull("deeper"),
                triggers = o.optJSONArray("triggers")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                ev = o.optBoolean("ev", false),
                practice = o.optStringOrNull("practice")?.let { Practice.valueOf(it.uppercase()) } ?: Practice.NONE,
                requiresPower = o.optBoolean("requiresPower", false),
            )
        }

        private fun parseSituation(o: JSONObject): Situation {
            val card = o.getJSONObject("card")
            val say = o.getJSONObject("say")
            return Situation(
                id = o.getString("id"),
                group = o.getString("group"),
                name = o.getString("name"),
                priority = o.getInt("priority"),
                trigger = Condition.parse(o.getJSONObject("trigger")),
                end = o.optJSONObject("end")?.let(Condition::parse),
                cooldownSec = o.optInt("cooldownSec", 60),
                card = SituationCard(card.getString("icon"), card.getString("word"), card.optStringOrNull("sub"), card.optString("tone", "info")),
                say = SayLevels(say.getString("first"), say.getString("learning"), say.optStringOrNull("brief")),
                missed = o.optBoolean("missed", false),
                drivingOnly = o.optBoolean("drivingOnly", true),
                relatedCards = o.optJSONArray("relatedCards")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                detail = o.optStringOrNull("detail"),
                source = o.optStringOrNull("source"),
                banner = o.optBoolean("banner", false),
                okWhen = o.optJSONObject("okWhen")?.let(Condition::parse),
                repeatCount = o.optJSONObject("repeat")?.optInt("count", 1) ?: 1,
                repeatWithinSec = o.optJSONObject("repeat")?.optInt("withinSec", 0) ?: 0,
                suppressWhen = o.optJSONObject("suppressWhen")?.let(Condition::parse),
            )
        }
    }
}

fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else getString(key).takeIf { it.isNotBlank() }
