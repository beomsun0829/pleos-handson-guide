package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.store.ThemeMode
import io.github.beomsun0829.handsonguide.ui.Act
import io.github.beomsun0829.handsonguide.ui.BootScreen
import io.github.beomsun0829.handsonguide.ui.CardScreen
import io.github.beomsun0829.handsonguide.ui.ChapterEndScreen
import io.github.beomsun0829.handsonguide.ui.DevScreen
import io.github.beomsun0829.handsonguide.ui.DriveScreen
import io.github.beomsun0829.handsonguide.ui.GuideTheme
import io.github.beomsun0829.handsonguide.ui.HomeScreen
import io.github.beomsun0829.handsonguide.ui.OnboardingScreen
import io.github.beomsun0829.handsonguide.ui.ReverseScreen
import io.github.beomsun0829.handsonguide.ui.SettingsScreen
import io.github.beomsun0829.handsonguide.ui.VoiceInputDialog
import io.github.beomsun0829.handsonguide.signal.Signals
import android.os.Bundle
import android.os.Build
import android.content.Intent
import android.content.pm.PackageManager
import io.github.beomsun0829.handsonguide.gleo.GleoBridge
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle

class MainActivity : ComponentActivity() {
    private var notificationPrompted = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuideService.start(this)
        val app = application as GuideApp
        val controller = app.controller
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.signals.map { GuideService.isParked(it) }.distinctUntilChanged().collect { parked ->
                    if (parked && !notificationPrompted && Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notificationPrompted = true
                        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2101)
                    }
                }
            }
        }
        // Every user action runs on the single engine thread, like signal updates.
        val act: Act = { f -> app.scope.launch { app.awaitReady(); controller.f() } }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            // On a top-level screen Back hides the app (Pleos review: CCP Back → moveTaskToBack); inside, it goes up.
            override fun handleOnBackPressed() {
                if (controller.ui.value.screen in setOf(Screen.HOME, Screen.DRIVE, Screen.REVERSE, Screen.BOOT)) moveTaskToBack(true)
                else act { back() }
            }
        })
        setContent {
            // Lifecycle-aware: a hidden window stops collecting, so the UI does not recompose 4×/s in the background.
            val ui by controller.ui.collectAsStateWithLifecycle()
            // Pages that only show things take the vehicle values every 2 s; cards (practice) and driving get every change.
            // The derived timers change on every 0.4 s tick, which otherwise redrew a still home screen 2-3 times a second.
            val fast = controller.signals.collectAsStateWithLifecycle()
            // The starting value is read without observing it: observing `fast` here would recompose on every tick again.
            val calm = remember { controller.signals.sample(CALM_SIGNAL_MS) }
                .collectAsStateWithLifecycle(remember { Snapshot.withoutReadObservation { fast.value } })
            val s = if (ui.screen in CALM_SCREENS) calm.value else fast.value
            val t by controller.tutorial.ui.collectAsStateWithLifecycle()
            val coach by controller.coach.ui.collectAsStateWithLifecycle()
            val voice by controller.voice.state.collectAsStateWithLifecycle()
            val input by app.voiceInput.ui.collectAsStateWithLifecycle()
            val version by controller.store.version.collectAsStateWithLifecycle()
            val storage by app.storageState.collectAsStateWithLifecycle()
            val logoTaps = remember { mutableIntStateOf(0) }
            val theme = controller.store.settings.theme
            val dark = when (theme) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.VEHICLE -> s.bool(Signals.NIGHT) ?: true
            }
            @Suppress("UNUSED_EXPRESSION") version
            GuideTheme(dark) {
                val mic: (() -> Unit)? = if (s.gear == "P" && s.num(Signals.SPEED) == 0.0 && s.bool(Signals.UXR_DO) != true &&
                    s.bool(Signals.CALL_ACTIVE) != true && s.bool(Signals.GLEO_SPEAKING) != true)
                    ({ app.scope.launch { app.voiceInput.listen() }; Unit }) else null
                Box {
                    when (ui.screen) {
                        Screen.BOOT -> BootScreen(ui.bootSlow, act,
                            loadingRecords = storage == io.github.beomsun0829.handsonguide.store.StoreState.LOADING,
                            recordError = storage == io.github.beomsun0829.handsonguide.store.StoreState.READ_ERROR)
                        Screen.ONBOARDING -> OnboardingScreen(act)
                        Screen.HOME, Screen.CHAPTERS -> HomeScreen(controller, s, act, onMic = mic) {
                            logoTaps.intValue++
                            if (logoTaps.intValue >= 5) { logoTaps.intValue = 0; act { show(Screen.DEV) } }
                        }
                        Screen.CARD -> CardScreen(controller, t, s, act, onMic = mic)
                        Screen.CHAPTER_END -> ChapterEndScreen(controller, t, act)
                        Screen.DRIVE -> DriveScreen(controller, coach, s, voice)
                        Screen.REVERSE -> ReverseScreen(s)
                        Screen.SETTINGS -> SettingsScreen(controller, s, act,
                            onVoice = { on -> controller.voice.enabled = on },
                            onRate = { r -> app.speech.setRate(r) })
                        Screen.DEV -> DevScreen(app, s, act)
                    }
                    if (mic != null && ui.screen in setOf(Screen.HOME, Screen.CARD)) VoiceInputDialog(input,
                        onDismiss = { app.scope.launch { app.voiceInput.dismiss() } },
                        onCard = { app.scope.launch { app.voiceInput.openCard() } },
                        modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        GleoBridge.dispatch(this, intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        GleoBridge.dispatch(this, intent)
    }

    override fun onStart() {
        super.onStart()
        GuideService.start(this)
        val app = application as GuideApp
        app.scope.launch { app.voiceInput.setForeground(true); app.awaitReady(); app.controller.onForeground() }
    }

    override fun onStop() {
        val app = application as GuideApp
        app.scope.launch {
            app.awaitReady()
            app.voiceInput.setForeground(false)
            app.controller.onBackground()
            app.persistNow()
        }
        super.onStop()
    }
}

/** Screens that show content without reacting to the car moment to moment. */
private val CALM_SCREENS = setOf(Screen.HOME, Screen.CHAPTERS, Screen.SETTINGS)
private const val CALM_SIGNAL_MS = 2_000L
