package com.scrcpyandroid.video

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import com.scrcpyandroid.protocol.AudioDemuxer
import com.scrcpyandroid.protocol.AudioPacket
import com.scrcpyandroid.protocol.ScrcpyConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays scrcpy audio (Opus/AAC/FLAC/RAW). Config packets become MediaCodec CSD;
 * AudioTrack is created from decoder output format (supports PCM16 / PCM float).
 */
class AudioPlayerPipeline(
    private val scope: CoroutineScope,
) {
    private var job: Job? = null
    private var track: AudioTrack? = null
    private var codec: MediaCodec? = null
    private val running = AtomicBoolean(false)

    var onError: ((Throwable) -> Unit)? = null

    fun start(input: InputStream) {
        stop()
        running.set(true)
        job = scope.launch(Dispatchers.IO) {
            try {
                val demuxer = AudioDemuxer(input)
                val codecId = demuxer.readCodecId()
                Log.i(TAG, "audio codec id=0x${codecId.toString(16)}")
                when (codecId) {
                    0 -> Log.i(TAG, "audio disabled by device")
                    1 -> Log.w(TAG, "audio configuration error on device")
                    ScrcpyConstants.CODEC_ID_RAW -> playRaw(demuxer)
                    ScrcpyConstants.CODEC_ID_AAC,
                    ScrcpyConstants.CODEC_ID_OPUS,
                    ScrcpyConstants.CODEC_ID_FLAC,
                    -> playEncoded(demuxer, codecId)
                    else -> Log.w(TAG, "unsupported audio codec 0x${codecId.toString(16)}")
                }
            } catch (t: Throwable) {
                if (isActive && running.get()) {
                    Log.e(TAG, "audio pipeline failed", t)
                    onError?.invoke(t)
                }
            } finally {
                releasePlayers()
            }
        }
    }

    private fun playRaw(demuxer: AudioDemuxer) {
        val audioTrack = createTrack(SAMPLE_RATE, CHANNEL_COUNT, AudioFormat.ENCODING_PCM_16BIT)
        track = audioTrack
        audioTrack.play()
        Log.i(TAG, "raw pcm playback started")
        var packets = 0
        var nonZero = 0L
        while (running.get()) {
            val packet = demuxer.readNextMediaOrNull() ?: break
            if (packet.data.isEmpty()) continue
            packets++
            nonZero += countNonZeroShorts(packet.data)
            writePcm(audioTrack, packet.data)
            if (packets == 1 || packets % 500 == 0) {
                Log.i(TAG, "raw packets=$packets nonzeroShorts=$nonZero")
            }
        }
    }

    private fun playEncoded(demuxer: AudioDemuxer, codecId: Int) {
        val mime = when (codecId) {
            ScrcpyConstants.CODEC_ID_AAC -> "audio/mp4a-latm"
            ScrcpyConstants.CODEC_ID_OPUS -> "audio/opus"
            ScrcpyConstants.CODEC_ID_FLAC -> "audio/flac"
            else -> return
        }

        // Pull leading config packet(s); do NOT merge into media (unlike H.264).
        var config: ByteArray? = null
        var firstMedia: AudioPacket? = null
        while (true) {
            val packet = demuxer.readRawPacket() ?: return
            if (packet.isConfig) {
                config = packet.data
                Log.i(
                    TAG,
                    "audio config size=${packet.data.size} hex=${packet.data.toHex(16)}",
                )
            } else {
                firstMedia = packet
                break
            }
        }
        val media0 = firstMedia ?: return

        if (config == null) {
            config = demuxer.lastConfig
        }
        if (config == null && codecId == ScrcpyConstants.CODEC_ID_AAC) {
            // LC-AAC / 48 kHz / stereo AudioSpecificConfig
            config = byteArrayOf(0x11, 0x90.toByte())
            Log.w(TAG, "AAC missing config packet; using default ASC 11 90")
        }
        if (config == null) {
            Log.e(TAG, "no audio codec config for $mime")
            return
        }

        val format = MediaFormat.createAudioFormat(mime, SAMPLE_RATE, CHANNEL_COUNT)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024)
        if (codecId == ScrcpyConstants.CODEC_ID_AAC) {
            format.setInteger(MediaFormat.KEY_IS_ADTS, 0)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, 2) // AAC LC
        }
        // Important: position must be 0 (allocate+put leaves position at end → empty CSD).
        val csd = ByteBuffer.allocate(config.size)
        csd.put(config)
        csd.flip()
        format.setByteBuffer("csd-0", csd)

        val decoder = MediaCodec.createDecoderByType(mime)
        codec = decoder
        decoder.configure(format, null, null, 0)
        decoder.start()
        Log.i(TAG, "decoder started mime=$mime csd=${config.size}b")

        fun ensureTrackFromOutputFormat() {
            if (track != null) return
            val out = decoder.outputFormat
            val rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val ch = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val pcmEnc = if (Build.VERSION.SDK_INT >= 24 && out.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                out.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
            Log.i(TAG, "output format rate=$rate ch=$ch pcmEnc=$pcmEnc fmt=$out")
            val t = createTrack(rate, ch, pcmEnc)
            track = t
            t.play()
        }

        var packets = 0
        var nonZero = 0L
        fun feed(data: ByteArray, pts: Long) {
            if (data.isEmpty()) return
            var inputIndex = decoder.dequeueInputBuffer(20_000)
            var spins = 0
            while (inputIndex < 0 && running.get() && spins < 100) {
                drain(decoder)
                ensureTrackFromOutputFormat()
                inputIndex = decoder.dequeueInputBuffer(20_000)
                spins++
            }
            if (inputIndex < 0) return
            val buf = decoder.getInputBuffer(inputIndex) ?: return
            buf.clear()
            buf.put(data)
            decoder.queueInputBuffer(inputIndex, 0, data.size, pts, 0)
            drain(decoder)
            ensureTrackFromOutputFormat()
        }

        feed(media0.data, media0.pts)
        while (running.get()) {
            val packet = demuxer.readRawPacket() ?: break
            if (packet.isConfig) {
                // Mid-stream config: ignore (already configured)
                continue
            }
            feed(packet.data, packet.pts)
            packets++
            track?.let { t ->
                // Sample a few decoded writes via side stats in drain
            }
            if (packets % 200 == 0) {
                Log.i(TAG, "encoded packets=$packets nonzeroPcmBytes=$nonZero track=${track != null}")
            }
        }
        // Keep draining residual
        repeat(20) { drain(decoder) }
    }

    private fun drain(decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            val outIndex = try {
                decoder.dequeueOutputBuffer(info, 0)
            } catch (_: IllegalStateException) {
                return
            }
            when {
                outIndex >= 0 -> {
                    val outBuf = decoder.getOutputBuffer(outIndex)
                    val t = track
                    if (outBuf != null && info.size > 0 && t != null) {
                        val chunk = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        outBuf.get(chunk)
                        writePcm(t, chunk)
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val out = decoder.outputFormat
                    val rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val ch = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val pcmEnc = if (Build.VERSION.SDK_INT >= 24 && out.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        out.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else {
                        AudioFormat.ENCODING_PCM_16BIT
                    }
                    Log.i(TAG, "FORMAT_CHANGED rate=$rate ch=$ch pcmEnc=$pcmEnc")
                    runCatching {
                        track?.pause()
                        track?.flush()
                        track?.release()
                    }
                    track = null
                    val t = createTrack(rate, ch, pcmEnc)
                    track = t
                    t.play()
                }
                else -> return
            }
        }
    }

    private fun writePcm(audioTrack: AudioTrack, data: ByteArray) {
        var offset = 0
        while (offset < data.size && running.get()) {
            val written = audioTrack.write(data, offset, data.size - offset)
            if (written < 0) {
                Log.w(TAG, "AudioTrack.write failed: $written")
                break
            }
            if (written == 0) break
            offset += written
        }
    }

    private fun createTrack(sampleRate: Int, channelCount: Int, pcmEncoding: Int): AudioTrack {
        val channelMask = if (channelCount >= 2) {
            AudioFormat.CHANNEL_OUT_STEREO
        } else {
            AudioFormat.CHANNEL_OUT_MONO
        }
        val encoding = when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> AudioFormat.ENCODING_PCM_FLOAT
            AudioFormat.ENCODING_PCM_8BIT -> AudioFormat.ENCODING_PCM_8BIT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(encoding)
            .setChannelMask(channelMask)
            .build()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            AudioFormat.ENCODING_PCM_8BIT -> 1
            else -> 2
        }
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
            .coerceAtLeast(sampleRate / 5 * channelCount * bytesPerSample)
        val builder = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= 26) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        return builder.build()
    }

    fun stop() {
        running.set(false)
        job?.cancel()
        job = null
        releasePlayers()
    }

    private fun releasePlayers() {
        runCatching {
            codec?.stop()
            codec?.release()
        }
        codec = null
        runCatching {
            track?.pause()
            track?.flush()
            track?.release()
        }
        track = null
    }

    companion object {
        private const val TAG = "AudioPlayer"
        private const val SAMPLE_RATE = 48_000
        private const val CHANNEL_COUNT = 2

        private fun ByteArray.toHex(max: Int): String =
            take(max).joinToString("") { b -> "%02x".format(b) }

        private fun countNonZeroShorts(data: ByteArray): Long {
            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            var n = 0L
            while (bb.remaining() >= 2) {
                if (bb.short.toInt() != 0) n++
            }
            return n
        }
    }
}
