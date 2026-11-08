package io.github.beomsun0829.handsonguide.ui

import io.github.beomsun0829.handsonguide.voice.VoiceInputUi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Voice question and answer as a bar along the bottom, not a dialog: the screen behind stays
 * visible and usable, and the bar goes away by itself.
 */
@Composable
fun VoiceInputDialog(ui: VoiceInputUi, onDismiss: () -> Unit, onCard: () -> Unit, modifier: Modifier = Modifier) {
    if (!ui.listening && ui.answer == null) return
    Row(
        modifier.padding(24.dp).fillMaxWidth().heightIn(min = 112.dp).clip(RoundedCornerShape(28.dp)).background(c.surface)
            .border(2.dp, c.outline, RoundedCornerShape(28.dp)).padding(horizontal = 28.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(if (ui.listening) "mic" else "speaker", c.info, 44.dp)
        Column(Modifier.weight(1f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (ui.transcript.isNotBlank()) Text("“${ui.transcript}”", style = GuideType.label, color = c.textSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (ui.listening) "듣고 있어요. 궁금한 기능을 말해주세요." else ui.answer ?: "",
                style = GuideType.body, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onDismiss) { Text(if (ui.listening) "취소" else "닫기", style = GuideType.label, color = c.textSub) }
        if (!ui.listening && ui.cardId != null) PrimaryButton("카드 보기", onClick = onCard)
    }
}
