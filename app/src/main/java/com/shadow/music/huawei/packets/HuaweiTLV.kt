package com.shadow.music.huawei.packets

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HuaweiTLV {
    private val buffer = ByteArrayOutputStream()

    fun put(tag: Byte, value: ByteArray): HuaweiTLV {
        buffer.write(tag.toInt())
        val len = value.size
        if (len < 128) {
            buffer.write(len)
        } else if (len < 16384) {
            buffer.write((len shr 8) or 0x80)
            buffer.write(len and 0xFF)
        } else {
            buffer.write(0x84)
            val b = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(len).array()
            buffer.write(b)
        }
        buffer.write(value)
        return this
    }

    fun put(tag: Byte, value: String): HuaweiTLV {
        return put(tag, value.toByteArray(Charsets.UTF_8))
    }

    fun put(tag: Byte, value: Int): HuaweiTLV {
        val b = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()
        return put(tag, b)
    }

    fun put(tag: Byte, value: Short): HuaweiTLV {
        val b = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(value).array()
        return put(tag, b)
    }

    fun put(tag: Byte, value: Byte): HuaweiTLV {
        return put(tag, byteArrayOf(value))
    }

    fun toByteArray(): ByteArray {
        return buffer.toByteArray()
    }
}
