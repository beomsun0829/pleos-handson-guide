package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.scene.RoadModel
import io.github.beomsun0829.handsonguide.scene.SceneScript
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class RoadSceneTest {
    private fun script(id:String) = SceneScript.parse(File("src/main/assets/scenes/$id.json").readText())

    @Test fun comparisonAlwaysShowsTheCorrectExampleAfterTheGhostExample() {
        SceneScript.cards.values.distinct().map(::script).filter { it.kind=="road" }.forEach { s ->
            assertFalse(s.sample(0f).phase.correct)
            assertTrue(s.sample(6f).phase.correct)
            assertFalse(s.sample(12f).phase.correct)
            assertTrue(s.sample(Float.NaN).cars.all { it.x.isFinite() })
            assertTrue(s.phases.first().cars.all { it.color[3]<1f })
            assertTrue(s.phases.last().cars.all { it.color[3]==1f })
        }
    }

    @Test fun reducingSpeedAfterChangingLaneClosesTheRearGapAndLightsItsBrakes() {
        val s=script("lane-speed")
        val bad=s.sample(4f).cars.associateBy { it.id }
        val good=s.sample(10f).cars.associateBy { it.id }
        val badGap=bad.getValue("rear").z-bad.getValue("ego").z
        val goodGap=good.getValue("rear").z-good.getValue("ego").z
        assertTrue(badGap>4.85f) // The wrong example illustrates close following, not a collision.
        assertTrue(goodGap>badGap*1.5f)
        assertTrue(bad.getValue("rear").brake)
        assertFalse(good.getValue("rear").brake)
    }

    @Test fun correctWaitExampleSignalsBeforeMovingAcrossTheLane() {
        val s=script("lane-wait")
        val start=s.sample(6f).cars.first { it.id=="ego" }
        val waiting=s.sample(8.4f).cars.first { it.id=="ego" }
        val moving=s.sample(9.3f).cars.first { it.id=="ego" }
        assertTrue(start.signal && waiting.signal && moving.signal)
        assertEquals(start.x,waiting.x,.001f)
        assertTrue(moving.x<waiting.x)
        assertEquals(-1.75f,s.sample(11f).cars.first { it.id=="ego" }.x,.001f)
    }

    @Test fun glbGeometryHasNamedMeshesFiniteNormalsAndBrakeIndicatorMaterials() {
        val meshes=RoadModel.parse(File("src/main/assets/scenes/road-kit.glb").readBytes())
        assertEquals(setOf("sedan","box"),meshes.keys)
        val car=meshes.getValue("sedan")
        assertTrue(car.primitives.any { it.role=="brake" })
        assertTrue(car.primitives.any { it.role=="indicator" })
        car.primitives.forEach { p ->
            assertEquals(0,p.vertices.size%18)
            for(i in p.vertices.indices step 6) {
                val length=p.vertices[i+3]*p.vertices[i+3]+p.vertices[i+4]*p.vertices[i+4]+p.vertices[i+5]*p.vertices[i+5]
                assertEquals(1f,length,.001f)
            }
        }
    }

    @Test fun corruptGlbAndUnsafeScriptOrderingAreRejected() {
        val bytes=File("src/main/assets/scenes/road-kit.glb").readBytes()
        bytes[4]=3
        assertThrows(IllegalArgumentException::class.java) { RoadModel.parse(bytes) }
        val raw=File("src/main/assets/scenes/lane-speed.json").readText()
        assertThrows(IllegalArgumentException::class.java) { SceneScript.parse(raw.replace("\"kind\": \"correct\"","\"kind\": \"incorrect\"")) }
        assertThrows(IllegalArgumentException::class.java) { SceneScript.parse(raw.replace("road-kit.glb","../model.glb")) }
    }
}
