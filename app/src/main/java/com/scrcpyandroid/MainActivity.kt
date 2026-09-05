package com.scrcpyandroid

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.scrcpyandroid.protocol.MirrorOptions
import com.scrcpyandroid.session.MirrorSessionService
import com.scrcpyandroid.session.SessionState
import com.scrcpyandroid.ui.ScrcpyAppNav
import com.scrcpyandroid.ui.theme.ScrcpyTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var service: MirrorSessionService? by mutableStateOf(null)
    private val serviceState = MutableStateFlow<SessionState>(SessionState.Idle)
    private var prefsStore: SettingsRepository? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as MirrorSessionService.LocalBinder).getService()
            service = svc
            lifecycleScope.launch {
                svc.state.collect { serviceState.value = it }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            serviceState.value = SessionState.Idle
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefsStore = SettingsRepository(applicationContext)
        maybeRequestNotificationPermission()

        setContent {
            ScrcpyTheme {
                val sessionState by serviceState.collectAsStateWithLifecycle()
                val saved by prefsStore!!.optionsFlow.collectAsStateWithLifecycle(
                    initialValue = MirrorOptions(host = ""),
                )
                ScrcpyAppNav(
                    sessionState = sessionState,
                    initialOptions = saved,
                    service = service,
                    onSaveOptions = { opts ->
                        lifecycleScope.launch { prefsStore?.save(opts) }
                    },
                    onStart = { opts ->
                        lifecycleScope.launch { prefsStore?.save(opts) }
                        ensureBatteryOptimizationExempt()
                        MirrorSessionService.start(this, opts)
                        bindMirrorService(force = true)
                    },
                    onStop = {
                        MirrorSessionService.stop(this)
                    },
                    onOpenBatterySettings = { openBatterySettings() },
                )
            }
        }
    }

    private var bound = false

    private fun bindMirrorService(force: Boolean = false) {
        if (bound && !force) return
        val intent = Intent(this, MirrorSessionService::class.java)
        bound = bindService(intent, connection, BIND_AUTO_CREATE)
    }

    override fun onStart() {
        super.onStart()
        // Re-bind if a session service is already running (e.g. return from background).
        bindMirrorService()
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun ensureBatteryOptimizationExempt() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            // Soft prompt via settings screen in UI; auto-request once.
            runCatching {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }

    private fun openBatterySettings() {
        runCatching {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            startActivity(intent)
        }
    }

    override fun onDestroy() {
        if (bound) {
            runCatching { unbindService(connection) }
            bound = false
        }
        super.onDestroy()
    }
}
