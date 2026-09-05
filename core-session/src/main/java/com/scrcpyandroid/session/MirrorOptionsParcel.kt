package com.scrcpyandroid.session

import com.scrcpyandroid.protocol.AudioCodecOption
import com.scrcpyandroid.protocol.MirrorOptions
import com.scrcpyandroid.protocol.VideoCodecOption
import java.io.Serializable

data class MirrorOptionsParcel(
    val host: String,
    val port: Int,
    val maxSize: Int,
    val videoBitRate: Int,
    val maxFps: Int,
    val videoCodec: String,
    val audioEnabled: Boolean,
    val audioCodec: String,
    val stayAwake: Boolean,
    val turnScreenOff: Boolean,
) : Serializable {
    fun toOptions(): MirrorOptions = MirrorOptions(
        host = host,
        port = port,
        maxSize = maxSize,
        videoBitRate = videoBitRate,
        maxFps = maxFps,
        videoCodec = VideoCodecOption.entries.firstOrNull { it.serverValue == videoCodec }
            ?: VideoCodecOption.H264,
        audioEnabled = audioEnabled,
        audioCodec = AudioCodecOption.entries.firstOrNull { it.serverValue == audioCodec }
            ?: AudioCodecOption.OPUS,
        stayAwake = stayAwake,
        turnScreenOff = turnScreenOff,
    )

    companion object {
        fun from(options: MirrorOptions) = MirrorOptionsParcel(
            host = options.host,
            port = options.port,
            maxSize = options.maxSize,
            videoBitRate = options.videoBitRate,
            maxFps = options.maxFps,
            videoCodec = options.videoCodec.serverValue,
            audioEnabled = options.audioEnabled,
            audioCodec = options.audioCodec.serverValue,
            stayAwake = options.stayAwake,
            turnScreenOff = options.turnScreenOff,
        )
    }
}
