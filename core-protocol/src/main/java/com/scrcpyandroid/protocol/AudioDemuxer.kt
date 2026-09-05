package com.scrcpyandroid.protocol

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/**
 * Demuxes scrcpy audio stream (codec id + 12-byte framed packets).
 */
class AudioDemuxer(
    private val input: InputStream,
) {
    private val data = DataInputStream(input)
    var lastConfig: ByteArray? = null
        private set

    fun readCodecId(): Int = data.readInt()

    /** Next framed packet (config or media). Updates [lastConfig] when config. */
    fun readRawPacket(): AudioPacket? {
        val header = ByteArray(ScrcpyConstants.PACKET_HEADER_SIZE)
        if (!readFully(header)) return null
        val ptsFlags = Binary.readU64Be(header, 0)
        val size = Binary.readU32Be(header, 8)
        if (size <= 0) return null
        val payload = ByteArray(size)
        if (!readFully(payload)) return null
        val isConfig = (ptsFlags and ScrcpyConstants.PACKET_FLAG_CONFIG) != 0L
        val pts = ptsFlags and ScrcpyConstants.PACKET_PTS_MASK
        if (isConfig) {
            lastConfig = payload
        }
        return AudioPacket(pts, isConfig, payload, if (isConfig) payload else lastConfig)
    }

    /** Skips config packets; returns next media frame (with latest config attached). */
    fun readNextMediaOrNull(): AudioPacket? {
        while (true) {
            val packet = readRawPacket() ?: return null
            if (!packet.isConfig) return packet
        }
    }

    @Deprecated("Use readRawPacket / readNextMediaOrNull", ReplaceWith("readNextMediaOrNull()"))
    fun readMergedPacket(): AudioPacket? = readNextMediaOrNull()

    private fun readFully(buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = try {
                data.read(buffer, offset, buffer.size - offset)
            } catch (_: EOFException) {
                return false
            }
            if (read < 0) return false
            offset += read
        }
        return true
    }
}

data class AudioPacket(
    val pts: Long,
    val isConfig: Boolean,
    val data: ByteArray,
    val config: ByteArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioPacket) return false
        return pts == other.pts && isConfig == other.isConfig &&
            data.contentEquals(other.data) &&
            (config == null && other.config == null || config.contentEquals(other.config))
    }

    override fun hashCode(): Int {
        var result = pts.hashCode()
        result = 31 * result + isConfig.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + (config?.contentHashCode() ?: 0)
        return result
    }
}
