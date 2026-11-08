package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.content.Content
import io.github.beomsun0829.handsonguide.ui.Art
import io.github.beomsun0829.handsonguide.ui.artFor
import io.github.beomsun0829.handsonguide.ui.drawRoad3D
import io.github.beomsun0829.handsonguide.ui.laneSceneAt
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Writes every road card's scene as a PNG when run with `-PrenderOut=<dir>`; skipped otherwise. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RoadSceneRenderTest {
    @Test fun renderRoadScenes() {
        val out = System.getProperty("render.out")
        assumeTrue(out != null)
        val dir = File(out!!).apply { mkdirs() }
        val content = Content.parse(asset("cards"), asset("situations"), asset("telltales"))
        val measurer = TextMeasurer(createFontFamilyResolver(RuntimeEnvironment.getApplication()), Density(1f), LayoutDirection.Ltr)
        val only = System.getProperty("render.only")?.split(",")
        // The lane-change scene, at each of its steps.
        for (t in listOf(1f, 3.5f, 6f, 8.5f, 11f, 13.5f)) {
            if (only != null && "c11-order" !in only) break
            val image = ImageBitmap(1000, 810)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(1000f, 810f)) { laneSceneAt(t, t, measurer) }
            File(dir, "c11-order_${(t * 10).toInt()}.png").outputStream().use { image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        for (card in content.cards) {
            val art = artFor(card) as? Art.Road ?: continue
            if (only != null && card.id !in only) continue
            for (t in listOf(1.5f, 3.2f)) {
                val image = ImageBitmap(1000, 720)
                CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(1000f, 720f)) { drawRoad3D(art, t, t, measurer) }
                File(dir, "${card.id}_${(t * 10).toInt()}.png").outputStream().use { image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }

    private fun asset(name: String) = File("src/main/assets/content/$name.json").readText()

    /** Rough per-frame drawing cost of each road scene (`-PrenderOut` only; JVM numbers, for comparing scenes). */
    @Test fun frameCost() {
        val out = System.getProperty("render.out")
        assumeTrue(out != null)
        val content = Content.parse(asset("cards"), asset("situations"), asset("telltales"))
        val measurer = TextMeasurer(createFontFamilyResolver(RuntimeEnvironment.getApplication()), Density(1f), LayoutDirection.Ltr)
        val image = ImageBitmap(1000, 810)
        val rows = ArrayList<String>()
        fun time(name: String, draw: androidx.compose.ui.graphics.drawscope.DrawScope.(Float) -> Unit) {
            repeat(20) { i -> CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(1000f, 810f)) { draw(i / 60f) } }
            val start = System.nanoTime()
            repeat(60) { i -> CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(1000f, 810f)) { draw(i / 60f) } }
            rows += "%-16s %.2f ms/frame".format(name, (System.nanoTime() - start) / 60e6)
        }
        time("c11-order") { t -> laneSceneAt(5f + t, 5f + t, measurer) }
        for (card in content.cards) {
            val art = artFor(card) as? Art.Road ?: continue
            time(card.id) { t -> drawRoad3D(art, 1f + t, 1f + t, measurer) }
        }
        File(out!!, "frame_cost.txt").writeText(rows.joinToString(System.lineSeparator()))
    }
}
