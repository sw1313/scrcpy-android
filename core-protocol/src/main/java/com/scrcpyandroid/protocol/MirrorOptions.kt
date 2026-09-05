package com.scrcpyandroid.protocol

enum class VideoCodecOption(val serverValue: String, val label: String) {
    H264("h264", "H.264"),
    H265("h265", "H.265"),
}

enum class AudioCodecOption(val serverValue: String, val label: String) {
    OPUS("opus", "Opus（推荐）"),
    AAC("aac", "AAC"),
    RAW("raw", "PCM 原始"),
}

data class MirrorOptions(
    val host: String,
    val port: Int = 5555,
    val maxSize: Int = 1280,
    val videoBitRate: Int = 2_000_000,
    val maxFps: Int = 30,
    val videoCodec: VideoCodecOption = VideoCodecOption.H264,
    val audioEnabled: Boolean = true,
    // Opus is scrcpy's default and usually more reliable than AAC on MediaCodec.
    val audioCodec: AudioCodecOption = AudioCodecOption.OPUS,
    val stayAwake: Boolean = true,
    val turnScreenOff: Boolean = false,
) {
    fun toServerArgs(scid: Int): List<String> {
        val args = mutableListOf(
            "tunnel_forward=true",
            "audio=$audioEnabled",
            "control=true",
            "cleanup=true",
            "video_codec=${videoCodec.serverValue}",
            "video_bit_rate=$videoBitRate",
            "max_size=$maxSize",
            "max_fps=$maxFps",
            "scid=${scid.toString(16).padStart(8, '0')}",
            "send_device_meta=true",
            "send_frame_meta=true",
            "send_dummy_byte=true",
        )
        if (audioEnabled) {
            args += "audio_codec=${audioCodec.serverValue}"
            args += "audio_bit_rate=128000"
        }
        if (stayAwake) args += "stay_awake=true"
        if (turnScreenOff) args += "power_off_on_close=true"
        return args
    }

    companion object {
        val maxSizeChoices = listOf(0, 640, 800, 1024, 1280, 1920)
        val bitRateChoices = listOf(
            1_000_000 to "1 Mbps",
            2_000_000 to "2 Mbps",
            4_000_000 to "4 Mbps",
            8_000_000 to "8 Mbps",
            16_000_000 to "16 Mbps",
        )
        val fpsChoices = listOf(15, 30, 60)
    }
}
