package com.liftlog.app

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.LocalDate

private const val MaxBackupBytes = 16 * 1024 * 1024

@Composable
internal fun BackupMenu(repo: WorkoutRepository, afterRestore: () -> Unit) {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var feedback by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingImport by rememberSaveable { mutableStateOf<String?>(null) }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val data = repo.exportBackup().toByteArray(Charsets.UTF_8)
                check(data.size <= MaxBackupBytes)
                withContext(Dispatchers.IO) {
                    checkNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(data) }
                }
                feedback = "已导出计划和训练记录。请妥善保管备份文件。"
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                feedback = "导出失败，未修改训练记录。请选一个可写入的位置重试。"
            } finally {
                busy = false
            }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingImport = uri?.toString()
    }

    Box {
        TextButton(onClick = { expanded = true }, enabled = !busy) {
            Text(if (busy) "处理中" else "备份")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("导出本地备份") },
                onClick = {
                    expanded = false
                    export.launch("练迹-备份-${LocalDate.now()}.json")
                }
            )
            DropdownMenuItem(
                text = { Text("从备份恢复") },
                onClick = {
                    expanded = false
                    import.launch(arrayOf("application/json", "text/json", "text/plain", "application/octet-stream"))
                }
            )
        }
    }

    val source = pendingImport
    if (source != null) {
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("用备份替换本机数据？") },
            text = { Text("恢复会替换当前的周计划、单日计划和训练记录，不会合并。建议先导出一份当前数据。") },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    scope.launch {
                        busy = true
                        try {
                            val json = withContext(Dispatchers.IO) { readBackup(resolver, Uri.parse(source)) }
                            repo.importBackup(json)
                            afterRestore()
                            feedback = "备份已恢复。日历和统计已按恢复后的记录更新。"
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            feedback = "无法恢复。文件不可读、过大或不是兼容的练迹备份。"
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("替换并恢复") }
            }
        )
    }
    feedback?.let { text ->
        AlertDialog(onDismissRequest = { feedback = null }, text = { Text(text) },
            confirmButton = { TextButton(onClick = { feedback = null }) { Text("好") } })
    }
}

private fun readBackup(resolver: ContentResolver, uri: Uri): String {
    return checkNotNull(resolver.openInputStream(uri)).use { input ->
        ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MaxBackupBytes)
                output.write(buffer, 0, count)
            }
            output.toString("UTF-8")
        }
    }
}
