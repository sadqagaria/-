package com.satelliteworld.tv

import android.net.Uri
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.util.Rational
import android.os.Bundle
import android.content.pm.ActivityInfo
import android.view.WindowManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil.compose.AsyncImage
import com.satelliteworld.tv.data.ChannelEntry
import com.satelliteworld.tv.data.ChannelInfo
import com.satelliteworld.tv.data.CatalogMerger
import com.satelliteworld.tv.data.EgyptianGuides
import com.satelliteworld.tv.recording.HlsRecorder
import com.satelliteworld.tv.data.PlaylistParser
import com.satelliteworld.tv.data.PlaylistSync
import com.satelliteworld.tv.data.EpgImporter
import com.satelliteworld.tv.data.ReceiverDatabase
import com.satelliteworld.tv.worker.CatalogWorker
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val deepBlue = Color(0xFF09101F)
private val panelBlue = Color(0xFF151F34)
private val aqua = Color(0xFF34DCC8)
private val quiet = Color(0xFF9AAEC5)

class MainActivity : ComponentActivity() {
    private val database by lazy { ReceiverDatabase(applicationContext) }
    private val epg by lazy { EpgImporter(applicationContext) }
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var player: ExoPlayer

    private var channels by mutableStateOf<List<ChannelEntry>>(emptyList())
    private var favoriteIds by mutableStateOf<Set<String>>(emptySet())
    private var currentChannel by mutableStateOf<ChannelEntry?>(null)
    private var status by mutableStateOf("جاري تجهيز دليل القنوات...")
    private var loading by mutableStateOf(false)
    private var selectedTab by mutableStateOf("النايل سات")
    private var query by mutableStateOf("")
    private var channelNumber = ""
    private val numberRunnable = Runnable {
        val wanted = channelNumber.toIntOrNull()
        channelNumber = ""
        if (wanted != null) {
            val matches = filteredChannels()
            val channel = matches.firstOrNull { it.position == wanted && it.source.startsWith("import:") }
                ?: matches.getOrNull(wanted - 1)
            if (channel != null) playChannel(channel) else status = "لا توجد قناة بهذا الرقم"
        }
    }
    private var currentProgram by mutableStateOf("" to "")
    private var fullScreen by mutableStateOf(false)
    private var showRecordDialog by mutableStateOf(false)
    private var recording by mutableStateOf(false)
    private var selectedRecordQuality = 0
    private var osdTitle by mutableStateOf("")
    private var verifiedIds by mutableStateOf<Set<String>>(emptySet())
    private var candidateUrls = emptyList<String>()
    private var candidateIndex = 0
    private val recorder: HlsRecorder by lazy {
        HlsRecorder(applicationContext) { message ->
            status = message
            recording = message.contains("تسجيل جارٍ")
        }
    }

    private val recordingPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp2t")) { uri ->
        if (uri != null) {
            val url = candidateUrls.getOrNull(candidateIndex) ?: currentChannel?.streamUrl
            if (url.isNullOrBlank()) status = "لا يمكن تسجيل قناة بلا رابط بث"
            else {
                recorder.start(url, uri, selectedRecordQuality)
                recording = true
                status = "بدء تسجيل البث في الملف الذي اخترته..."
            }
        }
    }
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importPlaylist(uri)
    }
    private val epgPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importEpg(uri)
    }
    private val exportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) exportReceiverList(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(9, 16, 31)
        window.navigationBarColor = android.graphics.Color.rgb(9, 16, 31)
        player = ExoPlayer.Builder(this).build().apply {
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    if (candidateIndex + 1 < candidateUrls.size && currentChannel?.streamUrl?.isNotBlank() == true) {
                        candidateIndex++
                        status = "المصدر الأول لا يعمل؛ جاري تجربة رابط بديل ${candidateIndex + 1}/${candidateUrls.size}"
                        tryPlayUrl(candidateUrls[candidateIndex])
                    } else status = "تعذّر التشغيل: لم ينجح أي رابط متاح لهذه القناة أو البث مقيّد بمنطقتك"
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        status = "تم تشغيل البث بنجاح • ${candidateIndex + 1}/${candidateUrls.size.coerceAtLeast(1)}"
                        currentChannel?.let { verifiedIds = verifiedIds + it.id }
                    }
                    if (playbackState == Player.STATE_BUFFERING && currentChannel != null) status = "جاري الاتصال بالبث..."
                }
            })
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = aqua, onPrimary = deepBlue,
                surface = panelBlue, background = deepBlue,
                onSurface = Color.White, onBackground = Color.White
            )) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    ReceiverScreen()
                }
            }
        }
        loadDatabase()
        scheduleUpdates()
        if (database.getPreference("catalog_version") != "3") synchronize()
    }

    override fun onResume() {
        super.onResume()
        if (::player.isInitialized) loadDatabase()
    }

    private fun scheduleUpdates() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val task = PeriodicWorkRequestBuilder<CatalogWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "update_github_catalog", ExistingPeriodicWorkPolicy.KEEP, task
        )
    }

    private fun loadDatabase() {
        executor.execute {
            if (database.getPreference("seeded_egypt") != "1") {
                PlaylistSync.seedCachedEgypt(applicationContext)
                database.setPreference("seeded_egypt", "1")
            }
            val data = preparedChannels(database.all())
            val favorites = database.favorites()
            handler.post {
                channels = data
                favoriteIds = favorites
                if (data.isNotEmpty() && status.startsWith("جاري تجهيز")) status = "${data.size} قناة ومحطة محفوظة"
                if (currentChannel == null) {
                    val lastId = database.getPreference("last_channel_id")
                    data.firstOrNull { it.id == lastId && it.streamUrl.isNotBlank() }?.let { playChannel(it) }
                }
            }
        }
    }

    private fun preparedChannels(entries: List<ChannelEntry>): List<ChannelEntry> =
        CatalogMerger.sortManual(EgyptianGuides.supplement(CatalogMerger.merge(entries)),
            database.getPreference("manual_order_v3"))

    private fun toggleFullScreen() {
        fullScreen = !fullScreen
        if (fullScreen) {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun startRecording() {
        if (currentChannel?.streamUrl.isNullOrBlank()) {
            status = "هذه القناة بلا رابط بث قابل للتسجيل"
            return
        }
        if (recording || recorder.active) {
            recorder.stop()
            status = "إيقاف التسجيل وحفظ الملف..."
        } else showRecordDialog = true
    }

    private fun chooseRecordingQuality(height: Int) {
        showRecordDialog = false
        selectedRecordQuality = height
        val friendlyName = (currentChannel?.name ?: "TV").replace(Regex("[^\\p{L}\\p{N}_-]"), "_").take(40)
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
        recordingPicker.launch("${friendlyName}_${stamp}_${if (height > 0) "${height}p" else "original"}.ts")
    }

    private fun enterMiniPlayer() {
        if (currentChannel == null || !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            status = "نافذة العرض المصغّرة غير متاحة على هذا الجهاز"
            return
        }
        enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
    }

    private fun synchronize() {
        if (loading) return
        loading = true
        executor.execute {
            val result = PlaylistSync.sync(applicationContext) { message -> handler.post { status = message } }
            database.setPreference("catalog_version", "3")
            val updated = preparedChannels(database.all())
            val favorites = database.favorites()
            handler.post {
                loading = false
                channels = updated
                favoriteIds = favorites
                status = "${updated.size} قناة (بث/دليل) | ${result.messages.joinToString(" · ")}"
            }
        }
    }

    private fun importPlaylist(uri: Uri) {
        if (loading) return
        loading = true
        status = "جاري استيراد الملف..."
        executor.execute {
            try {
                val filename = displayName(uri).lowercase(Locale.ROOT)
                val source = "import:${uri}"
                val entries = contentResolver.openInputStream(uri)?.use { input ->
                    InputStreamReader(input, Charsets.UTF_8).use { reader ->
                        when {
                            filename.endsWith(".csv") -> PlaylistParser.parseCsv(reader, source)
                            filename.endsWith(".tv") || filename.endsWith(".radio") ->
                                PlaylistParser.parseEnigmaBouquet(reader, source)
                            else -> PlaylistParser.parse(reader, source)
                        }
                    }
                }.orEmpty()
                if (entries.isEmpty()) throw IllegalArgumentException("لا توجد قنوات قابلة للقراءة في الملف")
                database.replaceSource(source, entries)
                val updated = preparedChannels(database.all())
                val favorites = database.favorites()
                handler.post {
                    channels = updated
                    favoriteIds = favorites
                    loading = false
                    selectedTab = "قائمتي"
                    status = "تم استيراد ${entries.size} قناة/محطة؛ الخدمات الفضائية بلا رابط إنترنت تُعرض في الدليل فقط"
                }
            } catch (e: Exception) {
                handler.post { loading = false; status = "تعذر الاستيراد: ${e.message ?: "ملف غير مدعوم"}" }
            }
        }
    }

    private fun exportReceiverList(uri: Uri) {
        status = "جاري تصدير قائمة القنوات..."
        executor.execute {
            try {
                val entries = database.all()
                contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                    writer.appendLine("name,url,satellite,number,group,radio,tvg-id,country")
                    fun csv(s: String): String = "\"" + s.replace("\"", "\"\"").replace("\n", " ").replace("\r", " ") + "\""
                    for (entry in entries) {
                        writer.appendLine(listOf(entry.name, entry.streamUrl, entry.satellite,
                            entry.position.toString(), entry.group, entry.radio.toString(),
                            entry.epgId, entry.country).joinToString(",") { csv(it) })
                    }
                } ?: throw IllegalStateException("لا يمكن إنشاء الملف")
                handler.post { status = "تم تصدير ${entries.size} قناة ومحطة بنجاح" }
            } catch (e: Exception) {
                handler.post { status = "تعذر التصدير: ${e.message ?: "خطأ في الملف"}" }
            }
        }
    }

    private fun displayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else uri.lastPathSegment.orEmpty()
            } ?: uri.lastPathSegment.orEmpty()
        } catch (_: Exception) { uri.lastPathSegment.orEmpty() }
    }

    private fun importEpg(uri: Uri) {
        if (loading) return
        loading = true
        status = "جاري قراءة دليل البرامج XMLTV..."
        executor.execute {
            try {
                val count = contentResolver.openInputStream(uri)?.use { input ->
                    InputStreamReader(input, Charsets.UTF_8).use { epg.importXml(it) }
                } ?: 0
                handler.post {
                    loading = false
                    status = if (count > 0) "تم استيراد $count برنامج؛ يظهر الدليل عند تطابق معرّف القناة" else "لم يُقرأ أي برنامج من XMLTV"
                    currentChannel?.let { currentProgram = epg.nowAndNext(it.epgId) }
                }
            } catch (e: Exception) {
                handler.post { loading = false; status = "خطأ في استيراد دليل البرامج: ${e.message}" }
            }
        }
    }

    private fun playChannel(channel: ChannelEntry) {
        currentChannel = channel
        executor.execute {
            val programmes = epg.nowAndNext(channel.epgId)
            handler.post { if (currentChannel?.id == channel.id) currentProgram = programmes }
        }
        if (channel.streamUrl.isBlank()) {
            status = "${channel.name}: قناة فضائية في الدليل دون رابط بث إنترنت"
            player.stop()
            player.clearMediaItems()
            return
        }
        if (!channel.streamUrl.startsWith("http://", true) && !channel.streamUrl.startsWith("https://", true)) {
            status = "صيغة الرابط غير مدعومة"
            return
        }
        osdTitle = "CH ${channel.position} • ${ChannelInfo.bilingualName(channel)}"
        status = "جاري الاتصال: ${ChannelInfo.bilingualName(channel)}"
        executor.execute { database.setPreference("last_channel_id", channel.id) }
        candidateUrls = (listOf(channel.streamUrl) + channel.alternateUrls).filter { it.startsWith("http") }.distinct()
        candidateIndex = 0
        tryPlayUrl(candidateUrls.first())
    }

    private fun tryPlayUrl(url: String) {
        val metadata = MediaMetadata.Builder().setTitle(currentChannel?.name.orEmpty()).build()
        player.setMediaItem(MediaItem.Builder().setUri(url)
            .setMediaId(currentChannel?.id.orEmpty()).setMediaMetadata(metadata).build())
        player.prepare()
        player.playWhenReady = true
    }

    private fun toggleFavorite(channel: ChannelEntry) {
        database.toggleFavorite(channel.id)
        favoriteIds = database.favorites()
    }

    private fun moveChannel(channel: ChannelEntry, direction: Int, visible: List<ChannelEntry>) {
        val mutable = channels.map { it.id }.toMutableList()
        val visibleIndex = visible.indexOfFirst { it.id == channel.id }
        val neighbor = visible.getOrNull(visibleIndex + direction) ?: return
        val originalIndex = mutable.indexOf(channel.id)
        val otherIndex = mutable.indexOf(neighbor.id)
        if (originalIndex !in mutable.indices || otherIndex !in mutable.indices) return
        val swapped = mutable[otherIndex]
        mutable[otherIndex] = mutable[originalIndex]
        mutable[originalIndex] = swapped
        val indexed = channels.associateBy { it.id }
        val reordered = mutable.mapNotNull { indexed[it] }
        channels = reordered
        executor.execute {
            database.setPreference("manual_order_v3", reordered.joinToString("|") { it.id })
            handler.post { status = "تم حفظ الترتيب الجديد" }
        }
    }

    private fun switchChannel(direction: Int) {
        val list = filteredChannels().filter { it.streamUrl.isNotBlank() }
        if (list.isEmpty()) return
        val currentIndex = list.indexOfFirst { it.id == currentChannel?.id }
        val nextIndex = if (currentIndex < 0) 0 else (currentIndex + direction + list.size) % list.size
        playChannel(list[nextIndex])
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> { switchChannel(1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { switchChannel(-1); return true }
        }
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            channelNumber += (keyCode - KeyEvent.KEYCODE_0).toString()
            status = "رقم القناة: $channelNumber"
            handler.removeCallbacks(numberRunnable)
            handler.postDelayed(numberRunnable, 1300)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        if (::player.isInitialized) player.release()
        if (recording) recorder.shutdown()
        executor.shutdown()
        handler.removeCallbacksAndMessages(null)
        database.close()
        epg.close()
        super.onDestroy()
    }

    private fun filteredChannels(): List<ChannelEntry> = channels.filter { c ->
        matchesTab(c, selectedTab, favoriteIds) && ChannelInfo.matches(c, query)
    }

    @Composable
    private fun FullScreenPlayer() {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { context -> PlayerView(context).apply {
                player = this@MainActivity.player
                useController = true
                keepScreenOn = true
            } }, modifier = Modifier.fillMaxSize(), update = { it.player = player })
            Row(Modifier.align(Alignment.TopEnd).background(Color(0xB009101F)).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { switchChannel(-1) }) { Icon(Icons.Default.SkipPrevious, contentDescription="القناة السابقة", tint=Color.White) }
                IconButton(onClick = { switchChannel(1) }) { Icon(Icons.Default.SkipNext, contentDescription="القناة التالية", tint=Color.White) }
                IconButton(onClick = { startRecording() }) { Icon(if (recording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                    contentDescription="تسجيل", tint=if (recording) Color.Red else Color.White) }
                IconButton(onClick = { toggleFullScreen() }) { Icon(Icons.Default.FullscreenExit, contentDescription="إغلاق ملء الشاشة",tint=Color.White) }
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0xB009101F)).padding(16.dp)) {
                Text(currentChannel?.let { ChannelInfo.bilingualName(it) } ?: "اختر قناة",color=Color.White, fontSize=18.sp,fontWeight=FontWeight.Bold)
                Text("$status  •  CH ${currentChannel?.position ?: "--"}",color=aqua,fontSize=12.sp,maxLines=1)
            }
        }
    }

    @Composable
    private fun ReceiverScreen() {
        val wideScreen = LocalConfiguration.current.screenWidthDp >= 730
        val filtered = remember(channels, favoriteIds, query, selectedTab) { filteredChannels() }
        BackHandler(fullScreen) { toggleFullScreen() }
        if (showRecordDialog) {
            AlertDialog(
                onDismissRequest = { showRecordDialog = false },
                title = { Text("تسجيل القناة") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("اختر جودة البث المتاحة. لا يمكن اختراع جودة غير موجودة في المصدر.", fontSize = 12.sp)
                        listOf(0,144,240,360,480,720).forEach { h ->
                            TextButton(onClick = { chooseRecordingQuality(h) }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (h==0) "الجودة الأصلية" else "${h}p")
                            }
                        }
                        Text("الحفظ بصيغة TS الأصلية دون إعادة ترميز؛ تقليل الحجم يكون باختيار مصدر أقل bitrate إذا توفر. تسجيلات DRM أو fMP4 غير مدعومة.",
                            fontSize = 11.sp, color = quiet)
                    }
                },
                confirmButton = { TextButton(onClick = { showRecordDialog=false }) { Text("إلغاء") } }
            )
        }
        if (fullScreen) {
            FullScreenPlayer()
            return
        }
        Column(Modifier.fillMaxSize().background(deepBlue)) {
            Header(
                count = channels.size, selectedCount = filtered.size, loading = loading,
                status = status, onUpdate = { synchronize() },
                onImport = { filePicker.launch(arrayOf("*/*")) },
                onImportEpg = { epgPicker.launch(arrayOf("text/xml", "application/xml", "*/*")) },
                onExport = { exportPicker.launch("SatelliteWorldTV-channels.csv") },
                onPip = { enterMiniPlayer() }
            )
            if (wideScreen) {
                Row(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PlayerPanel(Modifier.weight(1.45f).fillMaxHeight())
                    ChannelPanel(filtered, Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                PlayerPanel(Modifier.fillMaxWidth().heightIn(min = 225.dp).padding(horizontal = 10.dp, vertical = 6.dp))
                ChannelPanel(filtered, Modifier.fillMaxSize().padding(horizontal = 10.dp))
            }
        }
    }

    @Composable
    private fun Header(count: Int, selectedCount: Int, loading: Boolean, status: String,
                       onUpdate: () -> Unit, onImport: () -> Unit, onImportEpg: () -> Unit,
                       onExport: () -> Unit, onPip: () -> Unit) {
        Column(Modifier.fillMaxWidth().background(panelBlue).padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Tv, contentDescription = null, tint = aqua, modifier = Modifier.size(25.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("SATELLITE WORLD TV", fontWeight = FontWeight.Black, fontSize = 16.sp, color = Color.White)
                    Text("Nilesat directory + World TV   •   $selectedCount / $count", fontSize = 11.sp, color = quiet)
                }
                IconButton(onClick = onExport, enabled = !loading) {
                    Icon(Icons.Default.SaveAlt, contentDescription = "تصدير القنوات إلى CSV")
                }
                IconButton(onClick = onPip) { Icon(Icons.Default.PictureInPictureAlt, contentDescription = "نافذة مصغرة") }
                IconButton(onClick = onImport, enabled = !loading) { Icon(Icons.Default.FileOpen, contentDescription = "استيراد ملف قنوات") }
                IconButton(onClick = onImportEpg, enabled = !loading) { Icon(Icons.Default.CalendarMonth, contentDescription = "استيراد XMLTV") }
                IconButton(onClick = onUpdate, enabled = !loading) {
                    Icon(Icons.Default.Refresh, contentDescription = "تحديث القنوات", tint = if (loading) quiet else aqua)
                }
            }
            Text(status, color = if (status.startsWith("تعذّر") || status.startsWith("تعذر")) Color(0xFFF7B993) else quiet,
                fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }

    @Composable
    private fun PlayerPanel(modifier: Modifier) {
        val channel = currentChannel
        Column(modifier.background(panelBlue, RoundedCornerShape(15.dp)).padding(8.dp)) {
            Box(
                Modifier.fillMaxWidth().then(if (LocalConfiguration.current.screenWidthDp >= 730) Modifier.weight(1f) else Modifier.aspectRatio(16f/9f))
                    .background(Color.Black, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = this@MainActivity.player
                        useController = true
                        keepScreenOn = true
                    }
                }, update = { it.player = player }, modifier = Modifier.fillMaxSize())
                if (channel == null || channel.streamUrl.isBlank() || channel.radio) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if (channel?.radio == true) Icons.Default.Radio else Icons.Default.Tv,
                            contentDescription = null, modifier = Modifier.size(45.dp), tint = aqua)
                        Spacer(Modifier.height(9.dp))
                        Text(when {
                            channel == null -> "اختر قناة من القائمة لبدء المشاهدة"
                            channel.streamUrl.isBlank() -> "القناة موجودة في دليل الأقمار فقط"
                            else -> "الإذاعة • ${channel.name}"
                        }, color = quiet, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(channel?.let { ChannelInfo.bilingualName(it) } ?: "لم يتم اختيار قناة", color = Color.White,
                        fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(channel?.satellite?.ifBlank { channel.group } ?: "اختر مجموعة وقناة",
                        color = quiet, fontSize = 11.sp, maxLines = 1)
                }
                if (channel != null) {
                    Text(if (channel.radio) "RADIO" else "LIVE", fontSize = 11.sp,
                        color = aqua, modifier = Modifier.border(1.dp, aqua, RoundedCornerShape(4.dp)).padding(4.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.SpaceBetween) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={ switchChannel(-1) }) { Icon(Icons.Default.SkipPrevious, contentDescription="السابق", tint=aqua) }
                    IconButton(onClick={ switchChannel(1) }) { Icon(Icons.Default.SkipNext, contentDescription="التالي", tint=aqua) }
                    Text("CH+ / CH−", color=quiet, fontSize=11.sp)
                }
                Row(verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={ startRecording() }) { Icon(if (recording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                        contentDescription=if (recording) "إنهاء التسجيل" else "بدء التسجيل", tint=if (recording) Color.Red else aqua) }
                    IconButton(onClick={ toggleFullScreen() }) { Icon(Icons.Default.Fullscreen, contentDescription="تكبير شاشة العرض", tint=aqua) }
                }
            }
            if (osdTitle.isNotBlank()) Text(osdTitle,color=aqua,fontSize=12.sp, maxLines=1)
            if (channel != null && (currentProgram.first.isNotBlank() || currentProgram.second.isNotBlank())) {
                Spacer(Modifier.height(6.dp))
                if (currentProgram.first.isNotBlank()) Text("الآن: ${currentProgram.first}", color = aqua, fontSize = 12.sp, maxLines = 1)
                if (currentProgram.second.isNotBlank()) Text("التالي: ${currentProgram.second}", color = quiet, fontSize = 11.sp, maxLines = 1)
            }
            if (LocalConfiguration.current.screenWidthDp >= 730) {
                Spacer(Modifier.height(10.dp))
                Text("تغيير القنوات: CH+/CH− • الأرقام • البحث • المفضلة",
                    fontSize = 11.sp, color = quiet)
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    @Composable
    private fun ChannelPanel(filtered: List<ChannelEntry>, modifier: Modifier) {
        Column(modifier.background(panelBlue, RoundedCornerShape(15.dp))) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                items(tabs.size) { index ->
                    val tab = tabs[index]
                    FilterChip(
                        selected = selectedTab == tab, onClick = { selectedTab = tab },
                        label = { Text(tab, fontSize = 12.sp) }
                    )
                }
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("ابحث عن قناة أو رقم...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
            )
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("القنوات", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("${filtered.size} نتيجة", color = quiet, fontSize = 12.sp)
            }
            Spacer(Modifier.height(5.dp))
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(25.dp), contentAlignment = Alignment.Center) {
                    Text(if (selectedTab == "النايل سات")
                        "لم تُعثر على قنوات بعد. تحديث دليل مصر يعرض أسماء القنوات مع التنويه بأن وجودها على القمر غير مؤكد، أو استورد قائمة جهاز الريسيفر."
                    else "لا توجد نتائج. جرّب مجموعة أخرى أو تحديث الدليل.",
                        color = quiet, fontSize = 13.sp)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    itemsIndexed(filtered, key = { _, channel -> channel.id }) { index, channel ->
                        ChannelRow(channel, index, channel.id == currentChannel?.id,
                            favoriteIds.contains(channel.id),
                            onPlay = { playChannel(channel) },
                            onFavorite = { toggleFavorite(channel) },
                            onUp = { moveChannel(channel, -1, filtered) },
                            onDown = { moveChannel(channel, 1, filtered) })
                    }
                }
            }
        }
    }

    @Composable
    private fun ChannelRow(channel: ChannelEntry, index: Int, isPlaying: Boolean, favorite: Boolean,
                           onPlay: () -> Unit, onFavorite: () -> Unit, onUp: () -> Unit, onDown: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 2.dp)
                .background(if (isPlaying) Color(0xFF1B3D47) else Color(0xFF19263B), RoundedCornerShape(9.dp))
                .clickable(onClick = onPlay).padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${if (channel.source.startsWith("import:")) channel.position else index + 1}",
                modifier = Modifier.width(34.dp), fontSize = 11.sp, color = aqua)
            Box(Modifier.size(36.dp).background(Color.White, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                if (channel.logo.isNotBlank()) AsyncImage(
                    model = channel.logo, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(3.dp)
                ) else Icon(Icons.Default.Tv, contentDescription = null, tint = panelBlue, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(ChannelInfo.bilingualName(channel), color = Color.White, fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (channel.satellite.ifBlank { if (ChannelInfo.inEgyptDirectory(channel)) "مصر • القمر غير مؤكد" else channel.group }) +
                        " · ${ChannelInfo.category(channel)}" +
                        if (channel.streamUrl.isBlank()) " · دليل فقط" else if (channel.id in verifiedIds) " · تم تشغيله" else " · رابط متاح (غير مختبر)",
                    color = if (channel.streamUrl.isBlank()) Color(0xFFE9B86B) else quiet,
                    fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onFavorite, modifier = Modifier.size(32.dp)) {
                Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "المفضلة", tint = if (favorite) Color(0xFFF3BD6E) else quiet,
                    modifier = Modifier.size(19.dp))
            }
            if (channel.source.startsWith("import:")) {
                Column {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "رفع القناة",
                        tint = quiet, modifier = Modifier.size(18.dp).clickable(onClick = onUp))
                    Spacer(Modifier.height(5.dp))
                    Icon(Icons.Default.ArrowDownward, contentDescription = "خفض القناة",
                        tint = quiet, modifier = Modifier.size(18.dp).clickable(onClick = onDown))
                }
            }
        }
    }
}

private val tabs = listOf("النايل سات", "رياضة", "أفلام", "مسلسلات", "كرتون", "دين وقرآن", "أخبار", "وثائقي", "أخرى", "العالم", "أوروبا", "الراديو", "المفضلة", "قائمتي", "الكل")

private fun matchesTab(channel: ChannelEntry, tab: String, favorites: Set<String>): Boolean {
    if (tab == "المفضلة") return channel.id in favorites
    if (tab == "الراديو") return channel.radio
    if (channel.radio) return false
    return when (tab) {
        "الكل", "العالم" -> true
        "قائمتي" -> channel.source.startsWith("import:")
        "النايل سات" -> ChannelInfo.nilesatVerified(channel) || ChannelInfo.inEgyptDirectory(channel)
        "رياضة", "أفلام", "مسلسلات", "كرتون", "دين وقرآن", "أخبار", "وثائقي", "أخرى" ->
            (ChannelInfo.nilesatVerified(channel) || ChannelInfo.inEgyptDirectory(channel)) && ChannelInfo.category(channel) == tab
        "أوروبا" -> ChannelInfo.isEuropean(channel)
        else -> false
    }
}
