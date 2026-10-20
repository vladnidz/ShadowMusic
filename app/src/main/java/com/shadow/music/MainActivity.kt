package com.shadow.music

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dadb.Dadb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvDetails: TextView
    private lateinit var btnRefreshStatus: ImageButton

    // Track Manager UI
    private lateinit var tvTracksSummary: TextView
    private lateinit var btnSelectAll: Button
    private lateinit var btnUploadSelected: Button
    private lateinit var btnDeleteSelected: Button
    private lateinit var rvTracks: RecyclerView
    private lateinit var tvEmptyTracks: TextView

    private val trackList = mutableListOf<TrackItem>()
    private lateinit var trackAdapter: TrackAdapter

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadLocalTracks()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvWatchStatus)
        tvDetails = findViewById(R.id.tvWatchDetails)
        btnRefreshStatus = findViewById(R.id.btnRefreshStatus)

        tvTracksSummary = findViewById(R.id.tvTracksSummary)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnUploadSelected = findViewById(R.id.btnUploadSelected)
        btnDeleteSelected = findViewById(R.id.btnDeleteSelected)
        rvTracks = findViewById(R.id.rvTracks)
        tvEmptyTracks = findViewById(R.id.tvEmptyTracks)

        setupTrackManager()

        btnRefreshStatus.setOnClickListener {
            checkAndRequestPermissions()
            checkRealHardwareStatus()
            loadLocalTracks()
        }

        btnSelectAll.setOnClickListener {
            val selectAll = !trackAdapter.isAllSelected()
            trackAdapter.selectAll(selectAll)
            btnSelectAll.text = if (selectAll) "Снять все" else "Выбрать все"
        }

        btnUploadSelected.setOnClickListener {
            val selected = trackAdapter.getSelectedTracks()
            if (selected.isEmpty()) {
                Toast.makeText(this, "Выберите треки для отправки", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            uploadTracksToWatch(selected)
        }

        btnDeleteSelected.setOnClickListener {
            val selected = trackAdapter.getSelectedTracks()
            if (selected.isEmpty()) {
                Toast.makeText(this, "Выберите треки для удаления", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            deleteTracks(selected)
        }

        val filter = IntentFilter(TransferService.ACTION_TRACKS_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(updateReceiver, filter)
        }

        checkAndRequestPermissions()
        checkRealHardwareStatus()
        loadLocalTracks()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(updateReceiver)
        } catch (e: Exception) {}
    }

    private fun setupTrackManager() {
        trackAdapter = TrackAdapter(
            tracks = trackList,
            onSelectionChanged = { updateActionButtons() },
            onSingleUpload = { item -> uploadTracksToWatch(listOf(item)) },
            onSingleDelete = { item -> deleteTracks(listOf(item)) }
        )
        rvTracks.layoutManager = LinearLayoutManager(this)
        rvTracks.adapter = trackAdapter
    }

    private fun loadLocalTracks() {
        val outDir = File(externalCacheDir ?: cacheDir, "shadowmusic")
        val files = outDir.listFiles { file ->
            file.isFile && (file.extension.equals("mp3", true) || file.extension.equals("m4a", true))
        }?.sortedByDescending { it.lastModified() } ?: emptyList()

        trackList.clear()
        files.forEach { trackList.add(TrackItem(it)) }
        trackAdapter.notifyDataSetChanged()

        updateActionButtons()
    }

    private fun updateActionButtons() {
        val count = trackList.size
        val totalBytes = trackList.sumOf { it.file.length() }
        val totalMb = totalBytes.toDouble() / (1024 * 1024)

        tvTracksSummary.text = String.format(Locale.US, "%d треков • %.1f МБ", count, totalMb)

        val selected = trackAdapter.getSelectedTracks()
        btnUploadSelected.text = "⚡ Отправить (${selected.size})"
        btnDeleteSelected.text = "🗑 Удалить (${selected.size})"

        btnSelectAll.text = if (trackAdapter.isAllSelected()) "Снять все" else "Выбрать все"

        if (trackList.isEmpty()) {
            tvEmptyTracks.visibility = View.VISIBLE
            rvTracks.visibility = View.GONE
        } else {
            tvEmptyTracks.visibility = View.GONE
            rvTracks.visibility = View.VISIBLE
        }
    }

    private fun deleteTracks(items: List<TrackItem>) {
        AlertDialog.Builder(this)
            .setTitle("Удаление треков")
            .setMessage("Удалить выбранные треки (${items.size} шт.) из памяти телефона?")
            .setPositiveButton("Удалить") { _, _ ->
                var deletedCount = 0
                items.forEach {
                    if (it.file.delete()) deletedCount++
                }
                Toast.makeText(this, "Удалено треков: $deletedCount", Toast.LENGTH_SHORT).show()
                loadLocalTracks()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun uploadTracksToWatch(items: List<TrackItem>) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Отправка на Huawei Watch")
            .setMessage("Поиск часов по Wi-Fi...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        CoroutineScope(Dispatchers.IO).launch {
            val watchIp = findWatchWifiIp()
            if (watchIp != null) {
                val keyPair = getOrCreateAdbKeyPair()
                val dadbInstance = Dadb.create(watchIp, 5555, keyPair)
                try {
                    val targetDir = "/sdcard/Music/Imported Songs"
                    dadbInstance.shell("mkdir -p '$targetDir'")

                    items.forEachIndexed { index, item ->
                        withContext(Dispatchers.Main) {
                            progressDialog.setMessage("Загрузка [${index + 1}/${items.size}]:\n${item.title}")
                        }
                        val fileName = item.file.name
                        dadbInstance.push(item.file, "$targetDir/$fileName")
                        dadbInstance.shell("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d 'file://$targetDir/$fileName'")
                        dadbInstance.shell("media scan-file '$targetDir/$fileName'")
                    }

                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        Toast.makeText(applicationContext, "✅ Передано на часы: ${items.size} треков!", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        Toast.makeText(applicationContext, "❌ Ошибка передачи: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } finally {
                    dadbInstance.close()
                }
            } else {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    // Fallback to Bluetooth
                    shareTracksViaBluetooth(items.map { it.file })
                }
            }
        }
    }

    private fun shareTracksViaBluetooth(files: List<File>) {
        if (files.isEmpty()) return
        val uris = ArrayList<Uri>()
        files.forEach {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", it)
            uris.add(uri)
        }

        val shareIntent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, uris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "audio/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        val chooser = Intent.createChooser(shareIntent, "Отправить на Huawei Watch (Bluetooth)")
        startActivity(chooser)
    }

    private fun getOrCreateAdbKeyPair(): dadb.AdbKeyPair {
        val privKey = File(filesDir, "adbkey")
        val pubKey = File(filesDir, "adbkey.pub")
        if (!privKey.exists() || !pubKey.exists()) {
            dadb.AdbKeyPair.generate(privKey, pubKey)
        }
        return dadb.AdbKeyPair.read(privKey, pubKey)
    }

    private fun getLocalSubnets(): List<String> {
        val subnets = mutableSetOf("192.168.1", "192.168.0", "192.168.43")
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        val parts = host.split(".")
                        if (parts.size == 4) {
                            subnets.add("${parts[0]}.${parts[1]}.${parts[2]}")
                        }
                    }
                }
            }
        } catch (e: Exception) {}
        return subnets.toList()
    }

    private suspend fun findWatchWifiIp(): String? = withContext(Dispatchers.IO) {
        val candidates = listOf(
            "192.168.1.116", "192.168.0.116", "192.168.43.116",
            "192.168.1.242", "192.168.0.242", "192.168.43.2",
            "192.168.1.100", "192.168.0.100"
        )
        for (ip in candidates) {
            if (testSocket(ip, 5555)) return@withContext ip
        }

        val found = AtomicReference<String?>(null)
        val subnets = getLocalSubnets()
        coroutineScope {
            val jobs = subnets.flatMap { sub ->
                (2..254).map { host ->
                    async {
                        if (found.get() != null) return@async
                        val ip = "$sub.$host"
                        if (testSocket(ip, 5555)) {
                            found.set(ip)
                        }
                    }
                }
            }
            jobs.awaitAll()
        }
        return@withContext found.get()
    }

    private fun testSocket(ip: String, port: Int): Boolean {
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(ip, port), 250)
            s.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)
        }
    }

    @SuppressLint("MissingPermission")
    private fun isDeviceConnected(device: BluetoothDevice, manager: BluetoothManager?): Boolean {
        if (manager != null) {
            try {
                val gattState = manager.getConnectionState(device, BluetoothProfile.GATT)
                if (gattState == BluetoothProfile.STATE_CONNECTED) return true
            } catch (e: Exception) {}
        }

        try {
            val method = device.javaClass.getMethod("isConnected")
            val connected = method.invoke(device) as? Boolean
            if (connected == true) return true
        } catch (e: Exception) {}

        return false
    }

    @SuppressLint("MissingPermission")
    private fun checkRealHardwareStatus() {
        tvStatus.text = "⏳ Проверка радиомодулей..."
        tvDetails.text = "Опрос Bluetooth и Wi-Fi..."

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter

        val isBtEnabled = bluetoothAdapter?.isEnabled == true

        var matchedDevice: BluetoothDevice? = null
        if (isBtEnabled) {
            try {
                val bonded = bluetoothAdapter?.bondedDevices
                if (bonded != null) {
                    for (dev in bonded) {
                        val name = dev.name ?: ""
                        if (name.contains("HUAWEI", ignoreCase = true) ||
                            name.contains("Watch", ignoreCase = true) ||
                            name.contains("HONOR", ignoreCase = true)
                        ) {
                            matchedDevice = dev
                            break
                        }
                    }
                }
            } catch (e: Exception) {}
        }

        CoroutineScope(Dispatchers.IO).launch {
            var reachedIp: String? = null
            for (ip in listOf("192.168.1.116", "192.168.0.116", "192.168.1.242", "192.168.0.242")) {
                try {
                    val socket = Socket()
                    socket.connect(InetSocketAddress(ip, 5555), 350)
                    socket.close()
                    reachedIp = ip
                    break
                } catch (e: Exception) {}
            }

            val isBtConnected = matchedDevice != null && isDeviceConnected(matchedDevice, bluetoothManager)

            withContext(Dispatchers.Main) {
                if (reachedIp != null) {
                    tvStatus.text = "🟢 Подключено (Домашний Wi-Fi)"
                    tvDetails.text = "Канал: Прямой Wi-Fi ($reachedIp)\nПередача в 1 клик без запросов"
                    tvStatus.setTextColor(getColor(R.color.neon_green))
                } else if (isBtConnected) {
                    tvStatus.text = "🔵 Подключено (Bluetooth)"
                    tvDetails.text = "Устройство: ${matchedDevice?.name}. Часы рядом на связи"
                    tvStatus.setTextColor(getColor(R.color.neon_green))
                } else if (matchedDevice != null) {
                    tvStatus.text = "🔴 Часы не на связи (Оффлайн)"
                    tvDetails.text = "Устройство ${matchedDevice.name} сопряжено в памяти телефона, но находится вне зоны действия или выключено"
                    tvStatus.setTextColor(getColor(android.R.color.holo_red_light))
                } else if (!isBtEnabled) {
                    tvStatus.text = "🔴 Bluetooth выключен"
                    tvDetails.text = "Включите Bluetooth на телефоне"
                    tvStatus.setTextColor(getColor(android.R.color.holo_red_light))
                } else {
                    tvStatus.text = "🔴 Часы не найдены"
                    tvDetails.text = "Сопрягите часы Huawei в настройках телефона"
                    tvStatus.setTextColor(getColor(android.R.color.holo_red_light))
                }
            }
        }
    }
}
