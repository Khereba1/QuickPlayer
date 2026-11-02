package com.myplayer.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player

@Composable
fun NowPlayingScreen(vm: PlayerViewModel) {
    val item = vm.currentItem ?: return
    val entry = vm.entryFor(item.mediaId)
    var showQueue by remember { mutableStateOf(false) }
    var showEq by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primaryContainer
    val bg = MaterialTheme.colorScheme.background

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(primary.copy(alpha = 0.55f), bg, bg)))
            .clickable(enabled = false) {},
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.closePlayer() }) {
                    Ico(R.drawable.ic_back, stringResource(R.string.back))
                }
                Text(
                    stringResource(R.string.now_playing),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(onClick = { showQueue = true }) {
                    Ico(R.drawable.ic_playlist, stringResource(R.string.queue_title))
                }
            }

            BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                val side = minOf(maxWidth, 340.dp)
                MediaThumb(entry = entry, isVideo = false, size = side, corner = 32.dp, px = 640)
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.mediaMetadata.title?.toString() ?: "",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val sub = item.mediaMetadata.artist?.toString() ?: ""
                    if (sub.isNotBlank()) {
                        Text(
                            sub,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (entry != null) {
                    val fav = vm.favorites.contains(entry.key)
                    IconButton(onClick = { vm.toggleFavorite(entry.key) }) {
                        Ico(
                            id = if (fav) R.drawable.ic_favorite else R.drawable.ic_favorite_border,
                            desc = stringResource(R.string.favorite),
                            tint = if (fav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            SeekBar(vm.positionMs, vm.durationMs, onSeek = { vm.seekTo(it) }, modifier = Modifier.fillMaxWidth())
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(vm.positionMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatTime(vm.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.toggleShuffle() }) {
                    Ico(
                        R.drawable.ic_shuffle,
                        stringResource(R.string.shuffle),
                        tint = if (vm.shuffleOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { vm.previous() }, modifier = Modifier.size(56.dp)) {
                    Ico(R.drawable.ic_prev, stringResource(R.string.previous), Modifier.size(36.dp))
                }
                BigPlayButton(playing = vm.isPlaying, size = 76.dp, onClick = { vm.togglePlay() })
                IconButton(onClick = { vm.next() }, modifier = Modifier.size(56.dp)) {
                    Ico(R.drawable.ic_next, stringResource(R.string.next), Modifier.size(36.dp))
                }
                IconButton(onClick = { vm.cycleRepeat() }) {
                    Ico(
                        id = if (vm.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat,
                        desc = stringResource(R.string.repeat),
                        tint = if (vm.repeatMode == Player.REPEAT_MODE_OFF) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChipAction(speedLabel(vm.speed), vm.speed != 1f) { showSpeed = true }
                val sleepOn = vm.sleepLeft > 0L || vm.sleepEndOfTrack
                IconButton(onClick = { showSleep = true }) {
                    Ico(
                        R.drawable.ic_timer,
                        stringResource(R.string.sleep_title),
                        tint = if (sleepOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { showEq = true }) {
                    Ico(
                        R.drawable.ic_equalizer,
                        stringResource(R.string.equalizer),
                        tint = if (AudioFx.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (vm.sleepLeft > 0L) {
                Text(
                    stringResource(R.string.sleep_label, ((vm.sleepLeft + 59_999L) / 60_000L).toInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else if (vm.sleepEndOfTrack) {
                Text(
                    stringResource(R.string.sleep_end_short),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showQueue) QueueSheet(vm) { showQueue = false }
    if (showEq) EqualizerSheet { showEq = false }
    if (showSpeed) SpeedDialog(vm.speed, { vm.changeSpeed(it) }) { showSpeed = false }
    if (showSleep) SleepDialog(vm) { showSleep = false }
}

@Composable
private fun ChipAction(text: String, active: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
