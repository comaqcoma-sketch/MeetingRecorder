package com.mplan.meetingrecorder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { MeetingRecorderScreen() } }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme: ColorScheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingRecorderScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rec by RecorderService.state.collectAsStateWithLifecycle()
    val tr by TranscribeService.state.collectAsStateWithLifecycle()
    var openDetail by remember { mutableStateOf<Recording?>(null) }
    var askModelFor by remember { mutableStateOf<Recording?>(null) }
    val snackbar = remember { SnackbarHostState() }

    var recordings by remember { mutableStateOf<List<Recording>>(emptyList()) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var toDelete by remember { mutableStateOf<Recording?>(null) }

    val player = remember { Player() }
    var playingPath by remember { mutableStateOf<String?>(null) }
    var playPos by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) { onDispose { player.release() } }

    // 목록 불러오기 (녹음 저장·삭제 시 새로고침)
    LaunchedEffect(rec.savedCount, rec.filePath, reloadKey, tr.doneCount) {
        recordings = RecordingStore.list(ctx, excludePath = rec.filePath)
    }
    // 재생 위치 갱신
    LaunchedEffect(playingPath) {
        while (playingPath != null) { playPos = player.positionMs(); delay(250) }
    }
    // 오류 메시지
    LaunchedEffect(rec.error) {
        rec.error?.let { snackbar.showSnackbar(it); RecorderService.clearError() }
    }
    LaunchedEffect(tr.error) {
        tr.error?.let { snackbar.showSnackbar(it); TranscribeService.clearError() }
    }
    // 변환 완료 알림
    LaunchedEffect(tr.doneCount) {
        if (tr.doneCount > 0) snackbar.showSnackbar("텍스트 변환·요약이 완료됐습니다.")
    }
    // 녹음 시작하면 재생 중지 (마이크·스피커 충돌 방지)
    LaunchedEffect(rec.status) {
        if (rec.status != RecStatus.IDLE && playingPath != null) { player.release(); playingPath = null }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            RecorderService.send(ctx, RecorderService.ACTION_START)
        } else {
            scope.launch { snackbar.showSnackbar("녹음하려면 마이크 권한이 필요합니다.") }
        }
    }

    fun startTranscribe(r: Recording) {
        if (ModelManager.selected(ctx) == null) askModelFor = r
        else TranscribeService.start(ctx, r.path)
    }

    fun startWithPermission() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) RecorderService.send(ctx, RecorderService.ACTION_START)
        else permLauncher.launch(needed.toTypedArray())
    }

    val detail = openDetail
    if (detail != null) {
        DetailScreen(detail, onBack = { openDetail = null })
        return
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("회의 녹음") }) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                RecorderCard(
                    state = rec,
                    onStart = { startWithPermission() },
                    onPause = { RecorderService.send(ctx, RecorderService.ACTION_PAUSE) },
                    onResume = { RecorderService.send(ctx, RecorderService.ACTION_RESUME) },
                    onStop = { RecorderService.send(ctx, RecorderService.ACTION_STOP) }
                )
            }
            item {
                Text(
                    "녹음 목록 (${recordings.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            if (recordings.isEmpty()) {
                item {
                    Text(
                        "아직 녹음이 없습니다.\n위의 버튼으로 회의 녹음을 시작하세요.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp)
                    )
                }
            }
            items(recordings, key = { it.path }) { r ->
                RecordingItem(
                    rec = r,
                    isPlaying = playingPath == r.path,
                    playPosMs = if (playingPath == r.path) playPos else 0,
                    canPlay = rec.status == RecStatus.IDLE,
                    onPlay = {
                        val started = player.toggle(r.path) { playingPath = null }
                        playingPath = if (started) r.path else null
                    },
                    onShare = { RecordingStore.share(ctx, r) },
                    onDelete = { toDelete = r },
                    transcribing = tr.path == r.path,
                    transcribeBusy = tr.path != null,
                    phase = tr.phase,
                    progress = tr.progress,
                    onTranscribe = { startTranscribe(r) },
                    onCancelTranscribe = { TranscribeService.cancel(ctx) },
                    onOpen = {
                        if (playingPath != null) { player.release(); playingPath = null }
                        openDetail = r
                    }
                )
            }
        }
    }

    toDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("녹음 삭제") },
            text = { Text("${r.title} 녹음을 삭제할까요?\n삭제하면 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    if (playingPath == r.path) { player.release(); playingPath = null }
                    RecordingStore.delete(ctx, r)
                    toDelete = null
                    reloadKey++
                }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("취소") } }
        )
    }

    askModelFor?.let { r ->
        ModelChoiceDialog(
            onPick = { m ->
                ModelManager.select(ctx, m)
                askModelFor = null
                TranscribeService.start(ctx, r.path)
            },
            onDismiss = { askModelFor = null }
        )
    }
}

@Composable
fun ModelChoiceDialog(onPick: (WhisperModel) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("변환 방식 선택") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "녹음은 폰 밖으로 나가지 않고, 폰 안에서 텍스트로 바뀝니다. " +
                        "처음 한 번만 변환 모델을 내려받습니다 (Wi-Fi 권장).",
                    style = MaterialTheme.typography.bodyMedium
                )
                WhisperModel.entries.forEach { m ->
                    OutlinedButton(onClick = { onPick(m) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text("${m.label} · ${m.sizeMb}MB", style = MaterialTheme.typography.titleSmall)
                            Text(m.desc, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable
fun RecorderCard(
    state: RecState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val label = when (state.status) {
                RecStatus.IDLE -> "대기 중"
                RecStatus.RECORDING -> "● 녹음 중"
                RecStatus.PAUSED -> "일시정지됨"
            }
            Text(
                label,
                color = if (state.status == RecStatus.RECORDING) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                formatElapsed(state.elapsedMs),
                fontSize = 52.sp,
                fontWeight = FontWeight.Light,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            LinearProgressIndicator(
                progress = { if (state.status == RecStatus.RECORDING) state.level else 0f },
                modifier = Modifier.fillMaxWidth().height(6.dp)
            )
            Spacer(Modifier.height(20.dp))

            when (state.status) {
                RecStatus.IDLE -> Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("녹음 시작", fontSize = 18.sp) }

                else -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = if (state.status == RecStatus.RECORDING) onPause else onResume,
                        modifier = Modifier.weight(1f).height(56.dp)
                    ) { Text(if (state.status == RecStatus.RECORDING) "일시정지" else "재개", fontSize = 17.sp) }
                    Button(
                        onClick = onStop,
                        modifier = Modifier.weight(1f).height(56.dp)
                    ) { Text("정지·저장", fontSize = 17.sp) }
                }
            }
        }
    }
}

@Composable
fun RecordingItem(
    rec: Recording,
    isPlaying: Boolean,
    playPosMs: Int,
    canPlay: Boolean,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    transcribing: Boolean,
    transcribeBusy: Boolean,
    phase: String,
    progress: Int,
    onTranscribe: () -> Unit,
    onCancelTranscribe: () -> Unit,
    onOpen: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(rec.title, style = MaterialTheme.typography.titleSmall)
            val mb = rec.sizeBytes / 1024f / 1024f
            Text(
                if (isPlaying) "${formatElapsed(playPosMs.toLong())} / ${formatElapsed(rec.durationMs)}"
                else "${formatElapsed(rec.durationMs)} · ${String.format(Locale.KOREA, "%.1f", mb)}MB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isPlaying && rec.durationMs > 0) {
                LinearProgressIndicator(
                    progress = { (playPosMs / rec.durationMs.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, end = 8.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            when {
                transcribing -> {
                    Text("$phase · $progress%", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, end = 8.dp)
                    )
                    Text("화면을 꺼도 계속 진행됩니다.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
                rec.hasText -> FilledTonalButton(
                    onClick = onOpen, modifier = Modifier.fillMaxWidth().padding(end = 8.dp)
                ) { Text("📝 회의록 보기 (요약·전체 텍스트)") }
                else -> OutlinedButton(
                    onClick = onTranscribe, enabled = !transcribeBusy,
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp)
                ) { Text(if (transcribeBusy) "다른 녹음 변환 중…" else "텍스트 변환 + 요약") }
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (transcribing) {
                    TextButton(onClick = onCancelTranscribe) { Text("변환 취소") }
                } else {
                    TextButton(onClick = onPlay, enabled = canPlay) { Text(if (isPlaying) "■ 중지" else "▶ 재생") }
                    TextButton(onClick = onShare) { Text("공유") }
                    TextButton(onClick = onDelete) { Text("삭제", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}
