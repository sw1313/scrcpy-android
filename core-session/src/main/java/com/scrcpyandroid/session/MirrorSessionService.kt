package com.scrcpyandroid.session

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.scrcpyandroid.adb.AdbDeviceClient
import com.scrcpyandroid.adb.ScrcpyStreams
import com.scrcpyandroid.protocol.ControlMessenger
import com.scrcpyandroid.protocol.MirrorOptions
import com.scrcpyandroid.protocol.ScrcpyConstants
import com.scrcpyandroid.video.AudioPlayerPipeline
import com.scrcpyandroid.video.VideoDecoderPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Owns the entire mirroring session independently of any Activity lifecycle.
 * Activities only attach/detach a Surface for display.
 */
class MirrorSessionService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): MirrorSessionService = this@MirrorSessionService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var adb: AdbDeviceClient
    private lateinit var decoder: VideoDecoderPipeline
    private lateinit var audioPlayer: AudioPlayerPipeline

    private var streams: ScrcpyStreams? = null
    private var control: ControlMessenger? = null
    private var currentOptions: MirrorOptions? = null
    private var sessionJob: Job? = null
    private var reconnectJob: Job? = null
    private val userStopped = AtomicBoolean(false)
    /** Single thread keeps touch/key message order and avoids NetworkOnMainThreadException. */
    private val controlExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "scrcpy-control").apply { isDaemon = true }
    }

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Always-updated video size (survives Connecting→Streaming race). */
    private val videoSize = java.util.concurrent.atomic.AtomicReference(0 to 0)

    val videoWidth: Int get() = videoSize.get().first
    val videoHeight: Int get() = videoSize.get().second

    override fun onCreate() {
        super.onCreate()
        adb = AdbDeviceClient(applicationContext)
        decoder = VideoDecoderPipeline(scope)
        audioPlayer = AudioPlayerPipeline(scope)
        decoder.onSizeChanged = { w, h ->
            videoSize.set(w to h)
            _state.update { current ->
                when (current) {
                    is SessionState.Streaming -> current.copy(videoWidth = w, videoHeight = h)
                    else -> current
                }
            }
        }
        decoder.onError = { t ->
            Log.e(TAG, "Decoder error", t)
            scope.launch { handleDisconnect(t) }
        }
        decoder.onEos = {
            scope.launch { handleDisconnect(null) }
        }
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSession(userInitiated = true)
            ACTION_START -> {
                val options = intent.getParcelableExtraCompat(EXTRA_OPTIONS, MirrorOptionsParcel::class.java)
                    ?.toOptions()
                if (options != null) {
                    startSession(options)
                }
            }
        }
        return START_STICKY
    }

    fun startSession(options: MirrorOptions) {
        userStopped.set(false)
        currentOptions = options
        reconnectJob?.cancel()
        sessionJob?.cancel()
        sessionJob = scope.launch(Dispatchers.IO) {
            connectInternal(options, isReconnect = false)
        }
    }

    fun stopSession(userInitiated: Boolean) {
        if (userInitiated) userStopped.set(true)
        reconnectJob?.cancel()
        sessionJob?.cancel()
        tearDownConnection()
        _state.value = SessionState.Idle
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun setSurface(surface: Surface?) {
        decoder.setSurface(surface)
    }

    fun injectTouch(
        action: Int,
        pointerId: Long,
        x: Int,
        y: Int,
        pressure: Float,
    ) {
        if (_state.value !is SessionState.Streaming) return
        val (width, height) = videoSize.get()
        if (width <= 0 || height <= 0) return
        val messenger = control ?: return
        // Scrcpy server only treats ACTION_UP as pointer release. Map CANCEL → UP.
        val normalizedAction =
            if (action == ScrcpyConstants.ACTION_CANCEL) ScrcpyConstants.ACTION_UP else action
        controlExecutor.execute {
            runCatching {
                messenger.injectTouch(
                    action = normalizedAction,
                    pointerId = pointerId,
                    x = x.coerceIn(0, width - 1),
                    y = y.coerceIn(0, height - 1),
                    screenWidth = width,
                    screenHeight = height,
                    pressure = pressure,
                )
            }.onFailure { Log.w(TAG, "injectTouch failed", it) }
        }
    }

    fun pressHome() = enqueueControl { it.home() }
    fun pressBack() = enqueueControl { it.back() }
    fun pressRecents() = enqueueControl { it.recents() }
    fun pressVolumeUp() = enqueueControl { it.volumeUp() }
    fun pressVolumeDown() = enqueueControl { it.volumeDown() }
    fun pressVolumeMute() = enqueueControl { it.volumeMute() }

    private fun enqueueControl(block: (ControlMessenger) -> Unit) {
        val messenger = control ?: return
        controlExecutor.execute {
            runCatching { block(messenger) }
                .onFailure { Log.w(TAG, "control command failed", it) }
        }
    }

    private suspend fun connectInternal(options: MirrorOptions, isReconnect: Boolean) {
        try {
            _state.value = if (isReconnect) {
                SessionState.Reconnecting(0, "重新连接中…")
            } else {
                SessionState.Connecting
            }
            startAsForeground(options.host)
            acquireLocks()

            adb.connect(options.host, options.port)
            adb.pushServer()

            val scid = Random.nextInt(0, 0x7fffffff)
            val args = options.toServerArgs(scid)
            Log.i(TAG, "starting session audio=${options.audioEnabled} codec=${options.audioCodec.serverValue}")
            val opened = adb.startScrcpySession(
                ScrcpyConstants.SERVER_VERSION,
                scid,
                args,
                audioEnabled = options.audioEnabled,
            )
            streams = opened

            // Device name (64 bytes) on first socket after dummy byte
            val nameBytes = ByteArray(ScrcpyConstants.DEVICE_NAME_FIELD_LENGTH)
            var offset = 0
            while (offset < nameBytes.size) {
                val n = opened.videoInput.read(nameBytes, offset, nameBytes.size - offset)
                if (n < 0) error("EOF reading device name")
                offset += n
            }
            val deviceName = nameBytes.toString(Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }

            control = ControlMessenger(opened.controlOutput)
            videoSize.set(0 to 0)
            requestAudioFocus()
            decoder.start(opened.videoInput)
            opened.audioInput?.let {
                Log.i(TAG, "starting audio player codec=${options.audioCodec.serverValue}")
                audioPlayer.start(it)
            }

            // Prefer size already reported by decoder callback if it raced ahead.
            val (w, h) = videoSize.get()
            _state.value = SessionState.Streaming(
                deviceName = deviceName.ifBlank { options.host },
                videoWidth = w,
                videoHeight = h,
                options = options,
            )
            updateNotification("正在镜像 $deviceName")
        } catch (t: Throwable) {
            Log.e(TAG, "connect failed", t)
            tearDownConnection()
            if (!userStopped.get()) {
                _state.value = SessionState.Error(t.message ?: "连接失败", t)
                if (!isReconnect) {
                    scheduleReconnect(options, t.message)
                } else {
                    throw t
                }
            } else {
                _state.value = SessionState.Idle
            }
        }
    }

    private fun handleDisconnect(cause: Throwable?) {
        if (userStopped.get()) return
        if (_state.value is SessionState.Reconnecting) return
        val options = currentOptions ?: return
        tearDownConnection()
        scheduleReconnect(options, cause?.message)
    }

    private fun scheduleReconnect(options: MirrorOptions, reason: String? = null) {
        if (userStopped.get()) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            repeat(MAX_RECONNECT_ATTEMPTS) { attempt ->
                if (userStopped.get() || !isActive) return@launch
                val delayMs = (1000L * (1 shl attempt.coerceAtMost(4))).coerceAtMost(15_000L)
                val message = reason?.let {
                    "断线：$it，${attempt + 1}/$MAX_RECONNECT_ATTEMPTS 重连中…"
                } ?: "网络抖动，${attempt + 1}/$MAX_RECONNECT_ATTEMPTS 重连中…"
                _state.value = SessionState.Reconnecting(attempt + 1, message)
                updateNotification(message)
                delay(delayMs)
                if (userStopped.get()) return@launch
                val job = launch(Dispatchers.IO) {
                    connectInternal(options, isReconnect = true)
                }
                sessionJob = job
                job.join()
                if (_state.value is SessionState.Streaming) return@launch
            }
            if (_state.value !is SessionState.Streaming && !userStopped.get()) {
                _state.value = SessionState.Error("重连失败，请检查无线 ADB 后重试")
                updateNotification("镜像已断开")
            }
        }
    }

    private fun tearDownConnection() {
        runCatching { decoder.stop() }
        runCatching { audioPlayer.stop() }
        abandonAudioFocus()
        runCatching { streams?.close() }
        streams = null
        control = null
        runCatching { adb.close() }
        releaseLocks()
    }

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { }
                .build()
            audioFocusRequest = req
            val r = am.requestAudioFocus(req)
            Log.i(TAG, "audio focus request result=$r")
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }

    private fun startAsForeground(host: String) {
        val notification = buildNotification("正在连接 $host")
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, MirrorSessionService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPending = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("无线 ADB 投屏")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(contentPending)
            .addAction(0, "断开", stopPending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "投屏会话",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "保持无线投屏在后台不断联"
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wifiLock == null) {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "scrcpy:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wakeLock == null) {
            val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "scrcpy:cpu").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wifiLock = null
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        userStopped.set(true)
        reconnectJob?.cancel()
        sessionJob?.cancel()
        tearDownConnection()
        controlExecutor.shutdownNow()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MirrorSession"
        const val CHANNEL_ID = "mirror_session"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.scrcpyandroid.action.START"
        const val ACTION_STOP = "com.scrcpyandroid.action.STOP"
        const val EXTRA_OPTIONS = "options"
        private const val MAX_RECONNECT_ATTEMPTS = 8

        fun start(context: Context, options: MirrorOptions) {
            val intent = Intent(context, MirrorSessionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_OPTIONS, MirrorOptionsParcel.from(options))
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MirrorSessionService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}

private fun <T : java.io.Serializable> Intent.getParcelableExtraCompat(key: String, clazz: Class<T>): T? {
    return if (Build.VERSION.SDK_INT >= 33) {
        getSerializableExtra(key, clazz)
    } else {
        @Suppress("DEPRECATION")
        getSerializableExtra(key) as? T
    }
}
