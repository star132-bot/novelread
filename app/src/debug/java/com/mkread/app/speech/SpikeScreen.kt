package com.mkread.app.speech

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

const val SPIKE_SENTENCE = "窗外下着小雨，她轻声说：“我们回家吧。”"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpikeScreen(viewModel: SpikeViewModel = viewModel()) {
    val state = viewModel.state
    val isWorking = state is SpikeState.Installing || state is SpikeState.Generating
    var text by rememberSaveable { mutableStateOf(SPIKE_SENTENCE) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(title = { Text("MKread 离线语音验证") })
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 152.dp),
                    enabled = !isWorking,
                    label = { Text("朗读文本") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                )

                if (isWorking) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                StatusBlock(state)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = { viewModel.generateAndPlay(text) },
                        modifier = Modifier.weight(1f),
                        enabled = !isWorking && text.isNotBlank(),
                    ) {
                        Text(if (state is SpikeState.Failed) "重试生成" else "生成并试听")
                    }
                    OutlinedButton(
                        onClick = viewModel::replay,
                        modifier = Modifier.weight(1f),
                        enabled = state is SpikeState.Ready,
                    ) {
                        Text("重播")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBlock(state: SpikeState) {
    val title = when (state) {
        SpikeState.Idle -> "等待生成"
        SpikeState.Installing -> "正在安装语音资源"
        SpikeState.Generating -> "正在生成"
        is SpikeState.Ready -> "生成完成"
        is SpikeState.Failed -> "生成失败"
    }
    Text(text = title, style = MaterialTheme.typography.titleMedium)

    when (state) {
        is SpikeState.Ready -> Text(
            text = "音频 ${state.durationMs} ms · 生成 ${state.generationMs} ms",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is SpikeState.Failed -> Text(
            text = state.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )

        else -> Unit
    }
}
