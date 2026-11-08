package io.github.beomsun0829.handsonguide.content

import org.json.JSONArray
import org.json.JSONObject

/** A named run of topic cards opened together (the car's features, a road group). */
data class Playlist(val title: String, val cards: List<String>, val icon: String? = null)

/**
 * Home structure and playlists (docs/design/home-redesign.md). Only topic cards are shown; the
 * other cards survive as a topic's notes or as aliases, so coaching, review and Gleo links that
 * name them still open the right topic.
 */
class Guide(
    /** The car's features, the first list on home. */
    val essentials: Playlist,
    val road: List<Playlist>,
    /** topic id -> ids whose headlines are shown as notes on it (at most 3). */
    private val notes: Map<String, List<String>>,
    /** any card id (topic, note or absorbed) -> its topic id. */
    private val topicOf: Map<String, String>,
) {
    val topics: Set<String> get() = notes.keys

    fun topic(id: String): String? = topicOf[id] ?: topicOf[if (id == "c3-nocross") "c3-noCross" else id]

    fun notes(topic: String): List<String> = notes[topic].orEmpty()

    /** The playlist to open for a card: the home list its topic is in, else the card alone. */
    fun playlistFor(id: String): Playlist? {
        val t = topic(id) ?: return null
        return (listOf(essentials) + road).firstOrNull { t in it.cards } ?: Playlist("", listOf(t))
    }

    companion object {
        val EMPTY = Guide(Playlist("", emptyList()), emptyList(), emptyMap(), emptyMap())

        fun parse(text: String): Guide {
            val o = JSONObject(text)
            fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }
            fun playlist(p: JSONObject, title: String = p.getString("label")) = Playlist(title, strings(p.getJSONArray("cards")), p.optString("icon").ifEmpty { null })
            val ess = o.getJSONObject("essentials")
            val topics = o.getJSONObject("topics")
            val notes = HashMap<String, List<String>>()
            val topicOf = HashMap<String, String>()
            topics.keys().forEach { t ->
                val spec = topics.getJSONObject(t)
                val n = strings(spec.getJSONArray("notes"))
                notes[t] = n.take(3)
                topicOf[t] = t
                (n + strings(spec.getJSONArray("absorbs"))).forEach { topicOf.putIfAbsent(it, t) }
            }
            fun list(key: String) = o.getJSONArray(key).let { a -> (0 until a.length()).map { playlist(a.getJSONObject(it)) } }
            return Guide(Playlist(ess.getString("title"), strings(ess.getJSONArray("cards"))), list("road"), notes, topicOf)
        }
    }
}
