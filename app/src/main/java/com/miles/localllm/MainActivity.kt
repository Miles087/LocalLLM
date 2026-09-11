package com.miles.localllm

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.miles.localllm.operator.LlamaInference
import com.miles.localllm.operator.ModelManager
import com.miles.localllm.ui.theme.LocalLLMTheme

class MainActivity : ComponentActivity() {
    private lateinit var inference: LlamaInference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 生成过程耗时较长，保持屏幕常亮避免锁屏中断
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        inference = LlamaInference(this)

        setContent {
            LocalLLMTheme {
                var currentScreen by remember { mutableStateOf("loader") }
                var loadedModelPath by remember { mutableStateOf("") }

                when (currentScreen) {
                    "loader" -> ModelLoaderScreen(
                        onModelLoaded = { path ->
                            loadedModelPath = path
                            currentScreen = "loading"

                            // 在后台线程加载模型
                            inference.initModel { success ->
                                if (success) {
                                    currentScreen = "chat"
                                }
                            }
                        }
                    )
                    "loading" -> LoadingScreen(loadedModelPath)
                    "chat" -> ChatScreen(inference)
                }
            }
        }
    }
}

@Composable
fun LoadingScreen(path: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("正在加载模型...")
            Text(
                text = path.substringAfterLast('/'),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}