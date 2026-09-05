package com.scrcpyandroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.scrcpyandroid.R
import com.scrcpyandroid.protocol.MirrorOptions
import com.scrcpyandroid.session.SessionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    initialOptions: MirrorOptions,
    sessionState: SessionState,
    onStart: (MirrorOptions) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
) {
    var host by remember(initialOptions.host) { mutableStateOf(initialOptions.host) }
    var port by remember(initialOptions.port) { mutableStateOf(initialOptions.port.toString()) }
    val busy = sessionState is SessionState.Connecting || sessionState is SessionState.Reconnecting
    val maxSizeLabel = if (initialOptions.maxSize == 0) {
        stringResource(R.string.label_native_size)
    } else {
        initialOptions.maxSize.toString()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_connect)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.cd_settings))
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(R.string.hint_lan),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = host,
                onValueChange = { host = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.label_ip)) },
                placeholder = { Text("192.168.1.100") },
                singleLine = true,
                enabled = !busy,
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter { ch -> ch.isDigit() }.take(5) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.label_port)) },
                placeholder = { Text("5555") },
                singleLine = true,
                enabled = !busy,
            )

            Text(
                stringResource(
                    R.string.params_summary,
                    maxSizeLabel,
                    initialOptions.videoBitRate / 1_000_000,
                    initialOptions.maxFps,
                    initialOptions.videoCodec.label,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )

            Button(
                onClick = {
                    val p = port.toIntOrNull() ?: 5555
                    onStart(initialOptions.copy(host = host, port = p))
                },
                enabled = host.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (busy) {
                        stringResource(R.string.action_connecting)
                    } else {
                        stringResource(R.string.action_start)
                    },
                )
            }

            OutlinedButton(
                onClick = onOpenBatterySettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_battery))
            }

            when (sessionState) {
                is SessionState.Error -> {
                    Text(sessionState.message, color = MaterialTheme.colorScheme.error)
                }
                is SessionState.Reconnecting -> {
                    Text(sessionState.message)
                }
                else -> Unit
            }

            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.keepalive_tip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
        }
    }
}
