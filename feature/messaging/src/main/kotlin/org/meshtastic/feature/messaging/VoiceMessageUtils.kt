package org.meshtastic.feature.messaging

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import timber.log.Timber
import java.util.Locale
private const val VOICE_PREFIX = "[VR]"  // Voice Recorded flag

fun encodeVoiceText(transcript: String): String =
    "$VOICE_PREFIX $transcript"

data class DecodedVoice(
    val isVoice: Boolean,
    val visibleText: String,
)

fun decodeVoiceText(raw: String): DecodedVoice =
    if (raw.startsWith(VOICE_PREFIX)) {
        DecodedVoice(
            isVoice = true,
            visibleText = raw.removePrefix(VOICE_PREFIX).trimStart(),
        )
    } else {
        DecodedVoice(isVoice = false, visibleText = raw)
    }

@Composable
fun VoiceMessageBubble(raw: String, modifier: Modifier = Modifier) {
    val decoded = decodeVoiceText(raw)
    val context = LocalContext.current

    var speaking by remember { mutableStateOf(false) }
    var isReady by remember { mutableStateOf(false) }

    // Create TTS once
    val tts = remember {
        TextToSpeech(context) { status ->
            Timber.d("TTS init callback status=$status")
            isReady = (status == TextToSpeech.SUCCESS)
            if (status != TextToSpeech.SUCCESS) {
                Timber.e("TTS failed to initialize, status=$status")
            }
        }
    }

    // Set language when ready
    LaunchedEffect(isReady) {
        if (isReady) {
            val result = tts.setLanguage(Locale.getDefault())
            Timber.d("TTS language set, result=$result")
        }
    }

    // Listen for playback start/finish
    LaunchedEffect(tts) {
        tts.setOnUtteranceProgressListener(
            object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    // Called from background thread – bounce to main
                    android.os.Handler(android.os.Looper.getMainLooper())
                        .post { speaking = true }
                }

                override fun onDone(utteranceId: String?) {
                    android.os.Handler(android.os.Looper.getMainLooper())
                        .post { speaking = false }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    android.os.Handler(android.os.Looper.getMainLooper())
                        .post { speaking = false }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    android.os.Handler(android.os.Looper.getMainLooper())
                        .post { speaking = false }
                }
            },
        )
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            tts.stop()
            tts.shutdown()
        }
    }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier
                .clickable {
                    Timber.d("Voice bubble clicked, ready=$isReady speaking=$speaking")

                    if (!isReady) {
                        Timber.e("TTS not ready yet, ignoring click")
                        return@clickable
                    }

                    if (speaking) {
                        tts.stop()
                        // onDone/onError will flip speaking = false
                    } else {
                        val res = tts.speak(
                            decoded.visibleText,
                            TextToSpeech.QUEUE_FLUSH,
                            null,
                            "tts-${decoded.visibleText.hashCode()}",
                        )
                        Timber.d("speak result=$res")
                        if (res != TextToSpeech.SUCCESS) {
                            speaking = false
                        }
                    }
                }
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (speaking) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = if (speaking) "Stop" else "Play",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = decoded.visibleText)
        }
    }
}
