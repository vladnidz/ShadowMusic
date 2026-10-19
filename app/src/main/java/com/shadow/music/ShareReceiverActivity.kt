package com.shadow.music

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamExtractor
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class ShareReceiverActivity : AppCompatActivity() {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        private var isNewPipeInitialized = false

        @Synchronized
        fun ensureNewPipeInitialized(client: OkHttpClient) {
            if (isNewPipeInitialized) return

            NewPipe.init(object : Downloader() {
                private val USER_AGENT =
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

                override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): Response {
                    val httpMethod = request.httpMethod() ?: "GET"
                    val url = request.url()
                    val dataToSend = request.dataToSend()

                    val requestBody: RequestBody? = if (dataToSend != null) {
                        RequestBody.create(null, dataToSend)
                    } else if (httpMethod.equals("POST", ignoreCase = true) || httpMethod.equals("PUT", ignoreCase = true)) {
                        RequestBody.create(null, ByteArray(0))
                    } else {
                        null
                    }

                    val builder = Request.Builder()
                        .method(httpMethod, requestBody)
                        .url(url)
                        .addHeader("User-Agent", USER_AGENT)
                        .addHeader("Accept-Language", "en-US,en;q=0.9")

                    request.headers()?.forEach { (headerName, headerValues) ->
                        builder.removeHeader(headerName)
                        headerValues.forEach { headerValue ->
                            builder.addHeader(headerName, headerValue)
                        }
                    }

                    val response = client.newCall(builder.build()).execute()
                    val responseBody = response.body?.string() ?: ""
                    val latestUrl = response.request.url.toString()

                    return Response(
                        response.code,
                        response.message,
                        response.headers.toMultimap(),
                        responseBody,
                        latestUrl
                    )
                }
            })
            isNewPipeInitialized = true
        }
    }

    private var pendingUrl: String? = null
    private lateinit var tvTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_share_receiver)

        tvTitle = findViewById(R.id.tvTrackTitle)
        tvStatus = findViewById(R.id.tvTransferStatus)
        progressBar = findViewById(R.id.progressBar)

        ensureNewPipeInitialized(httpClient)

        val action = intent?.action
        val type = intent?.type

        if (Intent.ACTION_SEND == action && type != null) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            val urlMatch = Regex("https?://[^\\s]+").find(sharedText)
            val extractedUrl = urlMatch?.value ?: sharedText.trim()

            if (extractedUrl.isBlank()) {
                tvStatus.text = "❌ Пустая ссылка"
                return
            }

            pendingUrl = extractedUrl

            if (checkPermissions()) {
                startTransferProcess(extractedUrl)
            } else {
                requestRequiredPermissions()
            }
        } else {
            tvStatus.text = "❌ Поделитесь треком из YouTube или YouTube Music"
        }
    }

    private fun checkPermissions(): Boolean {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestRequiredPermissions() {
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
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 2002)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val url = pendingUrl
        if (url != null) {
            startTransferProcess(url)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startTransferProcess(rawUrl: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Поиск часов
                withContext(Dispatchers.Main) {
                    progressBar.progress = 5
                    tvTitle.text = "🎵 Обработка трека..."
                    tvStatus.text = "🔍 Проверка Bluetooth и поиск Huawei Watch..."
                }

                val watch = findHuaweiWatch()

                withContext(Dispatchers.Main) {
                    progressBar.progress = 15
                    if (watch != null) {
                        tvStatus.text = "✅ Найдено: ${watch.name}\n🔍 Извлечение аудиопотока..."
                    } else {
                        tvStatus.text = "🔍 Извлечение аудиопотока..."
                    }
                }

                // 2. Нормализация URL
                val cleanUrl = rawUrl
                    .replace("music.youtube.com", "www.youtube.com")
                    .replace("m.youtube.com", "www.youtube.com")

                val extractor: StreamExtractor = ServiceList.YouTube.getStreamExtractor(cleanUrl)
                extractor.fetchPage()

                val trackTitle = extractor.name
                val trackArtist = try { extractor.uploaderName } catch (e: Exception) { "YouTube" }

                withContext(Dispatchers.Main) {
                    progressBar.progress = 25
                    tvTitle.text = "🎵 $trackTitle"
                    tvStatus.text = "🎤 $trackArtist\n⚡ Быстрое скачивание потока..."
                }

                // 3. Выбор лучшего аудиопотока
                val audioStream: AudioStream = extractor.audioStreams
                    .filter { it.format?.mimeType?.contains("audio") == true || it.format?.mimeType?.contains("mp4") == true }
                    .maxByOrNull { it.averageBitrate }
                    ?: extractor.audioStreams.firstOrNull()
                    ?: throw Exception("Аудиопоток не найден на YouTube")

                val audioUrl = audioStream.content

                // 4. Быстрое скачивание через 1MB Range-чанки (обход 128kbps шейпера YouTube)
                val outDir = File(externalCacheDir ?: cacheDir, "shadowmusic").also { it.mkdirs() }
                val safeTitle = trackTitle.replace(Regex("[^\\w\\sА-Яа-яЁё-]"), "").trim().take(70)
                val ext = if (audioStream.format?.mimeType?.contains("webm") == true) "webm" else "m4a"
                val audioFile = File(outDir, "$safeTitle.$ext")

                downloadStreamUnthrottled(audioUrl, audioFile) { percent, downloaded, total ->
                    runOnUiThread {
                        progressBar.progress = 25 + (percent * 0.55).toInt()
                        val totalKb = if (total > 0) total / 1024 else 0
                        tvStatus.text = "⬇️ Скачивание: ${downloaded / 1024} KB" +
                                (if (totalKb > 0) " / $totalKb KB ($percent%)" else "")
                    }
                }

                withContext(Dispatchers.Main) {
                    progressBar.progress = 90
                    tvStatus.text = "📲 Запуск Bluetooth передачи на часы..."
                }

                // 5. Отправка файла через Bluetooth
                withContext(Dispatchers.Main) {
                    shareAudioViaBluetooth(audioFile)
                    progressBar.progress = 100
                    tvStatus.text = "🚀 Передача запущена!\nВыберите Huawei Watch в списке Bluetooth."
                    Toast.makeText(applicationContext, "⌚ Выберите часы в меню Bluetooth!", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.progress = 0
                    tvStatus.text = "❌ Ошибка: ${e.message}"
                }
            }
        }
    }

    private fun downloadStreamUnthrottled(
        streamUrl: String,
        destFile: File,
        onProgress: (Int, Long, Long) -> Unit
    ) {
        val chunkSize = 1024 * 1024L // 1 MB Range-чанки обходят throttling
        var currentOffset = 0L
        var totalBytes = -1L

        // Попытка узнать размер
        try {
            val headReq = Request.Builder()
                .url(streamUrl)
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://www.youtube.com/")
                .head()
                .build()
            val headResp = httpClient.newCall(headReq).execute()
            totalBytes = headResp.header("Content-Length")?.toLongOrNull() ?: -1L
            headResp.close()
        } catch (e: Exception) {}

        FileOutputStream(destFile).use { outStream ->
            var keepDownloading = true
            while (keepDownloading) {
                val endByte = if (totalBytes > 0) minOf(currentOffset + chunkSize - 1, totalBytes - 1) else currentOffset + chunkSize - 1
                val chunkReq = Request.Builder()
                    .url(streamUrl)
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Range", "bytes=$currentOffset-$endByte")
                    .header("Referer", "https://www.youtube.com/")
                    .build()

                val chunkResp = httpClient.newCall(chunkReq).execute()
                val code = chunkResp.code

                if (code == 206) {
                    val bytes = chunkResp.body?.bytes() ?: break
                    if (bytes.isEmpty()) break
                    outStream.write(bytes)
                    currentOffset += bytes.size

                    val contentRange = chunkResp.header("Content-Range")
                    if (contentRange != null && totalBytes <= 0) {
                        val parsed = Regex("/(\\d+)").find(contentRange)?.groupValues?.get(1)?.toLongOrNull()
                        if (parsed != null && parsed > 0) totalBytes = parsed
                    }

                    if (totalBytes > 0) {
                        val pct = ((currentOffset * 100) / totalBytes).toInt().coerceIn(0, 100)
                        onProgress(pct, currentOffset, totalBytes)
                        if (currentOffset >= totalBytes) keepDownloading = false
                    } else {
                        onProgress(50, currentOffset, -1L)
                    }
                } else if (code == 200) {
                    // Fallback на обычный поток если сервер не отдал 206
                    val body = chunkResp.body ?: break
                    val streamTotal = body.contentLength().takeIf { it > 0 } ?: totalBytes
                    val inStream = body.byteStream()
                    val buf = ByteArray(32768)
                    var read: Int
                    while (inStream.read(buf).also { read = it } != -1) {
                        outStream.write(buf, 0, read)
                        currentOffset += read
                        if (streamTotal > 0) {
                            val pct = ((currentOffset * 100) / streamTotal).toInt().coerceIn(0, 100)
                            onProgress(pct, currentOffset, streamTotal)
                        }
                    }
                    keepDownloading = false
                } else {
                    keepDownloading = false
                }
                chunkResp.close()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun findHuaweiWatch(): BluetoothDevice? {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter ?: return null
        if (!adapter.isEnabled) return null
        return adapter.bondedDevices?.firstOrNull { dev ->
            val name = dev.name ?: ""
            name.contains("HUAWEI", ignoreCase = true) ||
            name.contains("Watch", ignoreCase = true) ||
            name.contains("HONOR", ignoreCase = true)
        }
    }

    private fun shareAudioViaBluetooth(file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            file
        )

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        // Поиск зарегистрированного обработчика Bluetooth на устройстве (в т.ч. Xiaomi HyperOS)
        val pm = packageManager
        val resolveList = pm.queryIntentActivities(sendIntent, PackageManager.MATCH_DEFAULT_ONLY)
        val btResolve = resolveList.firstOrNull {
            it.activityInfo.packageName.contains("bluetooth", ignoreCase = true) ||
            it.activityInfo.name.contains("bluetooth", ignoreCase = true)
        }

        if (btResolve != null) {
            sendIntent.setClassName(btResolve.activityInfo.packageName, btResolve.activityInfo.name)
            try {
                startActivity(sendIntent)
                return
            } catch (e: Exception) {}
        }

        // Fallback: системный диалог шаринга
        val chooser = Intent.createChooser(sendIntent, "Отправить на Huawei Watch через Bluetooth")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(chooser)
    }
}
