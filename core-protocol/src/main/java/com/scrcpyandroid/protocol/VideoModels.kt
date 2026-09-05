package com.scrcpyandroid.protocol

data class VideoCodecMeta(
    val codecId: Int,
    val width: Int,
    val height: Int,
) {
    val mimeType: String
        get() = when (codecId) {
            ScrcpyConstants.CODEC_ID_H264 -> "video/avc"
            ScrcpyConstants.CODEC_ID_H265 -> "video/hevc"
            ScrcpyConstants.CODEC_ID_AV1 -> "video/av01"
            else -> error("Unsupported codec id: 0x${codecId.toString(16)}")
        }
}

data class VideoPacket(
    val pts: Long,
    val isConfig: Boolean,
    val isKeyFrame: Boolean,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VideoPacket) return false
        return pts == other.pts &&
            isConfig == other.isConfig &&
            isKeyFrame == other.isKeyFrame &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = pts.hashCode()
        result = 31 * result + isConfig.hashCode()
        result = 31 * result + isKeyFrame.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}
