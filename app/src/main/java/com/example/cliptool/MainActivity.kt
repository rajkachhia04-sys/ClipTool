package com.example.cliptool

import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

const val BASE_URL = "https://cliptool-mfud.onrender.com"

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

data class ClipItem(val title: String, val path: String)

@Composable
fun ClipScreen() {
    val context = LocalContext.current
    val ui = remember { Handler(Looper.getMainLooper()) }
    var youtubeUrl by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("YouTube link paste karke 'Make clips' dabaiye.") }
    var busy by remember { mutableStateOf(false) }
    var clips by remember { mutableStateOf<List<ClipItem>>(emptyList()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Clip Tool AI", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = youtubeUrl,
            onValueChange = { youtubeUrl = it },
            label = { Text("YouTube link") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                busy = true
                clips = emptyList()
                status = "AI video dekh raha hai... 2 se 5 minute lag sakte hain."
                val link = youtubeUrl.trim()
                Thread {
                    try {
                        val result = requestClips(link)
                        ui.post {
                            clips = result
                            status = if (result.isEmpty()) {
                                "Koi clip nahi mila."
                            } else {
                                "${result.size} clips ready. Neeche se download karein."
                            }
                            busy = false
                        }
                    } catch (e: Exception) {
                        ui.post {
                            status = "Error: ${e.message}"
                            busy = false
                        }
                    }
                }.start()
            },
            enabled = !busy && youtubeUrl.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Make clips")
        }

        Text(status)

        clips.forEach { clip ->
            Button(
                onClick = {
                    status = "Downloading: ${clip.title}"
                    Thread {
                        try {
                            downloadClip(context, clip.path, clip.title)
                            ui.post { status = "Saved to Movies/ClipTool: ${clip.title}" }
                        } catch (e: Exception) {
                            ui.post { status = "Download error: ${e.message}" }
                        }
                    }.start()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Download: ${clip.title}")
            }
        }
    }
}

/** Server ko YouTube link bhejta hai aur clips ki list wapas laata hai. */
fun requestClips(youtubeLink: String): List<ClipItem> {
    val conn = (URL("$BASE_URL/process").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        connectTimeout = 30_000
        readTimeout = 15 * 60_000
        setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
    }

    OutputStreamWriter(conn.outputStream).use {
        it.write("url=" + URLEncoder.encode(youtubeLink, "UTF-8"))
    }

    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val body = stream.bufferedReader().use { it.readText() }
    if (code !in 200..299) {
        throw Exception("Server $code: $body")
    }

    val arr = JSONObject(body).getJSONArray("clips")
    return (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        ClipItem(o.getString("title"), o.getString("url"))
    }
}

/** Server se ek clip download karke phone ki Movies/ClipTool folder mein save karta hai. */
fun downloadClip(context: Context, path: String, title: String) {
    val conn = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
        connectTimeout = 30_000
        readTimeout = 5 * 60_000
    }

    val safeName = title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "clip" }
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, "$safeName.mp4")
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ClipTool")
        put(MediaStore.Video.Media.IS_PENDING, 1)
    }

    val target = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        ?: throw Exception("File save nahi ho paayi")

    resolver.openOutputStream(target)?.use { out ->
        conn.inputStream.use { input -> input.copyTo(out) }
    }

    values.clear()
    values.put(MediaStore.Video.Media.IS_PENDING, 0)
    resolver.update(target, values, null, null)
}
