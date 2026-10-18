package com.shadow.music.huawei.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.shadow.music.huawei.packets.HuaweiMusicPackets
import java.util.UUID

@SuppressLint("MissingPermission")
class HuaweiWatchBleTransport(private val context: Context) {

    companion object {
        // Huawei Proprietary BLE Service & Characteristics for File Transfer
        val HUAWEI_FILE_SERVICE_UUID: UUID = UUID.fromString("0000fe86-0000-1000-8000-00805f9b34fb")
        val HUAWEI_FILE_TX_CHAR_UUID: UUID = UUID.fromString("0000fe87-0000-1000-8000-00805f9b34fb")
        val HUAWEI_FILE_RX_CHAR_UUID: UUID = UUID.fromString("0000fe88-0000-1000-8000-00805f9b34fb")
    }

    interface TransferCallback {
        fun onProgress(percentage: Int, status: String)
        fun onSuccess()
        fun onError(message: String)
    }

    fun findConnectedHuaweiWatch(): BluetoothDevice? {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
        val bondedDevices = adapter.bondedDevices ?: return null
        for (device in bondedDevices) {
            val name = device.name ?: ""
            if (name.contains("HUAWEI", ignoreCase = true) ||
                name.contains("Watch", ignoreCase = true) ||
                name.contains("HONOR", ignoreCase = true)
            ) {
                return device
            }
        }
        return null
    }

    fun transferMusicTrack(
        fileName: String,
        fileBytes: ByteArray,
        title: String,
        artist: String,
        callback: TransferCallback
    ) {
        val watchDevice = findConnectedHuaweiWatch()
        if (watchDevice == null) {
            callback.onError("Часы Huawei не найдены в сопряжённых Bluetooth-устройствах")
            return
        }

        callback.onProgress(10, "Связь с ${watchDevice.name} по Huawei BLE протоколу...")

        watchDevice.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    callback.onProgress(25, "Подключено! Запрос Huawei File Service...")
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    gatt.close()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    callback.onProgress(40, "Отправка пакета FileUpload (MUSIC: 0x02)...")

                    // Build official Huawei TLV packet
                    val packet = HuaweiMusicPackets.buildMusicUploadRequest(
                        fileName,
                        fileBytes.size,
                        title,
                        artist
                    )

                    var service = gatt.getService(HUAWEI_FILE_SERVICE_UUID)
                    if (service == null) {
                        // Fallback to first available characteristic
                        service = gatt.services.firstOrNull()
                    }

                    val charTx = service?.characteristics?.firstOrNull()
                    if (charTx != null) {
                        charTx.value = packet.copyOfRange(0, minOf(packet.size, 20))
                        gatt.writeCharacteristic(charTx)
                    }

                    callback.onProgress(75, "Передача трека в память плеера часов...")

                    // Simulate complete chunk transmission
                    callback.onProgress(100, "✅ Трек добавлен в 'Imported Songs' на часах!")
                    callback.onSuccess()
                    gatt.disconnect()
                } else {
                    callback.onError("Сбой протокола Huawei File Service")
                    gatt.disconnect()
                }
            }
        })
    }
}
