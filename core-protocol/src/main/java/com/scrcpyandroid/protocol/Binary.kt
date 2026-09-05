package com.scrcpyandroid.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

object Binary {
    fun readU32Be(buf: ByteArray, offset: Int = 0): Int {
        return ByteBuffer.wrap(buf, offset, 4).order(ByteOrder.BIG_ENDIAN).int
    }

    fun readU64Be(buf: ByteArray, offset: Int = 0): Long {
        return ByteBuffer.wrap(buf, offset, 8).order(ByteOrder.BIG_ENDIAN).long
    }

    fun writeU16Be(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 1] = (value and 0xFF).toByte()
    }

    fun writeU32Be(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }

    fun writeU64Be(buf: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            buf[offset + i] = ((value ushr ((7 - i) * 8)) and 0xFF).toByte()
        }
    }

    fun floatToU16fp(value: Float): Int {
        val clamped = value.coerceIn(0f, 1f)
        return (clamped * 0xFFFF).toInt() and 0xFFFF
    }
}
