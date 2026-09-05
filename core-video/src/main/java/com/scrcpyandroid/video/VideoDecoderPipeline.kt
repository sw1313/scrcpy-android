package com.scrcpyandroid.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.scrcpyandroid.protocol.ScrcpyConstants
import com.scrcpyandroid.protocol.VideoCodecMeta
import com.scrcpyandroid.protocol.VideoDemuxer
import com.scrcpyandroid.protocol.VideoPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Single-threaded MediaCodec access (codec is not thread-safe).
 * Waits for a Surface before feeding the first keyframe so SPS/PPS are never dropped.
 */
class VideoDecoderPipeline(
    private val scope: CoroutineScope,
) {
    private val surfaceRef = AtomicReference<Surface?>(null)
    private val hasSurface = AtomicBoolean(false)
    private val mutex = Mutex()
    private var codec: MediaCodec? = null
    private var demuxJob: Job? = null
    private var currentMeta: VideoCodecMeta? = null
    private var codecConfig: ByteArray? = null
    private var configured = false
    private var needKeyframe = true

    var onSizeChanged: ((width: Int, height: Int) -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null
    var onEos: (() -> Unit)? = null

    fun setSurface(surface: Surface?) {
        surfaceRef.set(surface)
        hasSurface.set(surface != null)
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                val c = codec ?: return@withLock
                val meta = currentMeta ?: return@withLock
                try {
                    if (surface != null) {
                        if (!configured) {
                            configureCodecLocked(c, meta, surface)
                            needKeyframe = true
                        } else if (Build.VERSION.SDK_INT >= 23) {
                            runCatching { c.setOutputSurface(surface) }
                                .onFailure {
                                    Log.w(TAG, "setOutputSurface failed, reconfigure", it)
                                    runCatching { c.stop() }
                                    configured = false
                                    configureCodecLocked(c, meta, surface)
                                    needKeyframe = true
                                }
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "setSurface failed", t)
                    onError?.invoke(t)
                }
            }
        }
    }

    fun start(input: InputStream) {
        stopInternal()
        demuxJob = scope.launch(Dispatchers.IO) {
            try {
                val demuxer = VideoDemuxer(input)
                val meta = demuxer.readCodecMeta()
                currentMeta = meta
                onSizeChanged?.invoke(meta.width, meta.height)

                mutex.withLock {
                    releaseCodecLocked()
                    needKeyframe = true
                    val c = MediaCodec.createDecoderByType(meta.mimeType)
                    codec = c
                    val surface = surfaceRef.get()
                    if (surface != null) {
                        configureCodecLocked(c, meta, surface)
                    }
                }

                while (isActive) {
                    // Block briefly until a Surface exists so we never drop the first IDR.
                    while (isActive && !hasSurface.get()) {
                        delay(10)
                    }
                    if (!isActive) break

                    mutex.withLock {
                        val c = codec
                        val surface = surfaceRef.get()
                        val m = currentMeta
                        if (c != null && surface != null && m != null && !configured) {
                            configureCodecLocked(c, m, surface)
                            needKeyframe = true
                        }
                    }

                    val packet = demuxer.readMergedPacket() ?: break
                    demuxer.lastConfig?.let { codecConfig = it }

                    if (needKeyframe && !packet.isKeyFrame) {
                        continue
                    }

                    val fed = feedPacket(packet)
                    if (!fed) {
                        // Decoder behind — skip to next keyframe to catch up (low latency).
                        needKeyframe = true
                        Log.d(TAG, "decoder busy, skipping to next keyframe")
                        continue
                    }
                    needKeyframe = false
                }
                onEos?.invoke()
            } catch (t: Throwable) {
                if (isActive) {
                    Log.e(TAG, "demux/decode failed", t)
                    onError?.invoke(t)
                }
            }
        }
    }

    /**
     * @return false if decoder is behind and frame was skipped (caller should wait for keyframe)
     */
    private suspend fun feedPacket(packet: VideoPacket): Boolean {
        mutex.withLock {
            val c = codec ?: return false
            if (!configured) {
                val surface = surfaceRef.get() ?: return false
                val meta = currentMeta ?: return false
                configureCodecLocked(c, meta, surface)
            }

            // Short wait — prefer skipping over growing latency.
            drainOutputLocked(c, renderLatestOnly = true)
            var inputIndex = c.dequeueInputBuffer(3_000)
            if (inputIndex < 0) {
                drainOutputLocked(c, renderLatestOnly = true)
                inputIndex = c.dequeueInputBuffer(3_000)
            }
            if (inputIndex < 0) {
                // Keyframes get one more chance; otherwise skip to catch up.
                if (packet.isKeyFrame) {
                    drainOutputLocked(c, renderLatestOnly = true)
                    inputIndex = c.dequeueInputBuffer(15_000)
                }
                if (inputIndex < 0) return false
            }

            val buffer = c.getInputBuffer(inputIndex) ?: return false
            buffer.clear()
            if (packet.data.size > buffer.remaining()) {
                Log.w(TAG, "packet too large ${packet.data.size}")
                return true
            }
            buffer.put(packet.data)
            val flags = if (packet.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            c.queueInputBuffer(inputIndex, 0, packet.data.size, packet.pts, flags)
            drainOutputLocked(c, renderLatestOnly = true)
            return true
        }
    }

    /**
     * When several frames are decoded, only present the newest to cut display latency.
     */
    private fun drainOutputLocked(c: MediaCodec, renderLatestOnly: Boolean = true) {
        val info = MediaCodec.BufferInfo()
        var pending = -1
        var pendingRender = false
        while (true) {
            val outIndex = try {
                c.dequeueOutputBuffer(info, 0)
            } catch (t: IllegalStateException) {
                Log.w(TAG, "dequeueOutputBuffer", t)
                break
            }
            when {
                outIndex >= 0 -> {
                    if (pending >= 0) {
                        runCatching { c.releaseOutputBuffer(pending, false) }
                    }
                    pending = outIndex
                    pendingRender = hasSurface.get() && info.size > 0
                    if (!renderLatestOnly) {
                        runCatching { c.releaseOutputBuffer(pending, pendingRender) }
                        pending = -1
                    }
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = c.outputFormat
                    val w = format.getInteger(MediaFormat.KEY_WIDTH)
                    val h = format.getInteger(MediaFormat.KEY_HEIGHT)
                    if (w > 0 && h > 0) {
                        currentMeta = currentMeta?.copy(width = w, height = h)
                        onSizeChanged?.invoke(w, h)
                    }
                }
                else -> break
            }
        }
        if (pending >= 0) {
            runCatching { c.releaseOutputBuffer(pending, pendingRender) }
        }
    }

    private fun configureCodecLocked(c: MediaCodec, meta: VideoCodecMeta, surface: Surface) {
        val format = MediaFormat.createVideoFormat(meta.mimeType, meta.width, meta.height)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
        if (Build.VERSION.SDK_INT >= 30) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        applyCsd(format, meta.codecId, codecConfig)
        c.configure(format, surface, null, 0)
        c.start()
        configured = true
        Log.i(TAG, "codec configured ${meta.mimeType} ${meta.width}x${meta.height}")
    }

    private fun applyCsd(format: MediaFormat, codecId: Int, config: ByteArray?) {
        if (config == null || config.isEmpty()) return
        when (codecId) {
            ScrcpyConstants.CODEC_ID_H264 -> {
                val pair = splitAvcCsd(config) ?: return
                format.setByteBuffer("csd-0", ByteBuffer.wrap(pair.first))
                format.setByteBuffer("csd-1", ByteBuffer.wrap(pair.second))
            }
            ScrcpyConstants.CODEC_ID_H265 -> {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(config))
            }
        }
    }

    private fun splitAvcCsd(config: ByteArray): Pair<ByteArray, ByteArray>? {
        val nalus = splitAnnexB(config)
        val sps = nalus.firstOrNull { (it.getOrNull(0)?.toInt() ?: 0) and 0x1F == 7 } ?: return null
        val pps = nalus.firstOrNull { (it.getOrNull(0)?.toInt() ?: 0) and 0x1F == 8 } ?: return null
        return sps to pps
    }

    private fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val result = ArrayList<ByteArray>()
        var i = 0
        fun startCodeLen(idx: Int): Int {
            if (idx + 3 < data.size &&
                data[idx] == 0.toByte() && data[idx + 1] == 0.toByte() &&
                data[idx + 2] == 1.toByte()
            ) return 3
            if (idx + 4 < data.size &&
                data[idx] == 0.toByte() && data[idx + 1] == 0.toByte() &&
                data[idx + 2] == 0.toByte() && data[idx + 3] == 1.toByte()
            ) return 4
            return 0
        }
        while (i < data.size) {
            val sc = startCodeLen(i)
            if (sc == 0) {
                i++
                continue
            }
            val nalStart = i + sc
            var nalEnd = data.size
            var j = nalStart
            while (j < data.size) {
                val n = startCodeLen(j)
                if (n > 0) {
                    nalEnd = j
                    break
                }
                j++
            }
            if (nalStart < nalEnd) {
                result.add(data.copyOfRange(nalStart, nalEnd))
            }
            i = nalEnd
        }
        return result
    }

    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        demuxJob?.cancel()
        demuxJob = null
        // Release asynchronously; avoid runBlocking on callers (may be main thread).
        scope.launch(Dispatchers.IO) {
            mutex.withLock { releaseCodecLocked() }
        }
    }

    private fun releaseCodecLocked() {
        runCatching {
            codec?.stop()
            codec?.release()
        }
        codec = null
        configured = false
    }

    companion object {
        private const val TAG = "VideoDecoder"
    }
}
