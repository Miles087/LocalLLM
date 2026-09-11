package com.miles.localllm.operator

import android.content.Context
import android.net.Uri
import java.io.File

object ModelManager {
    private const val MODEL_DIR = "models"

    fun getModelDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), MODEL_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getModelPath(context: Context, modelName: String? = null): String {
        val dir = getModelDir(context)
        return if (modelName != null) {
            File(dir, modelName).absolutePath
        } else {
            // 默认返回目录下第一个 .gguf 文件
            dir.listFiles { file ->
                file.extension == "gguf" || file.extension == "bin"
            }?.firstOrNull()?.absolutePath ?: ""
        }
    }

    fun importModel(context: Context, uri: Uri, fileName: String): Result<String> {
        return try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return Result.failure(Exception("无法打开文件"))

            val targetFile = File(getModelDir(context), fileName)
            if (targetFile.exists()) targetFile.delete()

            input.use { inputStream ->
                targetFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            Result.success(targetFile.absolutePath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getLocalModels(context: Context): List<File> {
        return getModelDir(context).listFiles { file ->
            file.extension == "gguf" || file.extension == "bin"
        }?.toList() ?: emptyList()
    }

    fun deleteModel(context: Context, fileName: String): Boolean {
        return File(getModelDir(context), fileName).delete()
    }
}