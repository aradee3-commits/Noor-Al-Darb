package org.nooraldarb.app

import android.content.ComponentName
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
private fun NoorHome(
    episodes: List<Episode>, loading: Boolean, message: String, player: MediaController?,
    onRefresh: () -> Unit, onPlay: (Episode) -> Unit
) {
    var selected by remember(episodes) { mutableStateOf(episodes.firstOrNull()) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var slider by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // The bundled JSON is only a tiny offline fallback. Fetch the full approved catalog.
        if (episodes.size <= 2) onRefresh()
    }

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
            Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF092820), DeepGreen, Color(0xFF1A493B))))
                .padding(horizontal = 16.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(8.dp))
                Text("۞", color = Gold, fontSize = 26.sp)
                Text("نور على الدرب", color = SoftGold, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("فضيلة الشيخ صالح بن محمد اللحيدان رحمه الله", color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.height(14.dp))

                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xE61B5142)),
                    shape = RoundedCornerShape(22.dp), elevation = CardDefaults.cardElevation(5.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("المشغل الصوتي", color = Gold, fontSize = 14.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(selected?.title ?: "اختر حلقة من الفهرس", color = Color.White, fontSize = 17.sp,
                            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
                        Slider(value = slider.coerceIn(0f, duration.coerceAtLeast(1L).toFloat()),
                            onValueChange = { dragging = true; slider = it },
                            onValueChangeFinished = { player?.seekTo(slider.toLong()); dragging = false },
                            valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                            enabled = player != null && duration > 0,
                            colors = SliderDefaults.colors(thumbColor = SoftGold, activeTrackColor = Gold, inactiveTrackColor = Color(0xFF668477)))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(timeText(duration), color = SoftGold, fontSize = 11.sp)
                            Text(timeText(position), color = SoftGold, fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(5.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { player?.seekToPreviousMediaItem() }, enabled = player?.hasPreviousMediaItem() == true,
                                contentPadding = PaddingValues(horizontal = 8.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = SoftGold)) { Text("السابق", fontSize = 11.sp) }
                            FilledTonalButton(onClick = { player?.seekTo((player.currentPosition - 15000L).coerceAtLeast(0L)) }, enabled = player != null,
                                contentPadding = PaddingValues(horizontal = 8.dp)) { Text("−15 ث", fontSize = 11.sp) }
                            Button(onClick = { player?.let { if (it.isPlaying) it.pause() else it.play() } }, enabled = player != null,
                                contentPadding = PaddingValues(horizontal = 12.dp), colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = DeepGreen)) {
                                Text(if (playing) "إيقاف" else "تشغيل", fontWeight = FontWeight.Bold)
                            }
                            FilledTonalButton(onClick = { player?.seekTo((player.currentPosition + 15000L).coerceAtMost(duration.takeIf { it > 0 } ?: Long.MAX_VALUE)) }, enabled = player != null,
                                contentPadding = PaddingValues(horizontal = 8.dp)) { Text("+15 ث", fontSize = 11.sp) }
                            OutlinedButton(onClick = { player?.seekToNextMediaItem() }, enabled = player?.hasNextMediaItem() == true,
                                contentPadding = PaddingValues(horizontal = 8.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = SoftGold)) { Text("التالي", fontSize = 11.sp) }
                        }
                        Text("اسحب الشريط للتقديم أو الرجوع", color = Color.LightGray, fontSize = 11.sp)
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("فهرس الحلقات (${episodes.size})", color = SoftGold, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Button(onClick = onRefresh, enabled = !loading,
                        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = DeepGreen)) {
                        Text(if (loading) "جارٍ التحديث" else "تحديث الفهرس")
                    }
                }
                Text(message, color = Color.LightGray, fontSize = 11.sp, modifier = Modifier.fillMaxWidth())
                if (episodes.isEmpty()) Text("لم يتم تحميل الفهرس بعد؛ اضغط تحديث الفهرس", color = Color.White, modifier = Modifier.padding(10.dp))
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(episodes, key = { it.id }) { ep ->
                        TextButton(onClick = { selected = ep; onPlay(ep) }, modifier = Modifier.fillMaxWidth()) {
                            Text("${ep.order}.  ${ep.title}", color = if (selected?.id == ep.id) SoftGold else Color.White,
                                modifier = Modifier.fillMaxWidth(), fontSize = 14.sp, maxLines = 2)
                        }
                        HorizontalDivider(color = Color(0x446FA18A), thickness = 0.5.dp)
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text("اللهم اجعل هذا العمل صدقة جارية لي ولوالدي ولأهل بيتي", color = SoftGold,
                    fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(2.dp))
                Text("الفقير إلى الله / محمد فريج البلوي", color = Color(0xFFB9A777),
                    fontSize = 8.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

private fun timeText(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}