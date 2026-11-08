package io.github.beomsun0829.handsonguide.ui

import io.github.beomsun0829.handsonguide.GuideApp
import io.github.beomsun0829.handsonguide.Screen
import io.github.beomsun0829.handsonguide.signal.Signals
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private val GLYPHS = listOf("defrost_front", "defrost_rear", "wiper", "recirc", "ac", "fan", "seat", "belt", "mirror", "wheel",
    "pedal", "gear", "park", "light", "hazard", "turn_left", "turn_right", "telltale", "cruise", "lane", "school", "rain",
    "rest", "intersection", "highway", "speed", "roundabout", "uturn", "check", "settings", "mic", "speaker")

/** S20: hidden developer/demo panel (5 taps on the home logo). */
@Composable
fun DevScreen(app: GuideApp, s: Signals, act: Act) {
    val running by app.player.running.collectAsState()
    val elapsed by app.player.elapsed.collectAsState()
    val overlay = app.controller.hub.overlayKeys()
    Screenful {
        TopBar("개발자 · 데모", s, onBack = { act { show(Screen.HOME) } })
        Row(Modifier.weight(1f).padding(horizontal = 32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SectionTitle("시나리오")
                Text(if (running != null) "실행 중: $running (${"%.0f".format(elapsed)}초)" else "대기", style = GuideType.body, color = c.textSub)
                app.scenarios.forEach { sc ->
                    SecondaryButton(sc.title, Modifier.fillMaxWidth()) { app.scope.launch { app.player.play(sc) } }
                }
                if (running != null) SecondaryButton("처음부터 다시", Modifier.fillMaxWidth()) {
                    app.scope.launch { app.scenarios.firstOrNull { it.name == running }?.let(app.player::play) }
                }
                PrimaryButton("시나리오 정지 · 주입 값 지우기", Modifier.fillMaxWidth()) { app.scope.launch { app.player.stop() } }
                SectionTitle("상태")
                SecondaryButton("진행 기록 초기화", Modifier.fillMaxWidth()) { act { store.clearAll(); store.onboarded = true; show(Screen.HOME) } }
                // Every icon at its real size, to check that each one reads as what it means.
                SectionTitle("아이콘")
                GLYPHS.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { name ->
                            Column(Modifier.weight(1f), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                Glyph(name, c.info, 56.dp); Text(name, style = GuideType.caption, color = c.textSub)
                            }
                        }
                    }
                }
            }
            Column(Modifier.weight(1.4f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionTitle("신호 (${s.values.size})")
                s.values.entries.sortedBy { it.key }.forEach { (k, v) ->
                    val shown = when (v) {
                        is Double -> "%.2f".format(v)
                        is List<*> -> v.joinToString { it.toString().substringAfter("type=").substringBefore(",") }.ifEmpty { "[]" }
                        else -> v.toString()
                    }
                    Text("${if (k in overlay) "◆ " else ""}$k = $shown", style = GuideType.caption, color = if (k in overlay) c.warn else c.text)
                }
            }
        }
    }
}
