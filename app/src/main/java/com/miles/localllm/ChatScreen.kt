package com.miles.localllm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.miles.localllm.operator.LlamaInference
import kotlinx.coroutines.delay

// ChatScreen.kt

// 开启内部图片的固定密码
private const val INTERNAL_IMAGE_PASSWORD = "123321"

@Composable
fun ChatScreen(inference: LlamaInference) {
    val context = LocalContext.current

    // 返回控制：2秒内连按两次返回才退出，防止误触直接退出程序
    var lastBackTime by remember { mutableStateOf(0L) }
    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBackTime < 2000) {
            (context as? android.app.Activity)?.finish()
        } else {
            lastBackTime = now
            Toast.makeText(context, "再按一次退出程序", Toast.LENGTH_SHORT).show()
        }
    }

    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var inputText by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var streamingText by remember { mutableStateOf("") }
    var showSystemDialog by remember { mutableStateOf(false) }
    var systemPromptInput by remember { mutableStateOf(inference.systemPrompt) }
    var privacyMode by remember { mutableStateOf(false) }
    var soundEnabled by remember { mutableStateOf(false) }
    var ttsApiKeyInput by remember { mutableStateOf(inference.ttsApiKey) }
    var ttsSpeakerInput by remember { mutableStateOf(inference.ttsSpeaker) }
    var speakerMenuExpanded by remember { mutableStateOf(false) }

    // 常用音色列表（speaker id 见火山音色列表文档）
    val speakerOptions = listOf(
        "zh_female_vv_uranus_bigtts" to "湾湾小何（默认）",
        "zh_female_gaolengyujie_uranus_bigtts" to "高冷御姐",
        "zh_female_shuangkuaisisi_moon_bigtts" to "爽快思思",
        "zh_female_roumeinvyou_emo_v2_mars_bigtts" to "柔美女友（多情感）",
        "zh_female_jiaochuannv_uranus_bigtts" to "娇喘女声 2.0"
    )

    // 图片轮播状态
    var imageUris by remember { mutableStateOf(listOf<Uri>()) }
    var imageIndex by remember { mutableIntStateOf(0) }
    var imageDirName by remember { mutableStateOf("未设置") }
    // 大图查看器：非空时显示浮窗；浮窗内容与轮播共用 imageIndex，左右滑切换
    var viewerUri by remember { mutableStateOf<Uri?>(null) }
    // 浮窗打开时返回键优先关闭浮窗，而不是触发退出提示
    BackHandler(enabled = viewerUri != null) { viewerUri = null }
    // 内部图片清理：多选删除
    var showImageCleaner by remember { mutableStateOf(false) }
    var selectedToDelete by remember { mutableStateOf(setOf<Uri>()) }

    // 图片来源：true=应用内部目录(files/images)，false=外部SAF目录
    val imgPrefs = context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
    var useInternalImages by remember { mutableStateOf(imgPrefs.getBoolean("image_source_internal", false)) }
    // 是否显示图片轮播：默认不显示，需在设定里手动开启
    var showImages by remember { mutableStateOf(imgPrefs.getBoolean("show_images", false)) }
    // 内部图片密码验证：开启“显示图片”时需输入固定密码（仅内部来源时）
    var showImagePasswordDialog by remember { mutableStateOf(false) }
    var imagePasswordInput by remember { mutableStateOf("") }
    var imagePasswordError by remember { mutableStateOf(false) }

    // 列出应用内部目录图片
    fun loadInternalImages(): List<Uri> {
        val dir = java.io.File(context.filesDir, "images")
        val files = dir.listFiles { f -> f.isFile }?.sortedBy { it.name } ?: emptyList()
        return files.map { Uri.fromFile(it) }
    }

    // 启动时若来源是内部目录，加载图片列表但不自动显示（显示需密码重新开启）
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (useInternalImages) {
            imageUris = loadInternalImages()
            imageIndex = 0
            imageDirName = "应用内部目录"
            if (showImages) {
                showImages = false
                imgPrefs.edit().putBoolean("show_images", false).apply()
            }
            Log.d("MyLocalLLM", "ChatScreen: 恢复内部图片目录, 共 ${imageUris.size} 张, 显示需密码")
        }
    }

    // 图片目录选择器（SAF 目录授权，可持久访问）
    // moveToInternal=true 时把目录内图片移入应用内部目录，否则直接读取该目录
    var moveToInternal by remember { mutableStateOf(false) }
    val dirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            if (moveToInternal) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
                moveImagesToInternal(context, it)
                // 显示内部目录全量（原有 + 新移入），而非仅本次复制的文件
                imageUris = loadInternalImages()
                imageIndex = 0
                imageDirName = "应用内部目录"
                // 移入后切到内部来源；显示图片需通过开关输密码开启
                useInternalImages = true
                imgPrefs.edit().putBoolean("image_source_internal", true).apply()
            } else {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                imageUris = listImagesInTree(context, it)
                imageIndex = 0
                imageDirName = it.lastPathSegment ?: it.toString()
                // 选中外部目录即显示图片
                showImages = true
                imgPrefs.edit().putBoolean("show_images", true).apply()
            }
            Log.d("MyLocalLLM", "ChatScreen: 图片目录=$imageDirName, 共 ${imageUris.size} 张, moveToInternal=$moveToInternal")
        }
    }

    // 每 5 秒自动切换下一张；手动滑动后重新计时；大图浮窗打开时暂停自动切换
    LaunchedEffect(imageUris.size, imageIndex, viewerUri) {
        if (viewerUri == null && imageUris.size > 1) {
            delay(5000)
            imageIndex = (imageIndex + 1) % imageUris.size
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
        // 顶部：角色设定入口
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (inference.systemPrompt.isNotBlank()) "角色设定：已开启" else "角色设定：未设置",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = {
                systemPromptInput = inference.systemPrompt
                ttsApiKeyInput = inference.ttsApiKey
                ttsSpeakerInput = inference.ttsSpeaker
                showSystemDialog = true
            }) {
                Text("设定")
            }
            Button(
                onClick = { privacyMode = true },
                modifier = Modifier.padding(start = 4.dp)
            ) {
                Text("隐私")
            }
            Button(
                onClick = {
                    soundEnabled = inference.setSoundEnabled(!soundEnabled)
                    Log.d("MyLocalLLM", "ChatScreen: 声音开关 -> $soundEnabled")
                },
                modifier = Modifier.padding(start = 4.dp)
            ) {
                Text(if (soundEnabled) "声音：开" else "声音")
            }
        }

        // 设定对话框：角色提示词 + 图片目录
        if (showSystemDialog) {
            AlertDialog(
                onDismissRequest = { showSystemDialog = false },
                title = { Text("设定") },
                text = {
                    Column {
                        Text(
                            text = "角色设定（System Prompt）",
                            style = MaterialTheme.typography.labelMedium
                        )
                        TextField(
                            value = systemPromptInput,
                            onValueChange = { systemPromptInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("例如：你是一位温柔的猫娘，用可爱的语气回答……") },
                            minLines = 3,
                            maxLines = 6
                        )
                        Text(
                            text = "语音合成（火山引擎）",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                        TextField(
                            value = ttsApiKeyInput,
                            onValueChange = { ttsApiKeyInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("API Key（新版控制台）") },
                            singleLine = true
                        )
                        // 音色选择
                        Text(
                            text = "音色",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { speakerMenuExpanded = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    speakerOptions.firstOrNull { it.first == ttsSpeakerInput }?.second
                                        ?: if (ttsSpeakerInput.isBlank()) "湾湾小何（默认）" else ttsSpeakerInput
                                )
                            }
                            DropdownMenu(
                                expanded = speakerMenuExpanded,
                                onDismissRequest = { speakerMenuExpanded = false }
                            ) {
                                speakerOptions.forEach { (id, name) ->
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = {
                                            ttsSpeakerInput = id
                                            speakerMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        // 图片来源选择：内部目录 / 外部目录
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "图片来源",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f)
                            )
                            // 显示图片开关：默认不显示；外部目录为空时不可开启
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = showImages,
                                    enabled = useInternalImages || imageUris.isNotEmpty(),
                                    onCheckedChange = { checked ->
                                        if (checked && useInternalImages) {
                                            // 内部目录需要密码验证后才能显示
                                            imagePasswordInput = ""
                                            imagePasswordError = false
                                            showImagePasswordDialog = true
                                        } else {
                                            showImages = checked
                                            imgPrefs.edit().putBoolean("show_images", checked).apply()
                                        }
                                    }
                                )
                                Text("显示图片", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable {
                                    if (useInternalImages) return@clickable
                                    // 切换内部目录无需密码，仅切换来源
                                    useInternalImages = true
                                    imgPrefs.edit().putBoolean("image_source_internal", true).apply()
                                    imageUris = loadInternalImages()
                                    imageIndex = 0
                                    imageDirName = "应用内部目录"
                                }
                            ) {
                                RadioButton(selected = useInternalImages, onClick = null)
                                Text("内部目录", style = MaterialTheme.typography.labelMedium)
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .padding(start = 16.dp)
                                    .clickable {
                                        if (!useInternalImages) return@clickable
                                        useInternalImages = false
                                        imgPrefs.edit().putBoolean("image_source_internal", false).apply()
                                        // 外部目录尚未选择，清空列表避免显示内部图片
                                        imageUris = emptyList()
                                        imageIndex = 0
                                        imageDirName = "未选择"
                                    }
                            ) {
                                RadioButton(selected = !useInternalImages, onClick = null)
                                Text("外部目录", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        if (useInternalImages) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "内部目录（${imageUris.size} 张）",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Button(onClick = {
                                    imageUris = loadInternalImages()
                                    imageIndex = 0
                                }) {
                                    Text("刷新")
                                }
                                Button(
                                    onClick = {
                                        selectedToDelete = emptySet()
                                        showImageCleaner = true
                                    },
                                    modifier = Modifier.padding(start = 4.dp)
                                ) {
                                    Text("清理")
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "图片目录：$imageDirName（${imageUris.size} 张）",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Button(onClick = { dirPicker.launch(null) }) {
                                    Text("选择")
                                }
                            }
                            // 是否移入应用内部目录（仅外部目录模式下显示）
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = moveToInternal,
                                    onCheckedChange = { moveToInternal = it }
                                )
                                Text(
                                    text = "移入应用目录（追加到内部目录并删除原文件）",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        inference.systemPrompt = systemPromptInput.trim()
                        inference.ttsApiKey = ttsApiKeyInput.trim()
                        inference.ttsSpeaker = ttsSpeakerInput.trim()
                        Log.d("MyLocalLLM", "ChatScreen: 设定已保存, system len=${inference.systemPrompt.length}, apiKey=${inference.ttsApiKey.isNotBlank()}, speaker=${inference.ttsSpeaker}")
                        showSystemDialog = false
                    }) {
                        Text("保存")
                    }
                },
                dismissButton = {
                    Button(onClick = { showSystemDialog = false }) {
                        Text("取消")
                    }
                }
            )
        }

        // 内部图片密码验证弹窗
        if (showImagePasswordDialog) {
            AlertDialog(
                onDismissRequest = { showImagePasswordDialog = false },
                title = { Text("输入密码") },
                text = {
                    Column {
                        Text(
                            text = "开启内部图片显示需要密码",
                            style = MaterialTheme.typography.labelMedium
                        )
                        TextField(
                            value = imagePasswordInput,
                            onValueChange = {
                                imagePasswordInput = it
                                imagePasswordError = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("密码") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                if (imagePasswordInput == INTERNAL_IMAGE_PASSWORD) {
                                    showImages = true
                                    imgPrefs.edit().putBoolean("show_images", true).apply()
                                    showImagePasswordDialog = false
                                } else {
                                    imagePasswordError = true
                                }
                            }),
                            isError = imagePasswordError
                        )
                        if (imagePasswordError) {
                            Text(
                                text = "密码错误",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        if (imagePasswordInput == INTERNAL_IMAGE_PASSWORD) {
                            showImages = true
                            imgPrefs.edit().putBoolean("show_images", true).apply()
                            showImagePasswordDialog = false
                        } else {
                            imagePasswordError = true
                        }
                    }) {
                        Text("确定")
                    }
                },
                dismissButton = {
                    Button(onClick = { showImagePasswordDialog = false }) {
                        Text("取消")
                    }
                }
            )
        }

        // 清理内部图片：网格预览 + 多选删除
        if (showImageCleaner) {
            AlertDialog(
                onDismissRequest = { showImageCleaner = false },
                title = { Text("清理内部图片（已选 ${selectedToDelete.size} 张）") },
                text = {
                    val internalFiles = remember(showImageCleaner) {
                        java.io.File(context.filesDir, "images")
                            .listFiles { f -> f.isFile }?.sortedBy { it.name } ?: emptyList()
                    }
                    if (internalFiles.isEmpty()) {
                        Text("内部目录没有图片")
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier.heightIn(max = 400.dp)
                        ) {
                            items(internalFiles.size) { idx ->
                                val file = internalFiles[idx]
                                val uri = Uri.fromFile(file)
                                val selected = uri in selectedToDelete
                                Box(
                                    modifier = Modifier
                                        .padding(2.dp)
                                        .aspectRatio(1f)
                                        .clickable {
                                            selectedToDelete = if (selected) {
                                                selectedToDelete - uri
                                            } else {
                                                selectedToDelete + uri
                                            }
                                        }
                                ) {
                                    AsyncImage(
                                        model = uri,
                                        contentDescription = file.name,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                    // 选中标记
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(
                                                if (selected) Color(0x6600FF00) else Color.Transparent
                                            )
                                    )
                                    if (selected) {
                                        Text(
                                            text = "✓",
                                            color = Color.White,
                                            style = MaterialTheme.typography.titleLarge,
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(4.dp)
                                                .background(Color(0xAA000000), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            var deleted = 0
                            selectedToDelete.forEach { uri ->
                                runCatching {
                                    java.io.File(uri.path!!).delete()
                                    deleted++
                                }.onFailure {
                                    Log.w("MyLocalLLM", "ChatScreen: 删除失败 $uri", it)
                                }
                            }
                            Log.d("MyLocalLLM", "ChatScreen: 清理内部图片完成, 删除 $deleted 张")
                            selectedToDelete = emptySet()
                            imageUris = loadInternalImages()
                            imageIndex = 0
                            showImageCleaner = false
                        },
                        enabled = selectedToDelete.isNotEmpty()
                    ) {
                        Text("删除")
                    }
                },
                dismissButton = {
                    Button(onClick = {
                        selectedToDelete = emptySet()
                        showImageCleaner = false
                    }) {
                        Text("取消")
                    }
                }
            )
        }

        // 上部：图片轮播区（开启显示且有图片才显示，左右滑动切换，每 5 秒自动切换）
        if (showImages && imageUris.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            ) {
                AsyncImage(
                    model = imageUris[imageIndex],
                    contentDescription = "角色图片",
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { viewerUri = imageUris[imageIndex] }
                        .pointerInput(imageUris.size) {
                            // 左右滑动切换上一张/下一张
                            var accumulated = 0f
                            detectHorizontalDragGestures(
                                onDragEnd = { accumulated = 0f },
                                onDragCancel = { accumulated = 0f },
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    accumulated += dragAmount
                                    val threshold = size.width * 0.15f
                                    if (accumulated < -threshold && imageUris.size > 1) {
                                        imageIndex = (imageIndex + 1) % imageUris.size
                                        accumulated = 0f
                                    } else if (accumulated > threshold && imageUris.size > 1) {
                                        imageIndex = (imageIndex - 1 + imageUris.size) % imageUris.size
                                        accumulated = 0f
                                    }
                                }
                            )
                        },
                    contentScale = ContentScale.Fit
                )
                // 指示器：第几张/共几张
                Surface(
                    color = Color.Black.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                ) {
                    Text(
                        text = "${imageIndex + 1}/${imageUris.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }

        // 下部：消息列表 + 输入框
        Column(modifier = Modifier.weight(1f)) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                reverseLayout = true
            ) {
                items(messages.reversed()) { msg ->
                    ChatBubble(msg)
                }
                if (streamingText.isNotEmpty()) {
                    item {
                        ChatBubble(ChatMessage(streamingText, isUser = false))
                    }
                }
                // 已发送但还没出第一个字：显示“思考中”转圈提示
                if (isGenerating && streamingText.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        text = " 思考中...",
                                        color = Color.Black,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 发送/停止逻辑
            val keyboardController = LocalSoftwareKeyboardController.current
            fun handleSend() {
                if (isGenerating) {
                    inference.stopGenerate()
                    return
                }
                if (inputText.isNotBlank()) {
                    val userMsg = ChatMessage(inputText, isUser = true)
                    messages = messages + userMsg
                    val prompt = inputText
                    inputText = ""
                    isGenerating = true
                    streamingText = ""
                    keyboardController?.hide()

                    inference.generate(
                        prompt = prompt,
                        onDelta = { snapshot ->
                            // 流式刷新当前生成的内容
                            streamingText = snapshot
                        },
                        callback = { response ->
                            messages = messages + ChatMessage(response, isUser = false)
                            streamingText = ""
                            isGenerating = false
                        }
                    )
                }
            }

            // 输入框：imePadding 保证键盘弹出时输入框被顶到键盘上方
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入消息...") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { handleSend() })
                )

                Button(onClick = { handleSend() }) {
                    Text(if (isGenerating) "停止" else "发送")
                }
            }
        }
        }

        // 隐私保护遮罩：全黑界面，只显示生成状态，点击恢复
        if (privacyMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { privacyMode = false },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isGenerating) "生成中..." else "已完成",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge
                )
            }
        }

        // 大图查看浮窗：双指捏合缩放、双指拖动平移、单指左右滑动切换图片
        // 浮窗内容与界面轮播共用 imageIndex，切换逻辑一致（首尾循环）
        viewerUri?.let {
            var scale by remember { mutableStateOf(1f) }
            var offsetX by remember { mutableStateOf(0f) }
            var offsetY by remember { mutableStateOf(0f) }
            // 切换图片后重置缩放和平移
            LaunchedEffect(imageIndex) {
                scale = 1f
                offsetX = 0f
                offsetY = 0f
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.9f))
                    .pointerInput(imageUris.size) {
                        awaitEachGesture {
                            awaitFirstDown()
                            var multiTouch = false
                            var accumulated = 0f
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressedCount = event.changes.count { it.pressed }
                                when {
                                    // 双指及以上：缩放 + 平移
                                    pressedCount >= 2 -> {
                                        multiTouch = true
                                        val zoom = event.calculateZoom()
                                        val pan = event.calculatePan()
                                        scale = (scale * zoom).coerceIn(1f, 5f)
                                        offsetX += pan.x
                                        offsetY += pan.y
                                    }
                                    // 单指：左右滑动切换上一张/下一张（一旦出现双指则本次手势不再切换）
                                    pressedCount == 1 && !multiTouch -> {
                                        val drag = event.changes.firstOrNull { it.pressed }
                                        if (drag != null && imageUris.size > 1) {
                                            accumulated += (drag.position.x - drag.previousPosition.x)
                                            val threshold = size.width * 0.2f
                                            if (accumulated < -threshold) {
                                                imageIndex = (imageIndex + 1) % imageUris.size
                                                accumulated = 0f
                                            } else if (accumulated > threshold) {
                                                imageIndex = (imageIndex - 1 + imageUris.size) % imageUris.size
                                                accumulated = 0f
                                            }
                                        }
                                    }
                                }
                                event.changes.forEach { it.consume() }
                                if (event.changes.none { it.pressed }) break
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = imageUris[imageIndex],
                    contentDescription = "大图查看",
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        ),
                    contentScale = ContentScale.Fit
                )
                // 指示器：第几张/共几张
                Surface(
                    color = Color.Black.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                ) {
                    Text(
                        text = "${imageIndex + 1}/${imageUris.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                // 关闭按钮
                Button(
                    onClick = { viewerUri = null },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(12.dp)
                ) {
                    Text("关闭")
                }
            }
        }
    }
}

// 列出 SAF 目录树下的所有图片文件（仅当前层级），按文件名排序保证轮播顺序稳定
private fun listImagesInTree(context: Context, treeUri: Uri): List<Uri> {    val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
    val results = mutableListOf<Pair<String, Uri>>()

    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE
    )
    context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
        val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
        while (cursor.moveToNext()) {
            val mime = cursor.getString(mimeCol) ?: continue
            if (!mime.startsWith("image/")) continue
            val docId = cursor.getString(idCol) ?: continue
            val name = cursor.getString(nameCol) ?: docId
            results.add(name to DocumentsContract.buildDocumentUriUsingTree(treeUri, docId))
        }
    }
    return results.sortedBy { it.first }.map { it.second }
}

// 把 SAF 目录树里的图片复制进应用内部目录（files/images），追加到已有图片，同名文件直接覆盖。
// 复制成功后删除原文件。返回内部目录文件的 Uri 列表（file://  scheme，应用内可直接用）。
private fun moveImagesToInternal(context: Context, treeUri: Uri): List<Uri> {
    val internalDir = java.io.File(context.filesDir, "images")
    internalDir.mkdirs()

    val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE
    )
    val results = mutableListOf<Pair<String, Uri>>()
    context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
        val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
        while (cursor.moveToNext()) {
            val mime = cursor.getString(mimeCol) ?: continue
            if (!mime.startsWith("image/")) continue
            val docId = cursor.getString(idCol) ?: continue
            val name = cursor.getString(nameCol) ?: docId
            results.add(name to DocumentsContract.buildDocumentUriUsingTree(treeUri, docId))
        }
    }

    val copied = mutableListOf<java.io.File>()
    var overwritten = 0
    results.sortedBy { it.first }.forEach { (name, docUri) ->
        runCatching {
            val dest = java.io.File(internalDir, name)
            // 同名文件直接覆盖
            if (dest.exists()) overwritten++
            context.contentResolver.openInputStream(docUri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
                copied.add(dest)
            }
        }.onFailure {
            Log.w("MyLocalLLM", "ChatScreen: 复制失败 $name", it)
        }
    }

    // 复制成功一个删一个原文件
    var deleted = 0
    results.sortedBy { it.first }.forEach { (name, docUri) ->
        if (copied.any { it.name == name }) {
            runCatching {
                DocumentsContract.deleteDocument(context.contentResolver, docUri)
                deleted++
            }.onFailure {
                Log.w("MyLocalLLM", "ChatScreen: 删除原文件失败 $name", it)
            }
        }
    }
    val failed = results.size - copied.size
    Log.d("MyLocalLLM", "ChatScreen: 追加到内部目录完成, 目录中共找到=${results.size}, 复制=${copied.size}, 其中覆盖同名=$overwritten, 失败=$failed, 删除原文件=$deleted")
    Toast.makeText(
        context,
        "找到 ${results.size} 张，复制 ${copied.size} 张（覆盖同名 $overwritten 张），失败 $failed 张",
        Toast.LENGTH_LONG
    ).show()

    return copied.sortedBy { it.name }.map { Uri.fromFile(it) }
}

data class ChatMessage(val text: String, val isUser: Boolean)

@Composable
fun ChatBubble(message: ChatMessage) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = if (message.isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Surface(
            color = if (message.isUser) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = message.text,
                modifier = Modifier.padding(12.dp),
                color = if (message.isUser) Color.White else Color.Black
            )
        }
    }
}
