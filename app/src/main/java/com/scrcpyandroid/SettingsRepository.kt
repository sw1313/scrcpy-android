package com.scrcpyandroid

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.scrcpyandroid.protocol.AudioCodecOption
import com.scrcpyandroid.protocol.MirrorOptions
import com.scrcpyandroid.protocol.VideoCodecOption
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("mirror_settings")

class SettingsRepository(private val context: Context) {
    private val keyHost = stringPreferencesKey("host")
    private val keyPort = intPreferencesKey("port")
    private val keyMaxSize = intPreferencesKey("max_size")
    private val keyBitRate = intPreferencesKey("bit_rate")
    private val keyFps = intPreferencesKey("fps")
    private val keyCodec = stringPreferencesKey("codec")
    private val keyAudio = booleanPreferencesKey("audio")
    private val keyAudioCodec = stringPreferencesKey("audio_codec")
    private val keyStayAwake = booleanPreferencesKey("stay_awake")
    private val keyScreenOff = booleanPreferencesKey("screen_off")

    val optionsFlow: Flow<MirrorOptions> = context.dataStore.data.map { prefs ->
        MirrorOptions(
            host = prefs[keyHost] ?: "",
            port = prefs[keyPort] ?: 5555,
            maxSize = prefs[keyMaxSize] ?: 1280,
            videoBitRate = prefs[keyBitRate] ?: 2_000_000,
            maxFps = prefs[keyFps] ?: 30,
            videoCodec = VideoCodecOption.entries.firstOrNull { it.serverValue == prefs[keyCodec] }
                ?: VideoCodecOption.H264,
            audioEnabled = prefs[keyAudio] ?: true,
            audioCodec = AudioCodecOption.entries.firstOrNull { it.serverValue == prefs[keyAudioCodec] }
                ?: AudioCodecOption.OPUS,
            stayAwake = prefs[keyStayAwake] ?: true,
            turnScreenOff = prefs[keyScreenOff] ?: false,
        )
    }

    suspend fun save(options: MirrorOptions) {
        context.dataStore.edit { prefs ->
            prefs[keyHost] = options.host
            prefs[keyPort] = options.port
            prefs[keyMaxSize] = options.maxSize
            prefs[keyBitRate] = options.videoBitRate
            prefs[keyFps] = options.maxFps
            prefs[keyCodec] = options.videoCodec.serverValue
            prefs[keyAudio] = options.audioEnabled
            prefs[keyAudioCodec] = options.audioCodec.serverValue
            prefs[keyStayAwake] = options.stayAwake
            prefs[keyScreenOff] = options.turnScreenOff
        }
    }
}
