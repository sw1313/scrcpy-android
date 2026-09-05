package com.scrcpyandroid.adb

import android.content.Context
import dadb.AdbKeyPair
import dadb.AdbStream
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

class AdbDeviceClient(
    private val context: Context,
) {
    private var dadb: Dadb? = null
    private var shellStream: AdbStream? = null

    val isConnected: Boolean get() = dadb != null

    suspend fun connect(host: String, port: Int) = withContext(Dispatchers.IO) {
        close()
        val keyPair = loadOrCreateKeyPair()
        val client = Dadb.create(host, port, keyPair, connectTimeout = 10_000, socketTimeout = 0)
        client.shell("echo ok")
        dadb = client
    }

    suspend fun pushServer() = withContext(Dispatchers.IO) {
        val client = requireDadb()
        val local = extractServerAsset()
        local.source().buffer().use { source ->
            client.push(source, REMOTE_SERVER_PATH, mode = 420, lastModifiedMs = local.lastModified())
        }
    }

    /**
     * Starts scrcpy-server and opens sockets in order: video, [audio], control.
     */
    suspend fun startScrcpySession(
        version: String,
        scid: Int,
        serverArgs: List<String>,
        audioEnabled: Boolean,
    ): ScrcpyStreams = withContext(Dispatchers.IO) {
        val client = requireDadb()
        val socketName = "scrcpy_${scid.toString(16).padStart(8, '0')}"
        val cmd = buildString {
            append("CLASSPATH=$REMOTE_SERVER_PATH app_process / ")
            append("com.genymobile.scrcpy.Server $version ")
            append(serverArgs.joinToString(" "))
        }

        shellStream?.close()
        shellStream = client.open("shell:$cmd")

        val video = openSocketWithRetry(client, socketName)
        val dummy = video.source.readByte()
        check(dummy == 0.toByte()) { "Unexpected dummy byte: $dummy" }

        val audio = if (audioEnabled) {
            openSocketWithRetry(client, socketName)
        } else {
            null
        }
        val control = openSocketWithRetry(client, socketName)
        ScrcpyStreams(video, audio, control, shellStream!!)
    }

    private suspend fun openSocketWithRetry(client: Dadb, socketName: String): AdbStream {
        var lastError: Exception? = null
        repeat(50) { attempt ->
            try {
                return client.open("localabstract:$socketName")
            } catch (e: Exception) {
                lastError = e
                delay(120L + attempt * 20L)
            }
        }
        throw IllegalStateException("Failed to open localabstract:$socketName", lastError)
    }

    private fun loadOrCreateKeyPair(): AdbKeyPair {
        val dir = File(context.filesDir, "adb")
        val privateKey = File(dir, "adbkey")
        val publicKey = File(dir, "adbkey.pub")
        if (!privateKey.exists() || !publicKey.exists()) {
            AdbKeyPair.generate(privateKey, publicKey)
        }
        return AdbKeyPair.read(privateKey, publicKey)
    }

    private fun extractServerAsset(): File {
        val out = File(context.filesDir, "scrcpy-server")
        context.assets.open("scrcpy-server").use { input ->
            FileOutputStream(out).use { output -> input.copyTo(output) }
        }
        return out
    }

    private fun requireDadb(): Dadb = dadb ?: error("ADB not connected")

    fun close() {
        runCatching { shellStream?.close() }
        shellStream = null
        runCatching { dadb?.close() }
        dadb = null
    }

    companion object {
        private const val REMOTE_SERVER_PATH = "/data/local/tmp/scrcpy-server.jar"
    }
}

class ScrcpyStreams(
    val video: AdbStream,
    val audio: AdbStream?,
    val control: AdbStream,
    val shell: AdbStream,
) {
    private val closed = AtomicBoolean(false)

    val videoInput: InputStream get() = video.source.inputStream()
    val audioInput: InputStream? get() = audio?.source?.inputStream()
    val controlOutput: OutputStream get() = control.sink.outputStream()

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { video.close() }
        runCatching { audio?.close() }
        runCatching { control.close() }
        runCatching { shell.close() }
    }
}
