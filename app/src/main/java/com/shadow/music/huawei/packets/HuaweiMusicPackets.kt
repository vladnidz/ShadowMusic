package com.shadow.music.huawei.packets

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HuaweiMusicPackets {
    companion object {
        const val SERVICE_FILE_UPLOAD: Byte = 0x28
        const val CMD_FILE_INFO_SEND: Byte = 0x02
        const val FILE_TYPE_MUSIC: Byte = 0x02

        const val SERVICE_MUSIC_CONTROL: Byte = 0x1E
        const val CMD_UPLOAD_SONG_INFO: Byte = 0x07

        fun buildMusicUploadRequest(
            fileName: String,
            fileSize: Int,
            title: String,
            artist: String
        ): ByteArray {
            val tlv = HuaweiTLV()
                .put(0x01.toByte(), fileName)
                .put(0x02.toByte(), fileSize)
                .put(0x03.toByte(), FILE_TYPE_MUSIC)
                .put(0x04.toByte(), "com.huawei.health")
                .put(0x05.toByte(), "com.huawei.music")
                .put(0x06.toByte(), title)
                .put(0x07.toByte(), artist)

            val payload = tlv.toByteArray()
            val header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            header.put(0x5A.toByte()) // Magic start
            header.put(0x00.toByte()) // Flag
            header.putShort(payload.size.toShort()) // Length
            header.put(SERVICE_FILE_UPLOAD)
            header.put(CMD_FILE_INFO_SEND)
            header.putShort(0x0001.toShort()) // Sequence number

            val packet = ByteArrayOutputStream()
            packet.write(header.array())
            packet.write(payload)
            return packet.toByteArray()
        }

        fun buildSongInfoMetaPacket(
            songIndex: Short,
            title: String,
            artist: String
        ): ByteArray {
            val tlv = HuaweiTLV()
                .put(0x01.toByte(), songIndex)
                .put(0x02.toByte(), title)
                .put(0x03.toByte(), artist)

            val payload = tlv.toByteArray()
            val header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            header.put(0x5A.toByte())
            header.put(0x00.toByte())
            header.putShort(payload.size.toShort())
            header.put(SERVICE_MUSIC_CONTROL)
            header.put(CMD_UPLOAD_SONG_INFO)
            header.putShort(0x0002.toShort())

            val packet = ByteArrayOutputStream()
            packet.write(header.array())
            packet.write(payload)
            return packet.toByteArray()
        }
    }
}
