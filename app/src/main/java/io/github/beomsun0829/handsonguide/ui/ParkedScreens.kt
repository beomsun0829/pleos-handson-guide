package io.github.beomsun0829.handsonguide.ui

import io.github.beomsun0829.handsonguide.AppController
import io.github.beomsun0829.handsonguide.Screen
import io.github.beomsun0829.handsonguide.content.Card
import io.github.beomsun0829.handsonguide.content.Condition
import io.github.beomsun0829.handsonguide.content.Situation
import io.github.beomsun0829.handsonguide.signal.Signals
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import io.github.beomsun0829.handsonguide.scene.SceneScript
import io.github.beomsun0829.handsonguide.scene.ParkingInput
import io.github.beomsun0829.handsonguide.store.CardResult
import io.github.beomsun0829.handsonguide.store.GuideLevel
import io.github.beomsun0829.handsonguide.store.ThemeMode
import io.github.beomsun0829.handsonguide.tutorial.TutorialUi
import io.github.beomsun0829.handsonguide.tutorial.TutorialEngine
import io.github.beomsun0829.handsonguide.tutorial.isComplete
import io.github.beomsun0829.handsonguide.tutorial.VerifyState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

typealias Act = (suspend AppController.() -> Unit) -> Unit

// ---------------- Shared chrome ----------------

@Composable
fun TopBar(title: String, s: Signals, onBack: (() -> Unit)? = null, onSettings: (() -> Unit)? = null, onLogo: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().height(88.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconTap("back", "뒤로", onClick = onBack) else Hspace(16.dp)
        Text(title, style = GuideType.title, color = c.text, maxLines = 1,
            modifier = Modifier.weight(1f).then(if (onLogo != null) Modifier.clickable(onClick = onLogo) else Modifier))
        trailing()
        ConnectionChip(s)
        if (onSettings != null) IconTap("settings", "설정", onClick = onSettings)
    }
}

@Composable
private fun ConnectionChip(s: Signals) {
    val connected = s.bool(Signals.VEHICLE_CONNECTED)
    val gear = s.gear
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(end = 8.dp)) {
        if (connected == false) Chip("차량 연결 중…", tone = "warn")
    }
}

@Composable
fun Screenful(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(c.bg), content = content)
}

// ---------------- S01 Boot ----------------

@Composable
fun BootScreen(slow: Boolean, act: Act, loadingRecords: Boolean = false, recordError: Boolean = false) {
    Box(Modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("핸즈온 가이드", style = GuideType.headline, color = c.text)
            Vspace(16.dp)
            Text(when {
                recordError -> "기록을 읽지 못했어요. 다시 시도하고 있어요."
                loadingRecords -> "저장한 기록을 불러오고 있어요"
                slow -> "차량 연결이 늦어요. 일부 기능 없이 시작할게요."
                else -> "차와 연결하고 있어요"
            }, style = GuideType.body, color = c.textSub)
            if (slow && !loadingRecords && !recordError) { Vspace(32.dp); PrimaryButton("계속") { act { show(if (store.onboarded) Screen.HOME else Screen.ONBOARDING) } } }
        }
    }
}

// ---------------- S02 Onboarding ----------------

@Composable
fun OnboardingScreen(act: Act) {
    val wide = isWide
    Box(Modifier.fillMaxSize().background(c.bg).padding(if (wide) 48.dp else 24.dp), contentAlignment = Alignment.Center) {
        val body: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Text("운전이 아직 서투신가요?", style = GuideType.headline, color = c.text)
                Text("주행 중 안내하는 자세함만 달라져요.", style = GuideType.body, color = c.textSub)
                PrimaryButton("네, 아직 서툴러요", Modifier.fillMaxWidth()) { act { answerOnboarding(false) } }
                SecondaryButton("익숙한 편이에요", Modifier.fillMaxWidth()) { act { answerOnboarding(true) } }
                Text("언제든 설정에서 바꿀 수 있어요.", style = GuideType.caption, color = c.textSub)
            }
        }
        if (wide) Row(verticalAlignment = Alignment.CenterVertically) {
            // The car the guide is about, not an abstract icon.
            val cabin by assetBitmap("home/overview.webp")
            Box(Modifier.size(480.dp, 300.dp).clip(RoundedCornerShape(28.dp)).background(c.surface2)) {
                cabin?.let { androidx.compose.foundation.Image(it, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
            }
            Hspace(64.dp)
            Box(Modifier.width(640.dp)) { body() }
        } else body()
    }
}

// ---------------- S03 Home ----------------

@Composable
fun TelltalePanel(app: AppController, type: String, red: Boolean, collapsed: Boolean, onCollapsed: (Boolean) -> Unit, onMore: (() -> Unit)? = null) {
    val info = app.content.telltales[type]
    if (collapsed) {
        Row(Modifier.fillMaxWidth().heightIn(min=88.dp).clip(RoundedCornerShape(20.dp)).background(c.surface)
            .clickable(onClickLabel="경고등 자세히 보기") { onCollapsed(false) }.padding(horizontal=24.dp,vertical=12.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            Glyph(telltaleGlyph(type),c.tone(if(red) "alert" else "warn"),40.dp)
            Text(info?.name ?: "경고등",style=GuideType.title,color=c.text,modifier=Modifier.weight(1f))
            Text("자세히 ›",style=GuideType.label,color=c.info)
        }
        return
    }
    Panel(Modifier.fillMaxWidth(), tone = if (red) "alert" else "warn") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(telltaleGlyph(type), c.tone(if (red) "alert" else "warn"), 96.dp)
            Hspace(24.dp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(info?.name ?: "경고등", style = GuideType.headline, color = c.text)
                    Chip(info?.grade ?: if (red) "즉시 정차" else "빨리 점검", tone = if (red) "alert" else "warn")
                }
                Text(info?.meaning ?: "계기판 메시지를 확인해요.", style = GuideType.body, color = c.text)
                Text("지금 할 일: ${info?.action ?: "계기판 메시지를 확인해요."}", style = GuideType.body, color = c.text)
                info?.keepDriving?.let { Text("계속 운전: $it", style = GuideType.body, color = c.textSub) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondaryButton("확인했어요") { onCollapsed(true) }
                    onMore?.let { SecondaryButton("계기판에서 보기 ›", onClick = it) }
                }
            }
        }
    }
}

@Composable
private fun LinkRow(text: String, badge: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 88.dp).clip(RoundedCornerShape(20.dp)).background(c.surface).clickable(onClick = onClick).padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = GuideType.title, color = c.text, modifier = Modifier.weight(1f))
        if (badge != null) { Chip(badge, tone = "info"); Hspace(16.dp) }
        Text("›", style = GuideType.headline, color = c.textSub)
    }
}

@Composable
fun VoiceHint(text: String, onMic: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(64.dp).background(c.surface)
        .then(if (onMic != null) Modifier.clickable(onClickLabel = "음성으로 물어보기", onClick = onMic) else Modifier)
        .padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph("mic", c.textSub, 32.dp)
        Hspace(16.dp)
        Text(text, style = GuideType.caption, color = c.textSub)
    }
}

private fun dayLabel(t: Long): String {
    val d = SimpleDateFormat("yyyyMMdd", Locale.KOREA)
    val today = d.format(Date())
    val that = d.format(Date(t))
    return when {
        that == today -> "오늘"
        that == d.format(Date(System.currentTimeMillis() - 86_400_000)) -> "어제"
        else -> SimpleDateFormat("M월 d일", Locale.KOREA).format(Date(t))
    }
}

// ---------------- S05 Card ----------------

@Composable
fun CardScreen(app: AppController, t: TutorialUi, s: Signals, act: Act, onMic: (() -> Unit)? = null) {
    val card = t.card ?: return
    if (t.compact) {
        PracticeCompactScreen(card, t)
        return
    }
    val cards = app.tutorial.playlistCards
    val list = rememberLazyListState()
    // Open at the section asked for (a hotspot, Gleo or a tip may point into the middle); a group opened
    // from its own button starts at the top.
    // Scrolling sets the live section too; only a change that did not come from scrolling moves the page.
    val scrolledTo = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(t.playlistTitle, card.id) { if (card.id != scrolledTo.value) list.scrollToItem(t.index) }
    // The section nearest the top third of the page is the one whose practice is live.
    // While a contents tap scrolls the page, the passing sections must not take over the highlight.
    val jumping = remember { mutableStateOf(false) }
    LaunchedEffect(list, cards) {
        snapshotFlow {
            val info = list.layoutInfo
            val line = info.viewportStartOffset + (info.viewportEndOffset - info.viewportStartOffset) / 3
            info.visibleItemsInfo.filter { it.index < cards.size }.minByOrNull { kotlin.math.abs(it.offset + it.size / 2 - line) }?.index
        }.collect { i -> if (!jumping.value) i?.let { cards.getOrNull(it) }?.let { c -> scrolledTo.value = c.id; act { tutorial.focus(c.id) } } }
    }
    val scope = rememberCoroutineScope()
    Screenful {
        Row(Modifier.fillMaxWidth().height(88.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTap("back", "홈으로", onClick = { act { back() } })
            Text(t.playlistTitle.ifEmpty { card.title }, style = GuideType.title, color = c.text, maxLines = 1, modifier = Modifier.weight(1f))
            if (onMic != null) IconTap("mic", "음성으로 물어보기", onClick = onMic)
        }
        Row(Modifier.weight(1f).padding(horizontal = if (isWide) 32.dp else 16.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            // Contents: every item of the group, tap to jump.
            if (isWide && cards.size > 1) Column(Modifier.width(260.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                cards.forEachIndexed { i, item ->
                    val current = item.id == card.id
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (current) c.info.copy(alpha = .10f) else Color.Transparent)
                        .clickable {
                            jumping.value = true; scrolledTo.value = item.id; act { tutorial.focus(item.id) }
                            scope.launch { try { list.animateScrollToItem(i) } finally { jumping.value = false } }
                        }.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", style = GuideType.label, color = if (current) c.info else c.textSub, modifier = Modifier.width(28.dp))
                        Text(item.title, style = GuideType.label, color = if (current) c.info else c.text, maxLines = 1, modifier = Modifier.weight(1f))
                    }
                }
            }
            BoxWithConstraints(Modifier.weight(1f)) {
            // One item fits one view: the picture takes about 60% of the page height.
            val artHeight = maxHeight * .6f
            LazyColumn(state = list, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(56.dp),
                contentPadding = PaddingValues(bottom = maxHeight * .7f)) {   // room for the last items to reach the top
                itemsIndexed(cards, key = { _, it -> it.id }) { i, item ->
                    DocSection(app, item, i, cards.size, if (item.id == card.id) t else null, s, artHeight, act)
                }
            }
            }
        }
    }
}

/** One item of the document page: title, picture, explanation, the car's state. [t] is set for the live section. */
@Composable
private fun DocSection(app: AppController, card: Card, index: Int, total: Int, t: TutorialUi?, s: Signals,
                       artHeight: androidx.compose.ui.unit.Dp, act: Act) {
    val done = app.store.cardResult(card.id).isComplete()
    val shown = t ?: TutorialUi(card = card, verify = if (done) VerifyState.DONE else if (card.verify != null) VerifyState.IDLE else VerifyState.NONE)
    val art: @Composable (Modifier) -> Unit = { m ->
        // Scenes always start from their first step for reading; the real control (live signal) shows the result.
        CardArt(card, s, m, done = false, step = shown.sequenceDone)
    }
    val text: @Composable ColumnScope.() -> Unit = {
        card.why?.let { Text(it, style = GuideType.body, color = c.text) }
        // Worth knowing: open from the start, short lines (a lookup, not a lesson to unfold).
        val notes = app.guide.notes(card.id).mapNotNull { app.content.card(it)?.headline } + listOfNotNull(card.situationalText)
        if (notes.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("알아두면 좋은 점", style = GuideType.label, color = c.info)
            notes.take(3).forEach { n -> Row { Text("·  ", style = GuideType.body, color = c.textSub); Text(n, style = GuideType.body, color = c.textSub) } }
        }
        StatusPanel(cardStatus(card.id, s, app.content))
    }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (total > 1) Text("${index + 1}. ${card.title}", style = GuideType.label, color = c.info)
        Text(card.headline, style = GuideType.headline, color = c.text)
        // Wide: picture beside its explanation, so one item reads in one view. Narrow: stacked.
        if (isWide) Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            art(Modifier.weight(1.15f).height(artHeight))
            Column(Modifier.weight(.85f), verticalArrangement = Arrangement.spacedBy(18.dp), content = text)
        } else {
            // Keep the pictures' own 5:4 shape; a full-width strip would cut off the control's top and bottom.
            // and no taller than the view's picture band, so one item still reads in one view.
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val h = minOf(artHeight, maxWidth / 1.25f)
                art(Modifier.height(h).width(h * 1.25f))
            }
            text()
        }
    }
}

/** No click handlers, buttons, scrolling or animation while a practice vehicle moves. */
@Composable
private fun PracticeCompactScreen(card: Card, t: TutorialUi) {
    Box(Modifier.fillMaxSize().background(c.bg).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(card.title, style = GuideType.title, color = c.textSub)
            Text(if (t.practicePaused) "브레이크 계속 밟아요" else card.verify?.instruction ?: card.headline,
                style = GuideType.headline, color = if (t.practicePaused) c.warn else c.text, textAlign = TextAlign.Center)
            if (t.verify in setOf(VerifyState.CONFIRMED, VerifyState.DONE)) Glyph("check", c.ok, 96.dp, "확인됐어요")
            else Text("천천히 · 주변을 확인해요", style = GuideType.body, color = c.textSub)
        }
    }
}

/**
 * The actions this card asks for, one row each from top to bottom, with a small check that fills
 * in when the car reports it. No waiting text: an empty box already says "not yet".
 */
/** What the car reports for this control right now; nothing when it reports nothing. */
@Composable
private fun StatusPanel(rows: List<Pair<String, String>>) {
    if (rows.isEmpty()) return
    Panel(Modifier.fillMaxWidth(), padding = 24.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("지금 상태", style = GuideType.label, color = c.textSub)
            rows.forEach { (label, value) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = GuideType.body, color = c.textSub, modifier = Modifier.weight(1f))
                    Text(value, style = GuideType.title, color = c.text)
                }
            }
        }
    }
}

@Composable
private fun StepRow(text: String, done: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) { stateDescription = if (done) "됨" else "아직" }) {
        Box(Modifier.size(28.dp).clip(CircleShape)
            .then(if (done) Modifier.background(c.ok) else Modifier.border(2.dp, c.outline, CircleShape)),
            contentAlignment = Alignment.Center) {
            if (done) Glyph("check", c.surface, 20.dp)
        }
        Hspace(16.dp)
        Text(text, style = GuideType.body, color = if (done) c.textSub else c.text)
    }
}

private fun stepLabel(c: Condition): String = when (c) {
    is Condition.Leaf -> when (val v = c.value) {
        true -> when (c.signal) { Signals.MIRROR_FOLDED -> "접기"; Signals.BELT -> "매기"; else -> "켜기" }
        false -> when (c.signal) { Signals.MIRROR_FOLDED -> "펴기"; Signals.BELT -> "풀기"; else -> "끄기" }
        "LEFT" -> "왼쪽"
        "RIGHT" -> "오른쪽"
        else -> v?.toString() ?: ""
    }
    is Condition.Range -> if ((c.min ?: 0.0) > 100) "끝까지" else "가운데"
    is Condition.Hold -> "안 밀리는지 확인"
    is Condition.All -> c.items.firstOrNull { it is Condition.Leaf && it.signal == Signals.GEAR }
        ?.let(::stepLabel) ?: if (c.items.any { it is Condition.Leaf && it.signal == Signals.PARKING_BRAKE }) "주차 브레이크" else "확인"
    else -> "확인"
}

// ---------------- S06 Chapter end ----------------

@Composable
fun ChapterEndScreen(app: AppController, t: TutorialUi, act: Act) {
    val chapter = app.content.chapter(t.finishedChapter ?: 0)?.title ?: ""
    val title = if (t.basicFinished) "$chapter 기본은 다 봤어요" else "$chapter 다 봤어요"
    Box(Modifier.fillMaxSize().background(c.bg).padding(48.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Glyph("check", c.ok, 120.dp)
            Text(title, style = GuideType.headline, color = c.text, textAlign = TextAlign.Center)
            if (t.allBasicsJustCompleted) Text("기본은 다 봤어요. 주행 중엔 제가 옆에서 알려드릴게요.",
                style = GuideType.body, color = c.textSub, textAlign = TextAlign.Center)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                t.finishedSummary.take(5).forEach { Text("· $it", style = GuideType.body, color = c.text) }
            }
            if (t.basicFinished && t.moreCount > 0) {
                PrimaryButton("더 알아보기 ${t.moreCount}개") { act { continueMore() } }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                if (app.content.chapters.any { it.number > (t.finishedChapter ?: Int.MAX_VALUE) }) PrimaryButton("다음 챕터") { act { chapterEndNext() } }
                SecondaryButton("홈으로") { act { tutorial.closeFinished(); show(Screen.HOME) } }
            }
        }
    }
}

// ---------------- S16 Settings ----------------

@Composable
fun SettingsScreen(app: AppController, s: Signals, act: Act, onVoice: (Boolean) -> Unit, onRate: (Float) -> Unit) {
    val st = app.store.settings
    var confirmVoiceOff by remember { mutableStateOf(false) }
    if (confirmVoiceOff) AlertDialog(
        modifier = Modifier.widthIn(max = 800.dp).fillMaxWidth(.9f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        containerColor = c.surface,
        onDismissRequest = { confirmVoiceOff = false },
        title = { Text("음성 안내를 끌까요?", style = GuideType.title) },
        text = { Text("음성을 끄면 화면을 보지 못할 때 안내를 놓칠 수 있어요.", style = GuideType.body) },
        confirmButton = { TextButton(onClick = {
            confirmVoiceOff = false
            act { store.settings = store.settings.copy(voiceOn = false); voice.clear() }
            onVoice(false)
        }) { Text("끄기", style = GuideType.label) } },
        dismissButton = { TextButton(onClick = { confirmVoiceOff = false }) { Text("계속 켜기", style = GuideType.label) } },
    )
    Screenful {
        TopBar("설정", s, onBack = { act { back() } })
        // A readable column in the middle of a wide screen rather than a strip down its left edge.
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp)
            .wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Choice("주행 중 안내", listOf("켜기" to true, "끄기" to false), st.coachingOn) { v ->
                act { store.settings = store.settings.copy(coachingOn = v) }
            }
            Text("끄면 차가 한 일과 경고등 해석만 알려드려요.", style = GuideType.caption, color = c.textSub)
            Choice("안내 단계", listOf("초보" to GuideLevel.BEGINNER, "익숙" to GuideLevel.FAMILIAR, "최소" to GuideLevel.MINIMAL), st.guideLevel) { v ->
                act { store.settings = store.settings.copy(guideLevel = v) }
            }
            Choice("음성 안내", listOf("켜기" to true, "끄기" to false), st.voiceOn) { v ->
                if (!v && st.voiceOn) confirmVoiceOff = true
                else { act { store.settings = store.settings.copy(voiceOn = v) }; onVoice(v) }
            }
            Choice("말하는 빠르기", listOf("느리게" to .9f, "보통" to 1.0f, "빠르게" to 1.1f), st.speechRate) { v -> act { store.settings = store.settings.copy(speechRate = v) }; onRate(v) }
            Choice("내비 음성과 함께", listOf("가이드 우선" to true, "둘 다" to false), st.navDedupe) { v -> act { store.settings = store.settings.copy(navDedupe = v) } }
            Choice("화면 테마", listOf("차량 따라가기" to ThemeMode.VEHICLE, "다크" to ThemeMode.DARK, "라이트" to ThemeMode.LIGHT), st.theme) { v -> act { store.settings = store.settings.copy(theme = v) } }
            Vspace(24.dp)
        }
    }
}

@Composable
private fun <T> Choice(title: String, options: List<Pair<String, T>>, selected: T, onPick: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = GuideType.title, color = c.text)
        // One connected control: equal segments on a shared track, the chosen one filled.
        Row(Modifier.clip(RoundedCornerShape(24.dp)).background(c.surface2).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (label, v) ->
                val on = v == selected
                Box(
                    Modifier.widthIn(min = 150.dp).heightIn(min = 72.dp).clip(RoundedCornerShape(18.dp))
                        .background(if (on) c.info else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { onPick(v) }.padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(label, style = GuideType.button, color = if (on) androidx.compose.ui.graphics.Color.White else c.textSub) }
            }
        }
    }
}

