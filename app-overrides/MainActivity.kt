package org.nooraldarb.app

import android.content.ComponentName
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
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
            var message by remember { mutableStateOf("يمكن تحديث فهرس الحلقات من أرشيف الإنترنت") }
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
                    val activeController = controller
                    if (activeController == null) {
                        Toast.makeText(this@MainActivity, "جارٍ الاتصال بمشغل الصوت، حاول بعد لحظة", Toast.LENGTH_LONG).show()
                    } else {
                        val playlist = episodes.map { item ->
                            MediaItem.Builder().setMediaId(item.id).setUri(item.audioUrl)
                                .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(item.title).build()).build()
                        }
                        val startIndex = episodes.indexOfFirst { it.id == episode.id }.coerceAtLeast(0)
                        activeController.setMediaItems(playlist, startIndex, 0L)
                        activeController.prepare()
                        activeController.play()
                    }
                })
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

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null; playerState.value = null
        super.onDestroy()
    }
}

@Composable
private fun NoorHome(episodes: List<Episode>, loading: Boolean, message: String, player: MediaController?, onRefresh: () -> Unit, onPlay: (Episode) -> Unit) {
    var selected by remember(episodes) { mutableStateOf(episodes.firstOrNull()) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var slider by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var showIndex by remember { mutableStateOf(false) }
    var showFavorites by remember { mutableStateOf(false) }
    val favorites = remember { mutableStateListOf<String>() }

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
                Image(painter = painterResource(id = R.drawable.app_background), contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Column(Modifier.align(Alignment.TopCenter).offset(y = maxHeight * 0.435f)
                    .fillMaxWidth(0.90f).background(Color(0xEE063A2B), RoundedCornerShape(14.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(selected?.title ?: "اختر حلقة من الفهرس", color = SoftGold, fontSize = 18.sp,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
                    if (loading) Text("جارٍ تحديث الفهرس...", color = Color.White, fontSize = 10.sp)
                }
                Column(Modifier.align(Alignment.TopCenter).offset(y = maxHeight * 0.545f)
                    .fillMaxWidth(0.94f).background(Color(0xEE063A2B), RoundedCornerShape(18.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Slider(value = slider.coerceIn(0f, duration.coerceAtLeast(1L).toFloat()),
                        onValueChange = { dragging = true; slider = it },
                        onValueChangeFinished = { player?.seekTo(slider.toLong()); dragging = false },
                        valueRange = 0f..duration.coerceAtLeast(1L).toFloat(), enabled = player != null && duration > 0,
                        colors = SliderDefaults.colors(thumbColor = SoftGold, activeTrackColor = Gold, inactiveTrackColor = Color.LightGray))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(timeText(position), color = Color.White, fontSize = 12.sp)
                        Text(timeText(duration), color = Color.White, fontSize = 12.sp)
                    }
                }
                Row(Modifier.align(Alignment.TopCenter).offset(y = maxHeight * 0.665f).fillMaxWidth(0.96f),
                    horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    RoundControl("⏪", "15 ثانية\nرجوع", { player?.seekTo((player.currentPosition - 15000L).coerceAtLeast(0L)) }, player != null)
                    RoundControl("|◀", "السابق", { player?.seekToPreviousMediaItem() }, player?.hasPreviousMediaItem() == true)
                    RoundControl(if (playing) "Ⅱ" else "▶", if (playing) "إيقاف" else "تشغيل",
                        { player?.let { if (it.isPlaying) it.pause() else it.play() } }, player != null, true)
                    RoundControl("▶|", "التالي", { player?.seekToNextMediaItem() }, player?.hasNextMediaItem() == true)
                    RoundControl("⏩", "15 ثانية\nتقديم", { player?.seekTo((player.currentPosition + 15000L).coerceAtMost(duration.takeIf { it > 0 } ?: Long.MAX_VALUE)) }, player != null)
                }
                Row(Modifier.align(Alignment.TopCenter).offset(y = maxHeight * 0.815f).fillMaxWidth(0.94f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActionButton("المفضلة", "★", Modifier.weight(1f)) { showFavorites = true }
                    ActionButton("تحديث", "↓", Modifier.weight(1f), enabled = !loading, onClick = onRefresh)
                    ActionButton("الفهرس", "☷", Modifier.weight(1f)) { showIndex = true }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 5.dp, start = 22.dp, end = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("اللهم اجعل هذا العمل صدقة جارية لي ولوالدي ولأهل بيتي", color = SoftGold,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
                    Text("الفقير إلى الله / محمد فريج البلوي", color = Color(0xFFD8C58C), fontSize = 8.sp, textAlign = TextAlign.Center)
                }
                if (message.isNotBlank()) Text(message, color = Color.White, fontSize = 10.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp)
                        .background(Color(0xEE063A2B), RoundedCornerShape(8.dp)).padding(5.dp))
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
private fun RoundControl(symbol: String, label: String, onClick: () -> Unit, enabled: Boolean, large: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(if (large) 68.dp else 54.dp)) {
        Surface(onClick = onClick, enabled = enabled, shape = CircleShape,
            color = if (large) Color(0xFF0A573C) else Color(0xFF075039), contentColor = SoftGold,
            border = androidx.compose.foundation.BorderStroke(if (large) 3.dp else 2.dp, Gold),
            shadowElevation = 5.dp, modifier = Modifier.size(if (large) 66.dp else 48.dp)) {
            Box(contentAlignment = Alignment.Center) { Text(symbol, fontSize = if (large) 29.sp else 19.sp, fontWeight = FontWeight.Bold) }
        }
        Text(label, color = Color.White, fontSize = 9.sp, textAlign = TextAlign.Center, lineHeight = 11.sp, maxLines = 2)
    }
}

@Composable
private fun ActionButton(label: String, symbol: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.5.dp, Gold),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = SoftGold, containerColor = Color(0xEE063A2B)),
        contentPadding = PaddingValues(horizontal = 4.dp)) {
        Text(symbol, color = Gold, fontSize = 18.sp); Spacer(Modifier.width(4.dp)); Text(label, fontSize = 11.sp, maxLines = 1)
    }
}

private fun timeText(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}