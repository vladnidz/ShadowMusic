package com.shadow.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import dadb.Dadb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TransferService : Service() {

    companion object {
        const val ACTION_TRANSFER = "com.shadow.music.ACTION_TRANSFER"
        const val EXTRA_URL = "com.shadow.music.EXTRA_URL"
        const val CHANNEL_ID = "shadowmusic_background_transfer"
        const val NOTIFICATION_ID = 4001
        const val ACTION_TRACKS_UPDATED = "com.shadow.music.TRACKS_UPDATED"

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

        fun start(context: Context, rawUrl: String) {
            val intent = Intent(context, TransferService::class.java).apply {
                action = ACTION_TRANSFER
                putExtra(EXTRA_URL, rawUrl)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        ensureNewPipeInitialized(httpClient)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("🎵 Инициализация...", "Получение ссылки...", 0, true))

        CoroutineScope(Dispatchers.IO).launch {
            processTrack(url)
        }

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Фоновая загрузка на часы",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Прогресс скачивания и отправки треков на Huawei Watch"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        title: String,
        content: String,
        progress: Int = 0,
        indeterminate: Boolean = false,
        pendingIntent: PendingIntent? = null
    ): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = pendingIntent ?: PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (progress > 0 || indeterminate) {
            builder.setProgress(100, progress, indeterminate)
        }

        return builder.build()
    }

    private fun updateNotification(
        title: String,
        content: String,
        progress: Int = 0,
        indeterminate: Boolean = false,
        pendingIntent: PendingIntent? = null
    ) {
        val notification = buildNotification(title, content, progress, indeterminate, pendingIntent)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showFinishedNotification(title: String, content: String, success: Boolean, pendingIntent: PendingIntent? = null) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = pendingIntent ?: PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val icon = if (success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private suspend fun processTrack(rawUrl: String) {
        try {
            updateNotification("🎵 ShadowMusic", "Извлечение аудиопотока...", 15, true)

            val cleanUrl = rawUrl
                .replace("music.youtube.com", "www.youtube.com")
                .replace("m.youtube.com", "www.youtube.com")

            val extractor: StreamExtractor = ServiceList.YouTube.getStreamExtractor(cleanUrl)
            extractor.fetchPage()

            val trackTitle = extractor.name
            val trackArtist = try { extractor.uploaderName } catch (e: Exception) { "YouTube" }

            updateNotification("⬇️ Скачивание", "$trackTitle — $trackArtist", 25, false)

            val audioStream: AudioStream = extractor.audioStreams
                .filter { it.format?.mimeType?.contains("audio") == true || it.format?.mimeType?.contains("mp4") == true }
                .maxByOrNull { it.averageBitrate }
                ?: extractor.audioStreams.firstOrNull()
                ?: throw Exception("Аудиопоток не найден на YouTube")

            val audioUrl = audioStream.content

            val outDir = File(externalCacheDir ?: cacheDir, "shadowmusic").also { it.mkdirs() }
            val safeTitle = trackTitle.replace(Regex("[^\\w\\sА-Яа-яЁё-]"), "").trim().take(70)
            val audioFile = File(outDir, "$safeTitle.mp3")

            downloadStreamUnthrottled(audioUrl, audioFile) { percent, downloaded, total ->
                val totalKb = if (total > 0) total / 1024 else 0
                val text = "$trackTitle (${downloaded / 1024} KB" + (if (totalKb > 0) " / $totalKb KB)" else ")")
                updateNotification("⬇️ Скачивание ($percent%)", text, 25 + (percent * 0.55).toInt(), false)
            }

            // Уведомляем интерфейс менеджера треков о новом файле
            sendBroadcast(Intent(ACTION_TRACKS_UPDATED))

            // Передача на часы
            updateNotification("📡 Поиск часов", "Проверка связи с Huawei Watch по Wi-Fi...", 85, true)

            val watchIp = findWatchWifiIp()
            if (watchIp != null) {
                updateNotification("⚡ Передача", "Отправка на часы ($watchIp)...", 90, true)
                pushDirectlyToWatch(watchIp, audioFile)

                showFinishedNotification(
                    "✅ Трек на часах!",
                    "«$trackTitle» сохранен в память часов",
                    true
                )

                CoroutineScope(Dispatchers.Main).launch {
                    Toast.makeText(applicationContext, "⌚ «$trackTitle» сохранен в Huawei Music!", Toast.LENGTH_LONG).show()
                }
            } else {
                // Если Wi-Fi недоступен — создаем уведомление с действием отправки через Bluetooth
                val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", audioFile)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "audio/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(shareIntent, "Отправить на Huawei Watch")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val pi = PendingIntent.getActivity(
                    this, 102, chooser,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                showFinishedNotification(
                    "📲 Нажмите для отправки на часы",
                    "«$trackTitle» скачан. Нажмите, чтобы передать через Bluetooth",
                    false,
                    pi
                )
            }

        } catch (e: Exception) {
            showFinishedNotification(
                "❌ Ошибка загрузки",
                e.message ?: "Сбой загрузки трека",
                false
            )
        } finally {
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun pushDirectlyToWatch(ip: String, file: File) {
        val keyPair = getOrCreateAdbKeyPair()
        val dadbInstance = Dadb.create(ip, 5555, keyPair)
        try {
            val fileName = file.name
            val targetDir = "/sdcard/Music/Imported Songs"
            dadbInstance.shell("mkdir -p '$targetDir'")
            dadbInstance.push(file, "$targetDir/$fileName")
            dadbInstance.shell("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d 'file://$targetDir/$fileName'")
            dadbInstance.shell("media scan-file '$targetDir/$fileName'")
        } finally {
            dadbInstance.close()
        }
    }

    private fun getOrCreateAdbKeyPair(): dadb.AdbKeyPair {
        val privKey = File(filesDir, "adbkey")
        val pubKey = File(filesDir, "adbkey.pub")
        if (!privKey.exists() || !pubKey.exists()) {
            dadb.AdbKeyPair.generate(privKey, pubKey)
        }
        return dadb.AdbKeyPair.read(privKey, pubKey)
    }

    private suspend fun findWatchWifiIp(): String? = withContext(Dispatchers.IO) {
        val candidates = listOf(
            "192.168.1.116", "192.168.0.116",
            "192.168.1.242", "192.168.0.242",
            "192.168.1.100", "192.168.0.100"
        )
        for (ip in candidates) {
            if (testSocket(ip, 5555)) return@withContext ip
        }

        val found = AtomicReference<String?>(null)
        val subnets = listOf("192.168.1", "192.168.0")
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

    private fun downloadStreamUnthrottled(
        streamUrl: String,
        destFile: File,
        onProgress: (Int, Long, Long) -> Unit
    ) {
        val chunkSize = 1024 * 1024L
        var currentOffset = 0L
        var totalBytes = -1L

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
}
