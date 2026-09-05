package com.scrcpyandroid.protocol

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

class VideoDemuxer(
    private val input: InputStream,
) {
    private val data = DataInputStream(input)

    /** Latest codec config (SPS/PPS etc.), retained across frames for MediaCodec CSD. */
    var lastConfig: ByteArray? = null
        private set

    fun readCodecMeta(): VideoCodecMeta {
        val codecId = data.readInt()
        if (codecId == 0) error("Video stream disabled by device")
        if (codecId == 1) error("Video stream configuration error on device")
        val width = data.readInt()
        val height = data.readInt()
        return VideoCodecMeta(codecId, width, height)
    }

    /**
     * Reads the next media packet. Config packets update [lastConfig] and are
     * prepended to the following frame for decoders that need in-band SPS/PPS.
     */
    fun readMergedPacket(): VideoPacket? {
        while (true) {
            val packet = readRawPacket() ?: return null
            if (packet.isConfig) {
                lastConfig = packet.data
                continue
            }
            val config = lastConfig
            val merged = if (config != null && packet.isKeyFrame) {
                // Only prepend config to keyframes (IDR), matching typical MediaCodec needs.
                ByteArray(config.size + packet.data.size).also {
                    System.arraycopy(config, 0, it, 0, config.size)
                    System.arraycopy(packet.data, 0, it, config.size, packet.data.size)
                }
            } else {
                packet.data
            }
            return packet.copy(data = merged)
        }
    }

    private fun readRawPacket(): VideoPacket? {
        val header = ByteArray(ScrcpyConstants.PACKET_HEADER_SIZE)
        if (!readFully(header)) return null
        val ptsFlags = Binary.readU64Be(header, 0)
        val size = Binary.readU32Be(header, 8)
        if (size <= 0) return null
        val payload = ByteArray(size)
        if (!readFully(payload)) return null
        val isConfig = (ptsFlags and ScrcpyConstants.PACKET_FLAG_CONFIG) != 0L
        val isKey = (ptsFlags and ScrcpyConstants.PACKET_FLAG_KEY_FRAME) != 0L
        val pts = ptsFlags and ScrcpyConstants.PACKET_PTS_MASK
        return VideoPacket(pts, isConfig, isKey, payload)
    }

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
