package com.mkread.app.cloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudLibraryScreen(library: CloudLibrary, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val status by library.status.collectAsState()
    var session by remember { mutableStateOf(library.auth.session) }
    var serverUrl by remember { mutableStateOf("") }
    var lastSynced by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(library) {
        val settings = library.refreshSettings()
        serverUrl = settings.serverUrl
        lastSynced = library.lastSyncedAt()
    }
    LaunchedEffect(status.lastSyncedAt) { status.lastSyncedAt?.let { lastSynced = it } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("云端书库") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("账号", style = MaterialTheme.typography.titleMedium)
                    val current = session
                    if (current != null) {
                        Text("已登录：${current.displayName ?: current.subject}")
                        OutlinedButton(onClick = {
                            scope.launch {
                                library.auth.signOut()
                                session = null
                            }
                        }) { Text("退出登录") }
                    } else {
                        Text(
                            "使用 MKauth 账号登录后，可以自动获取云端书库里的新书和更新。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                message = null
                                scope.launch {
                                    session = runCatching { library.auth.signIn() }
                                        .onFailure { message = "登录失败：${it.message}" }
                                        .getOrNull()
                                    busy = false
                                    if (session != null) library.requestSyncNow()
                                }
                            },
                        ) { Text("使用 MKauth 登录") }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("同步", style = MaterialTheme.typography.titleMedium)
                    if (status.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        text = status.message ?: lastSynced?.let {
                            "上次同步：${DateFormat.getDateTimeInstance().format(Date(it))}"
                        } ?: "还没有同步过",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "联网时每 6 小时自动同步一次，新书会放进「${CloudLibrary.CLOUD_FOLDER_NAME}」书架文件夹。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        enabled = !status.running,
                        onClick = { scope.launch { runCatching { library.sync() } } },
                    ) { Text("立即同步") }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("服务器", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text("云端书库地址") },
                        placeholder = { Text("https://…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                library.saveSettings(serverUrl)
                                session = library.auth.session
                                message = "已保存"
                            }
                        }) { Text("保存") }
                    }
                }
            }

            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
