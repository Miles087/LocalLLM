// ModelLoaderScreen.kt
package com.miles.localllm

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miles.localllm.operator.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ModelLoaderScreen(
    onModelLoaded: (String) -> Unit,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var modelPath by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var localModels by remember { mutableStateOf(listOf<File>()) }
    var selectedFile by remember { mutableStateOf<File?>(null) }

    // 刷新本地模型列表
    fun refreshModels() {
        localModels = ModelManager.getLocalModels(context)
    }

    LaunchedEffect(Unit) {
        refreshModels()
    }

    // 文件选择器
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            isLoading = true
            statusText = "正在导入模型..."

            scope.launch(Dispatchers.IO) {
                val fileName = getFileName(context, uri) ?: "model_${System.currentTimeMillis()}.gguf"
                val result = ModelManager.importModel(context, uri, fileName)

                withContext(Dispatchers.Main) {
                    isLoading = false
                    result.onSuccess { path ->
                        statusText = "导入成功"
                        refreshModels()
                        selectedFile = File(path)
                    }.onFailure { e ->
                        statusText = "导入失败: ${e.message}"
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp)
    ) {
        Text(
            text = "本地模型管理",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // 手动路径输入（高级用户）
        OutlinedTextField(
            value = modelPath,
            onValueChange = { modelPath = it },
            label = { Text("或直接输入模型路径") },
            placeholder = { Text("/storage/emulated/0/Android/data/.../model.gguf") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 操作按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { filePicker.launch("*/*") },
                modifier = Modifier.weight(1f),
                enabled = !isLoading
            ) {
                Text("选择文件")
            }

            Button(
                onClick = {
                    val path = if (selectedFile != null) {
                        selectedFile!!.absolutePath
                    } else modelPath

                    if (path.isNotBlank()) {
                        onModelLoaded(path)
                    } else {
                        statusText = "请先选择模型文件"
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !isLoading && (selectedFile != null || modelPath.isNotBlank())
            ) {
                Text("加载模型")
            }
        }

        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            )
        }

        if (statusText.isNotBlank()) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 本地模型列表
        Text(
            text = "已导入的模型 (${localModels.size})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (localModels.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无本地模型\n点击上方按钮导入 GGUF 文件",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(localModels) { file ->
                    ModelFileItem(
                        file = file,
                        isSelected = selectedFile?.absolutePath == file.absolutePath,
                        onSelect = { selectedFile = file },
                        onDelete = {
                            ModelManager.deleteModel(context, file.name)
                            if (selectedFile?.absolutePath == file.absolutePath) {
                                selectedFile = null
                            }
                            refreshModels()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ModelFileItem(
    file: File,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        onClick = onSelect,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatFileSize(file.length())} • ${file.absolutePath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isSelected) {
                Text(
                    text = "已选择",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            TextButton(onClick = onDelete) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// 辅助函数
private fun getFileName(context: android.content.Context, uri: Uri): String? {
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    return cursor?.use {
        val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (it.moveToFirst() && nameIndex != -1) {
            it.getString(nameIndex)
        } else null
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1L shl 30 -> String.format("%.2f GB", bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> String.format("%.2f MB", bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> String.format("%.2f KB", bytes.toDouble() / (1L shl 10))
        else -> "$bytes B"
    }
}
