package com.mkread.app.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** App-wide update prompt; renders nothing while there is nothing to tell the user. */
@Composable
fun UpdateDialog(updater: AppUpdater) {
    val state by updater.state.collectAsState()
    when (val current = state) {
        UpdateState.Idle, UpdateState.Checking -> Unit
        UpdateState.UpToDate -> MessageDialog("已是最新版本", "当前版本已经是最新的。", updater::dismissMessage)
        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = updater::dismissMessage,
            title = { Text("更新没有完成") },
            text = { Text(current.message) },
            confirmButton = {
                if (current.release != null) {
                    TextButton(onClick = updater::startUpdate) { Text("重试") }
                }
            },
            dismissButton = { TextButton(onClick = updater::dismissMessage) { Text("关闭") } },
        )
        is UpdateState.Available -> {
            val release = current.release
            AlertDialog(
                onDismissRequest = { if (!release.mandatory) updater.postpone() },
                properties = DialogProperties(
                    dismissOnBackPress = !release.mandatory,
                    dismissOnClickOutside = !release.mandatory,
                ),
                title = { Text("发现新版本 ${release.versionName}") },
                text = {
                    Column(
                        Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = "安装包 ${(release.apkSize + 524_288L) / 1_048_576L} MB。更新后书架、阅读进度和已下载的音色都会保留。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (release.notes.isNotBlank()) {
                            Text(
                                text = release.notes,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                        if (release.mandatory) {
                            Text(
                                text = "当前版本已停止支持，请更新后继续使用。",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = updater::startUpdate, modifier = Modifier.testTag("update-now")) {
                        Text("立即更新")
                    }
                },
                dismissButton = {
                    if (!release.mandatory) TextButton(onClick = updater::postpone) { Text("稍后") }
                },
            )
        }
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("正在下载 ${current.release.versionName}") },
            text = {
                Column {
                    Text("${(current.progress * 100).toInt()}%")
                    LinearProgressIndicator(
                        progress = { current.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {},
        )
        is UpdateState.NeedsInstallPermission -> AlertDialog(
            onDismissRequest = {},
            title = { Text("需要允许安装应用") },
            text = { Text("请在接下来的设置页打开「允许来自此来源的应用」，返回后会自动继续安装。") },
            confirmButton = { TextButton(onClick = updater::openInstallPermissionSettings) { Text("去设置") } },
            dismissButton = { TextButton(onClick = updater::dismissMessage) { Text("取消") } },
        )
        is UpdateState.Installing -> Unit
    }
}

@Composable
private fun MessageDialog(title: String, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("好") } },
    )
}
