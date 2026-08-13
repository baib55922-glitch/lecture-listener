package com.lecturelistener.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lecturelistener.asr.ModelManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val modelState by vm.modelState.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var keyVisible by remember { mutableStateOf(false) }
    val modelReady = ModelManager.isReady(context)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SectionTitle("DeepSeek API")
            OutlinedTextField(
                value = settings.apiKey,
                onValueChange = { vm.updateSettings(settings.copy(apiKey = it)) },
                label = { Text("API Key") },
                placeholder = { Text("sk-…") },
                singleLine = true,
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "显示/隐藏",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { vm.testConnection() },
                    enabled = testing != MainViewModel.TestState.Running,
                ) {
                    Text(if (testing == MainViewModel.TestState.Running) "测试中…" else "测试连接")
                }
                Spacer(Modifier.width(12.dp))
                val result = testing as? MainViewModel.TestState.Result
                if (result != null) {
                    Text(
                        result.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (result.ok) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(
                "Key 仅保存在本机加密存储中，只用于调用 DeepSeek API（deepseek-chat）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            SectionTitle("识别模型（中文优化 · Paraformer 双语流式）")
            Text(
                "阿里达摩院 Paraformer 体系，中英混合识别，准确率远超旧版 Vosk；" +
                    "三个文件合计约 226MB，首次使用自动从国内镜像下载（约 1~2 分钟）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (val m = modelState) {
                is ModelManager.ModelState.Downloading -> {
                    LinearProgressIndicator(
                        progress = { m.pct / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                    Text(
                        "正在下载模型 ${m.pct}%（建议连接 WiFi）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is ModelManager.ModelState.Error -> {
                    Text(
                        "模型问题：${m.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { vm.downloadSelectedModel() }) { Text("重试下载") }
                }
                else -> {
                    if (modelReady) {
                        Text(
                            "✓ 当前模型已就绪",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Text(
                            "当前模型尚未下载",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = { vm.downloadSelectedModel() },
                            enabled = modelState !is ModelManager.ModelState.Downloading,
                        ) {
                            Text("下载识别模型（约 226MB）")
                        }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            SectionTitle("断句灵敏度（静音多久算一句话说完）")
            Slider(
                value = settings.silenceMs.toFloat(),
                onValueChange = { vm.updateSettings(settings.copy(silenceMs = it.toInt())) },
                valueRange = 500f..2500f,
                steps = 19,
            )
            Text(
                "当前：${settings.silenceMs / 1000f}s（课堂噪音大建议调大）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )

            SwitchRow(
                title = "严格判定模式",
                subtitle = "所有句子都交给 LLM 判断，不易漏题，但出卡稍慢",
                checked = settings.strictMode,
                onChecked = { vm.updateSettings(settings.copy(strictMode = it)) },
            )
            SwitchRow(
                title = "识别到提问时震动提醒",
                subtitle = "关闭后仍会在通知栏/灵动岛显示",
                checked = settings.vibrate,
                onChecked = { vm.updateSettings(settings.copy(vibrate = it)) },
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            SectionTitle("后台保活（重要）")
            Text(
                "为确保息屏后仍持续监听，请在系统设置中：\n" +
                    "· 小米/Redmi：设置→应用设置→应用管理→本应用→自启动；省电策略→无限制\n" +
                    "· 华为/荣耀：设置→应用→应用启动管理→手动管理→全部允许\n" +
                    "· OPPO/一加：设置→电池→应用耗电管理→允许后台运行\n" +
                    "· vivo/iQOO：i管家→应用管理→权限→自启动；后台高耗电→允许",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.requestIgnoreBatteryOptimizations(context) }) {
                Text("一键申请忽略电池优化")
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            TextButton(onClick = { vm.clearSession() }) { Text("清空本次会话") }

            Text(
                "版本 0.1.0 · 转写在本地进行，问题判定与答案由 DeepSeek 生成 · 仅供课堂学习辅助",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
