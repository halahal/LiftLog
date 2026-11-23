package com.liftlog.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun RestSoundDialog(controller: RestSoundController, onDismiss: () -> Unit) {
    val saved by controller.settings.collectAsState()
    var toneKey by rememberSaveable { mutableStateOf(saved.tone.name) }
    var volume by rememberSaveable { mutableIntStateOf(saved.volume) }
    var previewFailed by rememberSaveable { mutableStateOf(false) }
    val tone = RestTone.entries.firstOrNull { it.name == toneKey } ?: saved.tone
    val draft = RestSoundSettings(tone, volume)

    DisposableEffect(controller) {
        onDispose { controller.stopPreview() }
    }
    AlertDialog(
        title = { Text("休息提示音") },
        onDismissRequest = onDismiss,
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.selectableGroup()) {
                    RestTone.entries.forEach { option ->
                        val selected = tone == option
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(selected, role = Role.RadioButton, onClick = {
                                    controller.stopPreview()
                                    toneKey = option.name
                                    previewFailed = false
                                }),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("提示音音量", style = MaterialTheme.typography.labelLarge)
                    Text("$volume%", style = MaterialTheme.typography.labelLarge)
                }
                Slider(
                    value = volume.toFloat(),
                    onValueChange = { value ->
                        if (previewFailed) previewFailed = false
                        if (volume != value.roundToInt()) controller.stopPreview()
                        volume = value.roundToInt()
                    },
                    valueRange = 0f..100f,
                    modifier = Modifier.semantics { contentDescription = "提示音音量" },
                )
                OutlinedButton(
                    onClick = { previewFailed = !controller.preview(draft) },
                    enabled = volume > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("试听") }
                if (previewFailed) {
                    Text("无法播放试听，请再试一次。", color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "0% 为静音。实际响度也受手机的闹钟音量影响。选择保存后，之后每次休息都会使用这项设置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            Button(onClick = {
                controller.save(draft)
                onDismiss()
            }) { Text("保存") }
        },
    )
}
