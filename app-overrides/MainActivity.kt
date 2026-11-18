package org.nooraldarb.app

import android.content.ComponentName
import android.app.DownloadManager
import android.net.Uri
import java.io.File
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.util.concurrent.Executor

private val DeepGreen = Color(0xFF0B3028)
private val Gold = Color(0xFFD8B96E)
private val SoftGold = Color(0xFFF0DDAA)

private fun safeEpisodeFileName(id: String): String = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

class MainActivity : ComponentActivity() {
    private var controller: MediaController? = null
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private val playerState = mutableStateOf<MediaController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({
            try { controller = controllerFuture?.get(); playerState.value = controller }
            catch (error: Exception) {
                playerState.value = null
                runOnUiThread {
                    Toast.makeText(this, "تعذر الاتصال بمشغل الصوت: ${error.localizedMessage ?: "خطأ غير معروف"}", Toast.LENGTH_LONG).show()
                }
            }
        }, Executor { command -> runOnUiThread(command) })

        val initialEpisodes = ArchiveCatalog.loadCached(this).ifEmpty { loadEpisodes() }
        setContent {
            var episodes by remember { mutableStateOf(initialEpisodes) }
            var loading by remember { mutableStateOf(false) }
            var message by remember { mutableStateOf<String?>(null) }
            NoorHome(episodes, loading, message, playerState.value,
                onRefresh = {
                    if (!loading) {
                        loading = true
                        message = "جارٍ جلب قائمة الحلقات من الأرشيف..."
                        lifecycleScope.launch {
                            try {
                                episodes = ArchiveCatalog.refresh(this@MainActivity)
                                message = "تم تحديث الفهرس: ${episodes.size} حلقة"
                            } catch (e: Exception) {
                                message = "تعذر التحديث: ${e.localizedMessage ?: "تحقق من اتصال الإنترنت"}"
                            } finally { loading = false }
                        }
                    }
                },
                onPlay = { episode ->
                    getSharedPreferences("noor_playback_resume", MODE_PRIVATE).edit()
                        .putString("episode_id", episode.id).putLong("position_ms", 0L).apply()
                    val startIndex = episodes.indexOfFirst { it.id == episode.id }.coerceAtLeast(0)
                    // Download this episode and the next four for offline listening.
                    downloadSmartFive(episodes, startIndex)
                    val activeController = controller
                    if (activeController == null) {
                        Toast.makeText(this@MainActivity, "جارٍ الاتصال بمشغل الصوت، حاول بعد لحظة", Toast.LENGTH_LONG).show()
                    } else {
                        val playlist = episodes.map { item ->
                            val offlineFile = offlineEpisodeFile(item.id)
                            val playbackUri = if (offlineFile.isFile && offlineFile.length() > 0L) Uri.fromFile(offlineFile) else Uri.parse(item.audioUrl)
                            MediaItem.Builder().setMediaId(item.id).setUri(playbackUri)
                                .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(item.title).build()).build()
                        }
                        activeController.setMediaItems(playlist, startIndex, 0L)
                        activeController.prepare()
                        activeController.play()
                    }
                }, onStatusExpired = { message = null })
        }
    }

    private fun offlineEpisodeFile(id: String): File =
        File(getExternalFilesDir(null), "offline_episodes/" + safeEpisodeFileName(id) + ".audio")

    private fun downloadSmartFive(episodes: List<Episode>, startIndex: Int) {
        val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val directory = File(getExternalFilesDir(null), "offline_episodes")
        if (!directory.exists()) directory.mkdirs()
        val prefs = getSharedPreferences("noor_smart_downloads", MODE_PRIVATE)
        episodes.drop(startIndex).take(5).forEach { episode ->
            val target = offlineEpisodeFile(episode.id)
            if (target.isFile && target.length() > 0L) return@forEach
            val key = "download_" + safeEpisodeFileName(episode.id)
            val existingId = prefs.getLong(key, -1L)
            if (existingId >= 0L) {
                val query = manager.query(DownloadManager.Query().setFilterById(existingId))
                val stillActive = query.use { it.moveToFirst() &&
                    it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) in
                    listOf(DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED) }
                if (stillActive) return@forEach
            }
            try {
                val request = DownloadManager.Request(Uri.parse(episode.audioUrl))
                    .setTitle("نور على الدرب")
                    .setDescription("تحميل الحلقة " + episode.id + " للاستماع دون إنترنت")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(false)
                    .setDestinationInExternalFilesDir(this, null, "offline_episodes/" + target.name)
                val id = manager.enqueue(request)
                prefs.edit().putLong(key, id).apply()
            } catch (_: Exception) {
                // Streaming remains available if the offline download cannot be queued.
            }
        }
    }
    private fun loadEpisodes(): List<Episode> = try {
        val json = assets.open("episodes.json").bufferedReader().use { it.readText() }
        val array = org.json.JSONObject(json).getJSONArray("episodes")
        (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            Episode(item.getString("id"), item.getString("title"), item.getString("audioUrl"), item.getInt("order"))
        }
    } catch (_: Exception) { emptyList() }

    override fun onStop() {
        // Save immediately when the app leaves the foreground, in addition to periodic saving.
        controller?.currentMediaItem?.mediaId?.let { id ->
            getSharedPreferences("noor_playback_resume", MODE_PRIVATE).edit()
                .putString("episode_id", id)
                .putLong("position_ms", controller?.currentPosition?.coerceAtLeast(0L) ?: 0L)
                .apply()
        }
        super.onStop()
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null; playerState.value = null
        super.onDestroy()
    }
}

@Composable
private fun NoorHome(episodes: List<Episode>, loading: Boolean, message: String?, player: MediaController?, onRefresh: () -> Unit, onPlay: (Episode) -> Unit, onStatusExpired: () -> Unit) {
    var selected by remember(episodes) { mutableStateOf(episodes.firstOrNull()) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var slider by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var showIndex by remember { mutableStateOf(false) }
    var showFavorites by remember { mutableStateOf(false) }
    val favorites = remember { mutableStateListOf<String>() }
    val backgroundImage = ImageBitmap.imageResource(R.drawable.app_background)
    val context = LocalContext.current
    var resumeRestored by remember { mutableStateOf(false) }

    // Restore the last episode and exact playback position after reopening the app.
    LaunchedEffect(player, episodes) {
        if (player != null && episodes.isNotEmpty() && !resumeRestored) {
            val prefs = context.getSharedPreferences("noor_playback_resume", android.content.Context.MODE_PRIVATE)
            val savedId = prefs.getString("episode_id", null)
            val savedIndex = episodes.indexOfFirst { it.id == savedId }
            val index = if (savedIndex >= 0) savedIndex else 0
            val savedPosition = if (savedIndex >= 0) prefs.getLong("position_ms", 0L).coerceAtLeast(0L) else 0L
            val playlist = episodes.map { item ->
                val offlineFile = File(context.getExternalFilesDir(null), "offline_episodes/" + safeEpisodeFileName(item.id) + ".audio")
                val playbackUri = if (offlineFile.isFile && offlineFile.length() > 0L) Uri.fromFile(offlineFile) else Uri.parse(item.audioUrl)
                MediaItem.Builder().setMediaId(item.id).setUri(playbackUri)
                    .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(item.title).build()).build()
            }
            player.setMediaItems(playlist, index, savedPosition)
            player.prepare()
            player.play()
            selected = episodes[index]
            resumeRestored = true
        }
    }

    // Persist the current item and position continuously, not just when the screen closes.
    LaunchedEffect(player) {
        while (true) {
            val current = player
            val id = current?.currentMediaItem?.mediaId
            if (current != null && id != null) {
                context.getSharedPreferences("noor_playback_resume", android.content.Context.MODE_PRIVATE)
                    .edit().putString("episode_id", id)
                    .putLong("position_ms", current.currentPosition.coerceAtLeast(0L)).apply()
            }
            delay(500)
        }
    }

    LaunchedEffect(message) { if (message != null) { delay(2600); onStatusExpired() } }

    LaunchedEffect(Unit) { if (episodes.size <= 2) onRefresh() }
    LaunchedEffect(player) {
        while (true) {
            if (player != null) {
                position = player.currentPosition.coerceAtLeast(0L)
                duration = player.duration.takeIf { it > 0 } ?: 0L
                playing = player.isPlaying
                if (!dragging) slider = position.toFloat()
                player.currentMediaItem?.mediaId?.let { id -> selected = episodes.firstOrNull { it.id == id } ?: selected }
            }
            delay(500)
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Gold, background = DeepGreen, surface = Color(0xFF174A3D))) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            BoxWithConstraints(Modifier.fillMaxSize().background(DeepGreen)) {
                // Landscape layout follows the safe inner width of the approved gold frame.
                // Keep portrait sizing unchanged; only tighten the player panels in landscape.
                val isLandscape = maxWidth > maxHeight
                val isPhone = LocalConfiguration.current.smallestScreenWidthDp < 600
                val isPhoneLandscape = isPhone && isLandscape
                val playerPanelWidth = when { isPhoneLandscape -> 0.94f; isLandscape -> 0.82f; else -> 0.88f }
                val controlsRowWidth = when { isPhoneLandscape -> 0.98f; isLandscape -> 0.86f; else -> 0.90f }
                Canvas(Modifier.fillMaxSize()) {
                    // Draw the approved artwork as one intact image: no arbitrary source crops
                    // and no independent horizontal/vertical stretching of the title or frame.
                    drawRect(Color(0xFFFFF2D2))
                    val scale = minOf(size.width / backgroundImage.width, size.height / backgroundImage.height)
                    val dstW = (backgroundImage.width * scale).toInt().coerceAtLeast(1)
                    val dstH = (backgroundImage.height * scale).toInt().coerceAtLeast(1)
                    val left = ((size.width - dstW) / 2f).toInt()
                    val top = ((size.height - dstH) / 2f).toInt()
                    drawImage(backgroundImage, dstOffset = IntOffset(left, top), dstSize = IntSize(dstW, dstH))
                }
                // Restore the original framed, layered player layout on landscape screens.
                Column(Modifier.align(Alignment.TopCenter)
                    .offset(y = maxHeight * (if (isLandscape) 0.405f else 0.435f))
                    .fillMaxWidth(if (isPhoneLandscape) 0.84f else playerPanelWidth)
                    .background(Color(0xEE063A2B), RoundedCornerShape(14.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(selected?.title ?: "اختر حلقة من الفهرس", color = SoftGold,
                        fontSize = if (isPhoneLandscape) 14.sp else 18.sp,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1)
                    if (loading) Text("جارٍ تحديث الفهرس...", color = Color.White, fontSize = 10.sp)
                }
                Column(Modifier.align(Alignment.TopCenter)
                    .offset(y = maxHeight * (if (isLandscape) 0.505f else 0.545f))
                    .fillMaxWidth(if (isPhoneLandscape) 0.76f else playerPanelWidth)
                    .background(Color(0xEE063A2B), RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 1.dp)) {
                    Slider(
                        modifier = Modifier.fillMaxWidth().height(if (isPhoneLandscape) 30.dp else 42.dp),
                        value = slider.coerceIn(0f, duration.coerceAtLeast(1L).toFloat()),
                        onValueChange = { dragging = true; slider = it },
                        onValueChangeFinished = { player?.seekTo(slider.toLong()); dragging = false },
                        valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                        enabled = player != null && duration > 0,
                        colors = SliderDefaults.colors(thumbColor = SoftGold, activeTrackColor = Gold, inactiveTrackColor = Color.LightGray)
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(timeText(position), color = Color.White, fontSize = if (isPhoneLandscape) 10.sp else 12.sp)
                        Text(timeText(duration), color = Color.White, fontSize = if (isPhoneLandscape) 10.sp else 12.sp)
                    }
                }
                Row(Modifier.align(Alignment.TopCenter)
                    .offset(y = maxHeight * (if (isLandscape) 0.565f else 0.665f))
                    .fillMaxWidth(if (isPhoneLandscape) 0.88f else controlsRowWidth),
                    horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    RoundControl("⏪", "15 ثانية\nرجوع", { player?.seekTo((player.currentPosition - 15000L).coerceAtLeast(0L)) }, player != null, compact = isLandscape)
                    RoundControl("|◀", "السابق", { player?.seekToPreviousMediaItem() }, player?.hasPreviousMediaItem() == true, compact = isLandscape)
                    RoundControl(if (playing) "Ⅱ" else "▶", if (playing) "إيقاف" else "تشغيل",
                        { player?.let { if (it.isPlaying) it.pause() else it.play() } }, player != null, true, compact = isLandscape)
                    RoundControl("▶|", "التالي", { player?.seekToNextMediaItem() }, player?.hasNextMediaItem() == true, compact = isLandscape)
                    RoundControl("⏩", "15 ثانية\nتقديم", { player?.seekTo((player.currentPosition + 15000L).coerceAtMost(duration.takeIf { it > 0 } ?: Long.MAX_VALUE)) }, player != null, compact = isLandscape)
                }
                Row(Modifier.align(Alignment.TopCenter)
                    .offset(y = maxHeight * (if (isLandscape) 0.815f else 0.785f))
                    .fillMaxWidth(if (isPhoneLandscape) 0.90f else 0.90f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActionButton("المفضلة", "★", Modifier.weight(1f), compact = isLandscape) { showFavorites = true }
                    ActionButton("تحديث", "↓", Modifier.weight(1f), enabled = !loading, compact = isLandscape, onClick = onRefresh)
                    ActionButton("الفهرس", "☷", Modifier.weight(1f), compact = isLandscape) { showIndex = true }
                }
                if (message != null) Text(message, color = SoftGold, fontSize = 10.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp)
                        .background(DeepGreen, RoundedCornerShape(8.dp)).padding(horizontal = 9.dp, vertical = 6.dp))
                if (showIndex || showFavorites) {
                    val shown = if (showFavorites) episodes.filter { favorites.contains(it.id) } else episodes
                    Dialog(onDismissRequest = { showIndex = false; showFavorites = false }) {
                        Surface(shape = RoundedCornerShape(20.dp), color = DeepGreen, modifier = Modifier.fillMaxWidth().fillMaxHeight(0.78f)) {
                            Column(Modifier.padding(14.dp)) {
                                Text(if (showFavorites) "الحلقات المفضلة" else "فهرس الحلقات (${episodes.size})",
                                    color = SoftGold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = onRefresh, enabled = !loading) { Text(if (loading) "جارٍ التحديث" else "تحديث القائمة", color = Gold) }
                                    TextButton(onClick = { showFavorites = false; showIndex = false }) { Text("إغلاق", color = Gold) }
                                }
                                LazyColumn(Modifier.weight(1f)) {
                                    items(shown, key = { it.id }) { ep ->
                                        Row(Modifier.fillMaxWidth().clickable { selected = ep; onPlay(ep); showIndex = false; showFavorites = false }
                                            .padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text("${ep.order}. ${ep.title}", color = Color.White, modifier = Modifier.weight(1f), fontSize = 14.sp)
                                            TextButton(onClick = { if (favorites.contains(ep.id)) favorites.remove(ep.id) else favorites.add(ep.id) }) {
                                                Text(if (favorites.contains(ep.id)) "★" else "☆", color = Gold, fontSize = 20.sp)
                                            }
                                        }
                                        HorizontalDivider(color = Color(0x557D9D82))
                                    }
                                }
                                if (shown.isEmpty()) Text(if (showFavorites) "لم تضف حلقات للمفضلة بعد" else "لا توجد حلقات", color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundControl(symbol: String, label: String, onClick: () -> Unit, enabled: Boolean, large: Boolean = false, compact: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(when { compact && large -> 56.dp; compact -> 48.dp; large -> 74.dp; else -> 64.dp })) {
        Surface(onClick = onClick, enabled = enabled, shape = CircleShape,
            color = if (large) Color(0xFF0A573C) else Color(0xFF075039), contentColor = SoftGold,
            border = androidx.compose.foundation.BorderStroke(if (large) 3.dp else 2.dp, Gold),
            shadowElevation = 5.dp, modifier = Modifier.size(when { compact && large -> 56.dp; compact -> 44.dp; large -> 78.dp; else -> 60.dp })) {
            Box(contentAlignment = Alignment.Center) { Text(symbol, fontSize = when { compact && large -> 28.sp; compact -> 19.sp; large -> 36.sp; else -> 25.sp }, fontWeight = FontWeight.Bold) }
        }
        Text(label, color = DeepGreen, fontSize = if (compact) 8.sp else 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            lineHeight = if (compact) 9.sp else 11.sp, maxLines = 2, modifier = Modifier.padding(top = 2.dp)
                .background(Color(0xDDF5E8C6), RoundedCornerShape(5.dp)).padding(horizontal = 3.dp, vertical = 2.dp))
    }
}

@Composable
private fun ActionButton(label: String, symbol: String, modifier: Modifier = Modifier, enabled: Boolean = true, compact: Boolean = false, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.height(if (compact) 38.dp else 54.dp),
        shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.5.dp, Gold),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = SoftGold, containerColor = Color(0xEE063A2B)),
        contentPadding = PaddingValues(horizontal = 4.dp)) {
        Text(symbol, color = Gold, fontSize = if (compact) 17.sp else 23.sp); Spacer(Modifier.width(if (compact) 3.dp else 5.dp)); Text(label, fontSize = if (compact) 10.sp else 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

private fun timeText(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}