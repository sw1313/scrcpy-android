package com.scrcpyandroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.scrcpyandroid.R
import com.scrcpyandroid.protocol.AudioCodecOption
import com.scrcpyandroid.protocol.MirrorOptions
import com.scrcpyandroid.protocol.VideoCodecOption

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    initialOptions: MirrorOptions,
    onSave: (MirrorOptions) -> Unit,
    onBack: () -> Unit,
) {
    var maxSize by remember { mutableIntStateOf(initialOptions.maxSize) }
    var bitRate by remember { mutableIntStateOf(initialOptions.videoBitRate) }
    var fps by remember { mutableIntStateOf(initialOptions.maxFps) }
    var codec by remember { mutableStateOf(initialOptions.videoCodec) }
    var audioEnabled by remember { mutableStateOf(initialOptions.audioEnabled) }
    var audioCodec by remember { mutableStateOf(initialOptions.audioCodec) }
    var stayAwake by remember { mutableStateOf(initialOptions.stayAwake) }
    var turnOff by remember { mutableStateOf(initialOptions.turnScreenOff) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SectionTitle(stringResource(R.string.section_max_size))
            ChipRow {
                MirrorOptions.maxSizeChoices.forEach { size ->
                    val label = if (size == 0) {
                        stringResource(R.string.label_native_size)
                    } else {
                        size.toString()
                    }
                    FilterChip(
                        selected = maxSize == size,
                        onClick = { maxSize = size },
                        label = { Text(label) },
                    )
                }
            }

            SectionTitle(stringResource(R.string.section_bitrate))
            ChipRow {
                MirrorOptions.bitRateChoices.forEach { (value, label) ->
                    FilterChip(
                        selected = bitRate == value,
                        onClick = { bitRate = value },
                        label = { Text(label) },
                    )
                }
            }

            SectionTitle(stringResource(R.string.section_fps))
            ChipRow {
                MirrorOptions.fpsChoices.forEach { value ->
                    FilterChip(
                        selected = fps == value,
                        onClick = { fps = value },
                        label = { Text("${value}fps") },
                    )
                }
            }

            SectionTitle(stringResource(R.string.section_video_codec))
            VideoCodecOption.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = codec == option,
                            onClick = { codec = option },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = codec == option, onClick = { codec = option })
                    Text(option.label, modifier = Modifier.padding(start = 8.dp))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.label_remote_audio))
                Switch(checked = audioEnabled, onCheckedChange = { audioEnabled = it })
            }
            if (audioEnabled) {
                SectionTitle(stringResource(R.string.section_audio_codec))
                AudioCodecOption.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = audioCodec == option,
                                onClick = { audioCodec = option },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = audioCodec == option, onClick = { audioCodec = option })
                        Text(option.label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.label_stay_awake))
                Switch(checked = stayAwake, onCheckedChange = { stayAwake = it })
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.label_power_off))
                Switch(checked = turnOff, onCheckedChange = { turnOff = it })
            }

            Text(
                stringResource(R.string.params_apply_tip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )

            Button(
                onClick = {
                    onSave(
                        initialOptions.copy(
                            maxSize = maxSize,
                            videoBitRate = bitRate,
                            maxFps = fps,
                            videoCodec = codec,
                            audioEnabled = audioEnabled,
                            audioCodec = audioCodec,
                            stayAwake = stayAwake,
                            turnScreenOff = turnOff,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        content()
    }
}
