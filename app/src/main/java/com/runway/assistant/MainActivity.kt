package com.runway.assistant

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.runway.assistant.data.RecordingStore
import com.runway.assistant.recorder.RecorderService
import com.runway.assistant.replay.EngineState
import com.runway.assistant.replay.JoinPlanner
import com.runway.assistant.replay.PrivilegedReplayEngine
import com.runway.assistant.replay.ReplayScheduler
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

private val TabTitles = listOf("首页", "录制", "回放", "关于")

private val RequiredPermissions = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    add(Manifest.permission.BODY_SENSORS)
    add(Manifest.permission.ACTIVITY_RECOGNITION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                MainScreen()
            }
        }
    }
}

@Composable
private fun MainScreen() {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf("就绪") }
    var sessions by remember { mutableStateOf(RecordingStore.listSessions(context)) }
    var selected by remember { mutableStateOf<File?>(sessions.firstOrNull()) }
    var events by remember { mutableStateOf<List<JSONObject>>(emptyList()) }

    val engine = remember { PrivilegedReplayEngine() }
    val engineState by engine.state.collectAsState()
    val scope = rememberCoroutineScope()
    val scheduler = remember { mutableStateOf<ReplayScheduler?>(null) }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val missing = result.filterValues { !it }.keys
        message = if (missing.isEmpty()) "权限已就绪" else "缺少权限：${missing.joinToString()}"
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                TabTitles.forEachIndexed { index, title ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = {},
                        label = { Text(title) },
                    )
                }
            }
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            when (tab) {
                0 -> HomeTab(message) {
                    permissionLauncher.launch(RequiredPermissions.toTypedArray())
                }
                1 -> RecordTab(message) { action ->
                    when (action) {
                        "start" -> RecorderService.start(context)
                        "stop" -> RecorderService.stop(context)
                        "mark_start" -> RecorderService.markRunStart(context)
                        "mark_end" -> RecorderService.markRunEnd(context)
                    }
                    message = "录制指令：$action"
                }
                2 -> ReplayTab(
                    sessions = sessions,
                    selected = selected,
                    engineState = engineState,
                    message = message,
                    onRefresh = {
                        sessions = RecordingStore.listSessions(context)
                        selected = sessions.firstOrNull()
                        message = "素材列表已刷新（${sessions.size} 份）"
                    },
                    onSelect = { dir ->
                        selected = dir
                        events = RecordingStore.readEvents(dir)
                        message = "已选择：${dir.name}，事件 ${events.size} 条"
                    },
                    onPrepare = {
                        val dir = selected ?: return@ReplayTab
                        scope.launch {
                            engine.prepare(dir).onSuccess {
                                events = RecordingStore.readEvents(dir)
                                message = "素材就绪：${dir.name}"
                            }.onFailure { message = "准备失败：${it.message}" }
                        }
                    },
                    onStart = {
                        val dir = selected ?: return@ReplayTab
                        val join = JoinPlanner.defaultJoinMs(events)
                        val plan = JoinPlanner.plan(events, requiredM = 3000.0, requestedMs = join)
                        plan.onSuccess { p ->
                            val s = ReplayScheduler(engine, events)
                            scheduler.value = s
                            scope.launch {
                                engine.start(p.offsetMs).onSuccess {
                                    s.beginMain(scope, p.offsetMs)
                                    message = "回放中，接入点 ${p.offsetMs} ms，剩余 %.0f m".format(p.remainingMeters)
                                }.onFailure { message = "启动失败：${it.message}" }
                            }
                        }.onFailure { message = "接入规划失败：${it.message}" }
                    },
                    onPause = {
                        scheduler.value?.pause()
                        scope.launch { engine.pause() }
                        message = "已暂停（注入冻结）"
                    },
                    onStop = {
                        scheduler.value?.stop()
                        scope.launch { engine.stop() }
                        message = "已停止，请复位后再开始下一次会话"
                    },
                )
                else -> AboutTab()
            }

            Spacer(Modifier.height(24.dp))
            Text("状态：$message", fontSize = 12.sp, color = Color(0xFF9AA4B2))
        }
    }
}

@Composable
private fun HomeTab(message: String, onRequestPermissions: () -> Unit) {
    val context = LocalContext.current
    SectionTitle("环境自检")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CheckRow("定位权限", hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION))
            CheckRow("身体传感器", hasPermission(context, Manifest.permission.BODY_SENSORS))
            CheckRow("活动识别", hasPermission(context, Manifest.permission.ACTIVITY_RECOGNITION))
            CheckRow("通知权限", hasPermission(context, Manifest.permission.POST_NOTIFICATIONS))
            Spacer(Modifier.height(4.dp))
            Button(onClick = onRequestPermissions) { Text("一键申请权限") }
        }
    }
    Spacer(Modifier.height(16.dp))
    SectionTitle("快速开始")
    Text(
        "1. 录制：真实跑一次，建议 5km，含 60 秒前奏。\n" +
            "2. 校验：录制结束自动生成清单与校验和。\n" +
            "3. 回放：先点“注入就绪”，再开目标客户端。\n" +
            "4. 结束：先暂停注入，再结束目标客户端，最后复位。",
        fontSize = 13.sp,
        color = Color(0xFFC7D0DB),
    )
    Spacer(Modifier.height(8.dp))
    Text("提示：$message", fontSize = 12.sp, color = Color(0xFF9AA4B2))
}

@Composable
private fun RecordTab(message: String, onAction: (String) -> Unit) {
    SectionTitle("录制控制")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { onAction("start") }, modifier = Modifier.fillMaxWidth()) {
                Text("开始记录（含 60 秒前奏）")
            }
            OutlinedButton(onClick = { onAction("mark_start") }, modifier = Modifier.fillMaxWidth()) {
                Text("标记跑步开始")
            }
            OutlinedButton(onClick = { onAction("mark_end") }, modifier = Modifier.fillMaxWidth()) {
                Text("标记跑步结束")
            }
            OutlinedButton(onClick = { onAction("stop") }, modifier = Modifier.fillMaxWidth()) {
                Text("停止记录并生成素材")
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Text("录制规范：位置 1Hz、IMU 50–100Hz、计步事件、活动识别、气压；总长建议 5km。", fontSize = 12.sp)
    Spacer(Modifier.height(6.dp))
    Text("状态：$message", fontSize = 12.sp, color = Color(0xFF9AA4B2))
}

@Composable
private fun ReplayTab(
    sessions: List<File>,
    selected: File?,
    engineState: EngineState,
    message: String,
    onRefresh: () -> Unit,
    onSelect: (File) -> Unit,
    onPrepare: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
) {
    SectionTitle("素材")
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onRefresh) { Text("刷新") }
        Spacer(Modifier.width(8.dp))
        Text(selected?.name ?: "未选择", fontSize = 13.sp, color = Color(0xFFC7D0DB))
    }
    Spacer(Modifier.height(8.dp))
    sessions.take(5).forEach { dir ->
        Text(
            text = dir.name,
            fontSize = 12.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect(dir) }
                .padding(vertical = 6.dp),
            color = if (dir == selected) Color(0xFF6EA8FE) else Color(0xFFC7D0DB),
        )
    }

    Spacer(Modifier.height(16.dp))
    SectionTitle("回放控制（引擎：${engineState.name}）")
    Button(onClick = onPrepare, modifier = Modifier.fillMaxWidth()) { Text("注入就绪 / 校验素材") }
    Spacer(Modifier.height(8.dp))
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("开始回放") }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onPause, modifier = Modifier.weight(1f)) { Text("暂停注入") }
        OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) { Text("停止并复位") }
    }
    Spacer(Modifier.height(10.dp))
    Text("操作顺序：注入就绪 → 开目标客户端 → 开始跑步 → 开始回放 → 结束前先暂停 → 复位。", fontSize = 12.sp)
    Spacer(Modifier.height(6.dp))
    Text("状态：$message", fontSize = 12.sp, color = Color(0xFF9AA4B2))
}

@Composable
private fun AboutTab() {
    SectionTitle("跑道助手 1.0")
    Text("运动数据记录与复盘工具 · alpha", fontSize = 13.sp)
    Spacer(Modifier.height(16.dp))
    SectionTitle("数据说明")
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141B29)),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("数据来源", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            SmallNote("本工具记录设备的位置、运动传感器、计步、活动识别、气压与设备状态，仅用于本地回放与复盘。")
            Text("素材限制", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            SmallNote("单份素材建议使用不超过 3 次；超过后轨迹与步频会呈现重复特征，应重新录制。")
            Text("接入限制", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            SmallNote("从“注入就绪”到“开始回放”不超过 5 分钟；剩余里程必须覆盖目标里程。")
            Text("结束约定", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            SmallNote("结束前先暂停注入；结束后复位，避免下一次会话里程不足。")
            Text("停止条件", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            SmallNote("连续 2 次出现“数据异常/成绩无效”，停止使用并回滚到上一个通过验收的配置。")
            Text("版本约定", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            SmallNote("适配版本固定后，目标客户端升级需重新验收。")
            Text("适用范围", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            SmallNote("本工具用于个人学习、运动数据研究与兼容性测试，使用者自行确认使用场景。")
        }
    }
    Spacer(Modifier.height(16.dp))
    Text("开源许可：MIT", fontSize = 12.sp, color = Color(0xFF9AA4B2))
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun SmallNote(text: String) {
    Text(text, fontSize = 11.sp, color = Color(0xFF9AA4B2), lineHeight = 16.sp)
}

@Composable
private fun CheckRow(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (ok) "●" else "○",
            color = if (ok) Color(0xFF3DDC84) else Color(0xFFFF6B6B),
            fontSize = 12.sp,
        )
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 13.sp)
    }
}

private fun hasPermission(context: android.content.Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
