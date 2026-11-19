package com.example.cliptool

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ClipScreen()
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun ClipScreen() {
    val context = LocalContext.current

    var durationMs by remember { mutableLongStateOf(0L) }
    var rangeStart by remember { mutableFloatStateOf(0f) }
    var rangeEnd by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("Pick a video to start.") }
    var busy by remember { mutableStateOf(false) }

    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && player.duration > 0) {
                    durationMs = player.duration
                    rangeStart = 0f
                    rangeEnd = player.duration.toFloat()
                    status = "Drag the handles to choose your clip."
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { picked ->
        if (picked != null) {
            durationMs = 0L
            status = "Loading…"
            player.setMediaItem(MediaItem.fromUri(picked))
            player.prepare()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Clip Tool", style = MaterialTheme.typography.headlineSmall)

        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
        )

        Button(onClick = { picker.launch("video/*") }, modifier = Modifier.fillMaxWidth()) {
            Text("Choose video")
        }

        if (durationMs > 0) {
            Text("Start ${fmt(rangeStart)}   End ${fmt(rangeEnd)}   Length ${fmt(rangeEnd - rangeStart)}")

            RangeSlider(
                value = rangeStart..rangeEnd,
                valueRange = 0f..durationMs.toFloat(),
                onValueChange = { range ->
                    rangeStart = range.start
                    rangeEnd = range.endInclusive
                },
                onValueChangeFinished = {
                    player.seekTo(rangeStart.toLong())
                }
            )

            Button(
                onClick = {
                    busy = true
                    status = "Exporting…"
                    exportClip(
                        context = context,
                        uri = player.currentMediaItem?.localConfiguration?.uri,
                        startMs = rangeStart.toLong(),
                        endMs = rangeEnd.toLong(),
                        onDone = { message ->
                            status = message
                            busy = false
                        }
                    )
                },
                enabled = !busy && rangeEnd > rangeStart,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Export clip")
            }
        }

        Text(status)
    }
}

/** Trims the source video with Media3 Transformer, then saves the result to Movies/ClipTool. */
@OptIn(UnstableApi::class)
fun exportClip(
    context: Context,
    uri: Uri?,
    startMs: Long,
    endMs: Long,
    onDone: (String) -> Unit
) {
    if (uri == null) {
        onDone("No video loaded.")
        return
    }

    val tempFile = File(context.cacheDir, "clip_${System.currentTimeMillis()}.mp4")

    val transformer = Transformer.Builder(context)
        .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, result: ExportResult) {
                saveToGallery(context, tempFile)
                onDone("Saved to Movies/ClipTool.")
            }

            override fun onError(
                composition: Composition,
                result: ExportResult,
                exception: ExportException
            ) {
                tempFile.delete()
                onDone("Export failed: ${exception.message}")
            }
        })
        .build()

    val clipped = MediaItem.Builder()
        .setUri(uri)
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(startMs)
                .setEndPositionMs(endMs)
                .build()
        )
        .build()

    transformer.start(clipped, tempFile.absolutePath)
}

/** Copies the finished clip into the public Movies folder so it appears in the Gallery. */
fun saveToGallery(context: Context, file: File) {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, "clip_${System.currentTimeMillis()}.mp4")
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ClipTool")
        put(MediaStore.Video.Media.IS_PENDING, 1)
    }

    val target = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return
    resolver.openOutputStream(target)?.use { out ->
        file.inputStream().use { input -> input.copyTo(out) }
    }

    values.clear()
    values.put(MediaStore.Video.Media.IS_PENDING, 0)
    resolver.update(target, values, null, null)
    file.delete()
}

/** Formats milliseconds as m:ss.s for display. */
fun fmt(ms: Float): String {
    val totalSeconds = ms / 1000f
    val minutes = (totalSeconds / 60).toInt()
    val seconds = totalSeconds % 60
    return "%d:%04.1f".format(minutes, seconds)
}
