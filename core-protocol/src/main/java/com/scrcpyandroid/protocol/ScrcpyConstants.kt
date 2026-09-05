package com.scrcpyandroid.protocol

object ScrcpyConstants {
    const val SERVER_VERSION = "2.7"
    const val DEVICE_NAME_FIELD_LENGTH = 64
    const val PACKET_HEADER_SIZE = 12
    const val SOCKET_NAME_PREFIX = "scrcpy"

    const val CODEC_ID_H264 = 0x68323634 // "h264"
    const val CODEC_ID_H265 = 0x68323635 // "h265"
    const val CODEC_ID_AV1 = 0x00617631 // "av1"
    const val CODEC_ID_OPUS = 0x6f707573 // "opus"
    const val CODEC_ID_AAC = 0x00616163 // "aac"
    const val CODEC_ID_FLAC = 0x666c6163 // "flac"
    const val CODEC_ID_RAW = 0x00726177 // "raw"

    const val PACKET_FLAG_CONFIG = 1L shl 63
    const val PACKET_FLAG_KEY_FRAME = 1L shl 62
    const val PACKET_PTS_MASK = PACKET_FLAG_KEY_FRAME - 1

    // Control message types (scrcpy 2.7)
    const val TYPE_INJECT_KEYCODE: Byte = 0
    const val TYPE_INJECT_TEXT: Byte = 1
    const val TYPE_INJECT_TOUCH_EVENT: Byte = 2
    const val TYPE_INJECT_SCROLL_EVENT: Byte = 3
    const val TYPE_BACK_OR_SCREEN_ON: Byte = 4
    const val TYPE_EXPAND_NOTIFICATION_PANEL: Byte = 5
    const val TYPE_EXPAND_SETTINGS_PANEL: Byte = 6
    const val TYPE_COLLAPSE_PANELS: Byte = 7
    const val TYPE_GET_CLIPBOARD: Byte = 8
    const val TYPE_SET_CLIPBOARD: Byte = 9
    const val TYPE_SET_SCREEN_POWER_MODE: Byte = 10
    const val TYPE_ROTATE_DEVICE: Byte = 11

    // Android keycodes
    const val KEYCODE_HOME = 3
    const val KEYCODE_BACK = 4
    const val KEYCODE_VOLUME_UP = 24
    const val KEYCODE_VOLUME_DOWN = 25
    const val KEYCODE_VOLUME_MUTE = 164
    const val KEYCODE_APP_SWITCH = 187

    const val KEY_ACTION_DOWN = 0
    const val KEY_ACTION_UP = 1

    // MotionEvent actions
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2
    const val ACTION_CANCEL = 3
    const val ACTION_POINTER_DOWN = 5
    const val ACTION_POINTER_UP = 6
}
