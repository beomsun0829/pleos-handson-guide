package io.github.beomsun0829.handsonguide.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import io.github.beomsun0829.handsonguide.AppController
import io.github.beomsun0829.handsonguide.Screen
import io.github.beomsun0829.handsonguide.content.Playlist
import io.github.beomsun0829.handsonguide.signal.Signals
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Home (docs/design/home-redesign.md): the controls this car does its own way, each shown where it is,
 * and the road groups. A lookup: open one thing and leave.
 */
@Composable
fun HomeScreen(app: AppController, s: Signals, act: Act, onMic: (() -> Unit)? = null, onLogo: () -> Unit) {
    val guide = app.guide
    Screenful {
        TopBar("핸즈온 가이드", s, onSettings = { act { show(Screen.SETTINGS) } }, onLogo = onLogo)
        Column(Modifier.weight(1f).padding(horizontal = if (isWide) 32.dp else 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            // Several lights at once: the most urgent (red first) stays open, the others are one line each
            // until tapped, so the panel never pushes the whole home off the screen.
            val warnings = app.ui.collectAsState().value.homeWarnings    // recompose as lights come and go
            var opened by remember { mutableStateOf<String?>(null) }
            val open = warnings.firstOrNull { it.type == opened && !it.collapsed } ?: warnings.firstOrNull { !it.collapsed }
            warnings.forEach { warning ->
                TelltalePanel(app, warning.type, warning.red, warning != open,
                    onCollapsed = { collapsed ->
                        if (!collapsed) opened = warning.type
                        if (collapsed || warning.collapsed) act { setWarningCollapsed(warning, collapsed) }
                    },
                    onMore = { act { jumpCard(if (warning.type == "TPMS") "c8-tpms" else "c8-colors") } })
            }
            SectionTitle(guide.essentials.title)
            // Every control this car does its own way, each a photo of where it is; one tap opens it.
            val perFeature = if (isWide) 4 else 3
            guide.essentials.cards.chunked(perFeature).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { id -> FeatureTile(app, id, act, Modifier.weight(1f)) }
                    repeat(perFeature - row.size) { Box(Modifier.weight(1f)) }
                }
            }
            SectionTitle("도로 위 기본기")
            // Same width for every tile: the last row is padded with empty slots instead of stretching.
            val perRow = if (isWide) 3 else 2
            guide.road.chunked(perRow).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { p -> RoadTile(app, p, s, act, Modifier.weight(1f)) }
                    repeat(perRow - row.size) { Box(Modifier.weight(1f)) }
                }
            }
            Vspace(16.dp)
        }
        VoiceHint("Gleo에게 ‘김서림 어떻게 해?’라고 물어보세요", onMic)
    }
}

/** One control: the photo of where it is with the spot ringed, its name, and the photo's caption saying where. */
@Composable
private fun FeatureTile(app: AppController, id: String, act: Act, modifier: Modifier) {
    val card = app.content.card(id) ?: return
    val photo by assetBitmap("scenes3d/$id/1_photo.webp")
    val cue by assetCue("scenes3d/$id/frames.json")
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(c.surface)
        .clickable(role = Role.Button) { act { openPlaylist(app.guide.essentials, id) } }) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.6f).background(Color(0xFF101318))) {
            photo?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            // The card's own tap rings, so two controls on the same panel still read apart at a glance.
            cue?.let { k -> if (photo != null) Canvas(Modifier.fillMaxSize()) {
                val shown = size.width / PHOTO_ASPECT                 // photo height at this width; the crop trims top and bottom
                val top = (shown - size.height) / 2f
                for (p in k.spots) {
                    val at = Offset(p.x * size.width, p.y * shown - top)
                    drawCircle(Color(0x99000000), 15.dp.toPx(), at, style = Stroke(6.dp.toPx()))
                    drawCircle(Color.White, 15.dp.toPx(), at, style = Stroke(3.dp.toPx()))
                }
            } }
        }
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(card.title, style = GuideType.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(cue?.caption ?: "", style = GuideType.caption, color = c.textSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RoadTile(app: AppController, p: Playlist, s: Signals, act: Act, modifier: Modifier) {
    val first = p.cards.firstNotNullOfOrNull { app.content.card(it) } ?: return
    Row(modifier.height(112.dp).clip(RoundedCornerShape(24.dp)).background(c.surface)
        .clickable(role = Role.Button) { act { openPlaylist(p) } }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(80.dp).clip(RoundedCornerShape(20.dp)).background(c.info.copy(alpha = .10f)), contentAlignment = Alignment.Center) {
            Glyph(p.icon ?: first.illustration ?: "road", c.info, 48.dp)
        }
        Hspace(16.dp)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(p.title, style = GuideType.title, color = c.text, maxLines = 1)
            Text("${p.cards.size}가지", style = GuideType.caption, color = c.textSub)    // look things up, not a course to finish
        }
    }
}

/** Card photos are 1200×960. */
private const val PHOTO_ASPECT = 1.25f

/** What a feature tile shows from its card's first photo frame: the caption and where its taps or ring sit (0..1). */
private class TileCue(val caption: String, val spots: List<Offset>)

@Composable
private fun assetCue(path: String): androidx.compose.runtime.State<TileCue?> {
    val context = LocalContext.current
    return produceState<TileCue?>(null, path) {
        value = runCatching {
            withContext(Dispatchers.IO) {
                val f = org.json.JSONObject(context.assets.open(path).bufferedReader().use { it.readText() }).getJSONArray("frames").getJSONObject(0)
                val taps = f.optJSONArray("taps")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getJSONArray("at") } }.orEmpty()
                    .map { Offset(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) }
                val ring = f.optJSONArray("ring")?.let { r -> Offset((r.getDouble(0) + r.getDouble(2) / 2).toFloat(), (r.getDouble(1) + r.getDouble(3) / 2).toFloat()) }
                // A card with only a motion arrow (the wiper's twist) rings where the motion starts.
                val arrow = f.optJSONArray("arrows")?.optJSONObject(0)?.getJSONArray("pts")?.getJSONArray(0)
                    ?.let { Offset(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) }
                TileCue(f.getString("caption"), taps.ifEmpty { listOfNotNull(ring ?: arrow) })
            }
        }.getOrNull()
    }
}

@Composable
fun assetBitmap(path: String): androidx.compose.runtime.State<ImageBitmap?> {
    val context = LocalContext.current
    return produceState<ImageBitmap?>(null, path) {
        value = if (path.isEmpty()) null else runCatching {
            withContext(Dispatchers.IO) { context.assets.open(path).use { BitmapFactory.decodeStream(it).asImageBitmap() } }
        }.getOrNull()
    }
}

