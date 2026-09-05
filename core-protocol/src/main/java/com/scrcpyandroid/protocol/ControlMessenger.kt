package com.scrcpyandroid.protocol

import java.io.OutputStream

/**
 * Serializes scrcpy 2.7 control messages.
 */
class ControlMessenger(
    private val output: OutputStream,
) {
    @Synchronized
    fun injectKeycode(action: Int, keycode: Int, repeat: Int = 0, metastate: Int = 0) {
        val buf = ByteArray(14)
        buf[0] = ScrcpyConstants.TYPE_INJECT_KEYCODE
        buf[1] = action.toByte()
        Binary.writeU32Be(buf, 2, keycode)
        Binary.writeU32Be(buf, 6, repeat)
        Binary.writeU32Be(buf, 10, metastate)
        output.write(buf)
        output.flush()
    }

    @Synchronized
    fun injectKeycodeClick(keycode: Int) {
        injectKeycode(ScrcpyConstants.KEY_ACTION_DOWN, keycode)
        injectKeycode(ScrcpyConstants.KEY_ACTION_UP, keycode)
    }

    @Synchronized
    fun backOrScreenOn(action: Int) {
        val buf = ByteArray(2)
        buf[0] = ScrcpyConstants.TYPE_BACK_OR_SCREEN_ON
        buf[1] = action.toByte()
        output.write(buf)
        output.flush()
    }

    @Synchronized
    fun injectTouch(
        action: Int,
        pointerId: Long,
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        pressure: Float,
        actionButton: Int = 0,
        buttons: Int = 0,
    ) {
        val buf = ByteArray(32)
        buf[0] = ScrcpyConstants.TYPE_INJECT_TOUCH_EVENT
        buf[1] = action.toByte()
        Binary.writeU64Be(buf, 2, pointerId)
        Binary.writeU32Be(buf, 10, x)
        Binary.writeU32Be(buf, 14, y)
        Binary.writeU16Be(buf, 18, screenWidth)
        Binary.writeU16Be(buf, 20, screenHeight)
        Binary.writeU16Be(buf, 22, Binary.floatToU16fp(pressure))
        Binary.writeU32Be(buf, 24, actionButton)
        Binary.writeU32Be(buf, 28, buttons)
        output.write(buf)
        output.flush()
    }

    fun home() = injectKeycodeClick(ScrcpyConstants.KEYCODE_HOME)

    fun back() {
        backOrScreenOn(ScrcpyConstants.KEY_ACTION_DOWN)
        backOrScreenOn(ScrcpyConstants.KEY_ACTION_UP)
    }

    fun recents() = injectKeycodeClick(ScrcpyConstants.KEYCODE_APP_SWITCH)

    fun volumeUp() = injectKeycodeClick(ScrcpyConstants.KEYCODE_VOLUME_UP)

    fun volumeDown() = injectKeycodeClick(ScrcpyConstants.KEYCODE_VOLUME_DOWN)

    fun volumeMute() = injectKeycodeClick(ScrcpyConstants.KEYCODE_VOLUME_MUTE)
}
