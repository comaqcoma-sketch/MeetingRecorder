package com.mplan.meetingrecorder

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 화자별 구분 색 (밝은·어두운 화면 모두 잘 보이는 색) */
private val speakerColors = listOf(
    Color(0xFF3B82F6), Color(0xFFF59E0B), Color(0xFF10B981), Color(0xFFEC4899),
    Color(0xFF8B5CF6), Color(0xFFEF4444), Color(0xFF14B8A6), Color(0xFF84CC16)
)

private fun speakerColor(i: Int) = speakerColors[i % speakerColors.size]

private val timeRegex = Regex("""\[(?:(\d+):)?(\d{1,2}):(\d{2})]""")

/** "[01:23]" 또는 "[1:02:03]" → ms */
private fun parseTime(line: String): Long? {
    val m = timeRegex.find(line) ?: return null
    val h = m.groupValues[1].toLongOrNull() ?: 0L
    val mm = m.groupValues[2].toLongOrNull() ?: return null
    val ss = m.groupValues[3].toLongOrNull() ?: return null
    return ((h * 60 + mm) * 60 + ss) * 1000
}

/** 회의록 화면: 요약 / 전체 텍스트. 문장을 누르면 그 위치부터 재생 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(rec: Recording, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var summary by remember { mutableStateOf("") }
    var segments by remember { mutableStateOf<List<Segment>>(emptyList()) }

    val player = remember { Player() }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) { onDispose { player.release() } }
    BackHandler { onBack() }

    val tr by TranscribeService.state.collectAsStateWithLifecycle()
    val working = tr.path == rec.path

    LaunchedEffect(rec.path, tr.doneCount) {
        summary = RecordingStore.loadSummary(rec.path)
        segments = RecordingStore.loadTranscript(rec.path)
    }
    LaunchedEffect(playing) {
        while (playing) { pos = player.positionMs(); delay(300) }
    }

    fun playAt(ms: Long) {
        playing = player.playFrom(rec.path, ms) { playing = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(rec.title, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ 목록") } },
                actions = {
                    TextButton(onClick = {
                        scope.launch { RecordingStore.shareText(ctx, rec.title, RecordingStore.exportText(rec.path)) }
                    }) { Text("공유") }
                }
            )
        },
        bottomBar = {
            if (playing) {
                BottomAppBar {
                    Text(
                        "▶ ${formatElapsed(pos.toLong())} / ${formatElapsed(rec.durationMs)}",
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f).padding(start = 16.dp)
                    )
                    TextButton(onClick = { player.release(); playing = false }) { Text("■ 정지") }
                }
            }
        }
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            if (working) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("${tr.phase} · ${tr.progress}%", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                    LinearProgressIndicator(progress = { tr.progress / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    Text("설정의 '최고 정확' 모델은 시간이 더 걸립니다. 화면을 꺼도 계속 진행됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (!working) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        enabled = tr.path == null,
                        modifier = Modifier.weight(1f),
                        onClick = { player.release(); playing = false; TranscribeService.start(ctx, rec.path) }
                    ) { Text("음성 다시 변환") }
                    OutlinedButton(
                        enabled = tr.path == null,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (ModelManager.aiSummary(ctx) != true) ModelManager.setAiSummary(ctx, true)
                            TranscribeService.start(ctx, rec.path, summaryOnly = true)
                            tab = 0
                        }
                    ) { Text("AI 다시 요약") }
                }
            }
            if (tr.error != null && tr.path == null) {
                Text(tr.error ?: "", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
            }
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("요약") })
                val spk = segments.map { it.speaker }.filter { it >= 0 }.distinct().size
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = {
                    Text(if (spk >= 2) "전체 텍스트 · ${spk}명" else "전체 텍스트 (${segments.size})")
                })
            }
            if (tab == 0) SummaryView(summary, onTime = { playAt(it) })
            else TranscriptView(segments, if (playing) pos.toLong() else -1L, onTap = { playAt(it) })
        }
    }
}

@Composable
private fun SummaryView(md: String, onTime: (Long) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        val lines = md.lines()
        itemsIndexed(lines) { _, raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> {}
                line.startsWith("# ") -> Text(
                    line.removePrefix("# "), style = MaterialTheme.typography.titleLarge
                )
                line.startsWith("## ") -> Text(
                    line.removePrefix("## "),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 14.dp)
                )
                line.startsWith("- ") -> {
                    val body = line.removePrefix("- ").replace("[ ] ", "☐ ")
                    val t = parseTime(body)
                    Text(
                        "• $body",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth()
                            .then(if (t != null) Modifier.clickable { onTime(t) } else Modifier)
                            .padding(vertical = 2.dp)
                    )
                }
                else -> Text(line, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Text(
                "※ 폰 안에서 만든 요약입니다. 중요한 내용은 전체 텍스트에서 한 번 더 확인하세요. 시간이 표시된 줄을 누르면 그 부분부터 재생됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 20.dp)
            )
        }
    }
}

@Composable
private fun TranscriptView(segments: List<Segment>, posMs: Long, onTap: (Long) -> Unit) {
    if (segments.isEmpty()) {
        Text("변환된 텍스트가 없습니다.", modifier = Modifier.padding(24.dp))
        return
    }
    val listState = rememberLazyListState()
    val currentIdx = if (posMs < 0) -1 else segments.indexOfLast { it.startMs <= posMs }
    // 재생 중이면 현재 문장으로 따라가기
    LaunchedEffect(currentIdx) {
        if (currentIdx >= 0) listState.animateScrollToItem((currentIdx - 2).coerceAtLeast(0))
    }
    LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
        itemsIndexed(segments) { i, s ->
            val active = i == currentIdx
            Row(
                Modifier.fillMaxWidth()
                    .then(if (active) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
                    .clickable { onTap(s.startMs) }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    formatElapsed(s.startMs),
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(64.dp).padding(top = 2.dp)
                )
                if (s.speaker >= 0) {
                    Text(
                        SpeakerDiarizer.letter(s.speaker) + ":",
                        color = speakerColor(s.speaker),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(28.dp)
                    )
                }
                Text(
                    s.text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}
