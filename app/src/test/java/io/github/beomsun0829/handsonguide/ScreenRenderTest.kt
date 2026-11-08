package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.signal.Signals
import io.github.beomsun0829.handsonguide.ui.CardScreen
import io.github.beomsun0829.handsonguide.ui.DriveScreen
import io.github.beomsun0829.handsonguide.ui.SettingsScreen
import io.github.beomsun0829.handsonguide.ui.GuideTheme
import io.github.beomsun0829.handsonguide.ui.HomeScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Writes the home screen and a few cards as PNGs at the app's pane size (1280×850 at 1x) when run with
 * `-PrenderOut=<dir>`; skipped otherwise. Lets the layout be checked without an emulator.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h850dp-mdpi")
class ScreenRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** A state write from the test thread reaches the composition only once the snapshot is applied. */
    private fun onUi(block: () -> Unit) = compose.runOnUiThread { block(); androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }

    private fun shoot(dir: File, name: String) {
        // Photos decode on the IO dispatcher in real time: give them a moment between frames.
        repeat(8) { compose.mainClock.advanceTimeBy(700); Thread.sleep(300) }
        // Draw the window's views straight into a bitmap (captureToImage waits for a frame that never comes here).
        val view = compose.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun renderScreens() {
        val out = System.getProperty("render.out")
        assumeTrue(out != null)
        val dir = File(out!!, "screens").apply { mkdirs() }
        val cards = System.getProperty("render.cards")?.split(",") ?: listOf("c1-seat", "c0-trunk", "c11-order", "c13-toll")
        EngineFixture().use { f ->
            var screen by mutableStateOf("home")
            compose.mainClock.autoAdvance = false
            compose.setContent {
                val s by f.app.signals.collectAsState()
                val t by f.tutorial.ui.collectAsState()
                val coach by f.coach.ui.collectAsState()
                val voice by f.voice.state.collectAsState()
                GuideTheme(dark = true) {
                    Box(Modifier.size(1280.dp, 850.dp)) {
                        when (screen) {
                            "home" -> HomeScreen(f.app, s, act = {}, onLogo = {})
                            "drive" -> DriveScreen(f.app, coach, s, voice)
                            "settings" -> SettingsScreen(f.app, s, act = {}, onVoice = {}, onRate = {})
                            else -> CardScreen(f.app, t, s, act = {})
                        }
                    }
                }
            }
            shoot(dir, "home")
            onUi { screen = "settings" }; shoot(dir, "settings")
            // A few parked readings, so the "지금 상태" panels have values to show.
            onUi { f.emit(400, Signals.FAN_DIRECTION to "FACE", Signals.FAN to 3, Signals.PARKING_BRAKE to true, Signals.BRAKE to false) }
            for (id in cards) {
                onUi { f.app.jumpCard(id); screen = "card" }
                shoot(dir, id)
            }
            // Driving: in a lane with the signal on, so the drive screen has something to show.
            onUi { f.emit(400, Signals.GEAR to "D", Signals.SPEED to 52.0, Signals.TURN_SIGNAL to "OFF", Signals.PARKING_BRAKE to false, Signals.NAV_ACTIVE to true,
                Signals.NAV_TURN_TYPE to "RIGHT", Signals.NAV_TURN_DIST to 300, Signals.NAV_SPEED_LIMIT to 60); screen = "drive" }
            shoot(dir, "drive")
        }
    }

    /** The whole home in one picture: a tall window instead of scrolling. */
    @Test @Config(sdk = [34], qualifiers = "w1280dp-h2000dp-mdpi") fun renderWholeHome() {
        val out = System.getProperty("render.out")
        assumeTrue(out != null)
        val dir = File(out!!, "screens").apply { mkdirs() }
        EngineFixture().use { f ->
            compose.mainClock.autoAdvance = false
            compose.setContent {
                val s by f.app.signals.collectAsState()
                GuideTheme(dark = true) { Box(Modifier.size(1280.dp, 2000.dp)) { HomeScreen(f.app, s, act = {}, onLogo = {}) } }
            }
            shoot(dir, "home_full")
        }
    }
}
