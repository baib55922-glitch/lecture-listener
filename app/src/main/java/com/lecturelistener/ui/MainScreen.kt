package com.lecturelistener.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lecturelistener.R
import com.lecturelistener.asr.ModelManager
import com.lecturelistener.data.ServiceState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatTime(ts: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts))

@Composable
fun AppRoot(vm: MainViewModel) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsScreen(vm, onBack = { showSettings = false })
    } else {
        MainScreen(vm, onOpenSettings = { showSettings = true })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val serviceState by vm.serviceState.collectAsStateWithLifecycle()
    val serviceError by vm.serviceError.collectAsStateWithLifecycle()
    val segments by vm.segments.collectAsStateWithLifecycle()
    val partial by vm.partial.collectAsStateWithLifecycle()
    val modelState by vm.modelState.collectAsStateWithLifecycle()

    var hasMic by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        hasMic = grants[Manifest.permission.RECORD_AUDIO] == true
    }
    val requestPermissions = {
        val perms = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }
        permissionLauncher.launch(perms)
    }

    val listening = serviceState == ServiceState.LISTENING || serviceState == ServiceState.LOADING_MODEL

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name))
                        Text(
                            when (serviceState) {
                                ServiceState.LISTENING -> "监听中 · 已识别 ${segments.size} 句"
                                ServiceState.LOADING_MODEL -> "正在加载识别模型…"
                                ServiceState.ERROR -> "已停止（异常）"
                                ServiceState.IDLE -> "未监听"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    StatusDot(serviceState)
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            when {
                serviceState == ServiceState.LOADING_MODEL ->
                    StatusBanner(
                        text = when (val m = modelState) {
                            is ModelManager.ModelState.Downloading -> "正在下载识别模型 ${m.pct}%"
                            is ModelManager.ModelState.Unzipping -> "正在解压模型…"
                            else -> "正在加载识别模型…"
                        },
                        color = MaterialTheme.colorScheme.primaryContainer,
                    )
                serviceState == ServiceState.ERROR ->
                    StatusBanner(
                        text = "监听异常：${serviceError ?: "未知错误"}（可重试或查看设置）",
                        color = MaterialTheme.colorScheme.errorContainer,
                    )
            }

            TranscriptPanel(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                segments = segments,
                partial = partial,
            )

            CardsPanel(
                modifier = Modifier
                    .weight(0.85f)
                    .fillMaxWidth(),
                vm = vm,
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        if (listening) vm.stopListening(context) else vm.startListening(context)
                    },
                    enabled = hasMic,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        if (listening) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (listening) "停止监听" else "开始监听")
                }
                if (!hasMic) {
                    TextButton(onClick = requestPermissions) { Text("授权麦克风") }
                } else {
                    IconButton(onClick = { vm.clearSession() }) {
                        Icon(Icons.Default.Delete, contentDescription = "清空会话")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusDot(state: ServiceState) {
    val color = when (state) {
        ServiceState.LISTENING -> Color(0xFF2EBD59)
        ServiceState.LOADING_MODEL -> Color(0xFFF5A623)
        ServiceState.ERROR -> Color(0xFFE23D3D)
        ServiceState.IDLE -> MaterialTheme.colorScheme.outline
    }
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun StatusBanner(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(color)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun TranscriptPanel(
    modifier: Modifier,
    segments: List<com.lecturelistener.data.TranscriptSegment>,
    partial: String,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(segments.size, partial) {
        if (segments.isNotEmpty() || partial.isNotBlank()) {
            listState.animateScrollToItem(segments.size)
        }
    }
    Box(modifier) {
        if (segments.isEmpty() && partial.isBlank()) {
            Text(
                text = "点击下方「开始监听」\n课堂内容将实时转写在这里",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                items(segments.size + 1) { idx ->
                    if (idx < segments.size) {
                        val seg = segments[idx]
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(
                                seg.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                formatTime(seg.time),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    } else if (partial.isNotBlank()) {
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(
                                "● $partial",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}
