package com.myplayer.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(vm: PlayerViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(
            stringResource(R.string.queue_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        if (vm.queue.isEmpty()) {
            Text(
                stringResource(R.string.queue_empty),
                modifier = Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().height(460.dp)) {
                itemsIndexed(vm.queue) { index, item ->
                    val current = index == vm.queueIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.jumpTo(index) }
                            .padding(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                item.mediaMetadata.title?.toString() ?: "",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                            val sub = item.mediaMetadata.artist?.toString() ?: ""
                            if (sub.isNotBlank()) {
                                Text(
                                    sub,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        IconButton(onClick = { vm.removeFromQueue(index) }) {
                            Ico(R.drawable.ic_close, stringResource(R.string.remove), Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun dbLabel(mb: Int): String {
    val v = mb / 100f
    return String.format(Locale.US, "%+.1f dB", v)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.equalizer),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { AudioFx.reset() }) { Text(stringResource(R.string.eq_reset)) }
            }
            if (!AudioFx.available) {
                Text(
                    stringResource(R.string.eq_unavailable),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.eq_enable), modifier = Modifier.weight(1f))
                    Switch(checked = AudioFx.enabled, onCheckedChange = { AudioFx.setEnabled(it) })
                }
                if (AudioFx.presets.isNotEmpty()) {
                    Text(
                        stringResource(R.string.eq_presets),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(AudioFx.presets) { i, name ->
                            FilterChip(
                                selected = AudioFx.preset == i,
                                onClick = { AudioFx.usePreset(i) },
                                label = { Text(name) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                for (i in AudioFx.bandLevels.indices) {
                    val level = AudioFx.bandLevels[i]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            freqLabel(AudioFx.bandFreqs.getOrElse(i) { 0 }),
                            modifier = Modifier.width(72.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Slider(
                            value = level.toFloat(),
                            onValueChange = { AudioFx.setBand(i, it.toInt()) },
                            valueRange = AudioFx.minLevel.toFloat()..AudioFx.maxLevel.toFloat(),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            dbLabel(level),
                            modifier = Modifier.width(64.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (AudioFx.bassSupported) {
                    Text(
                        stringResource(R.string.eq_bass),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Slider(
                        value = AudioFx.bass.toFloat(),
                        onValueChange = { AudioFx.setBass(it.toInt()) },
                        valueRange = 0f..1000f,
                    )
                }
                Text(
                    stringResource(R.string.eq_boost),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Slider(
                    value = AudioFx.boost.toFloat(),
                    onValueChange = { AudioFx.setBoost(it.toInt()) },
                    valueRange = 0f..1500f,
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
fun SpeedDialog(current: Float, onSet: (Float) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableFloatStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.speed_title)) },
        text = {
            Column {
                Text(
                    speedLabel(value),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Slider(
                    value = value,
                    onValueChange = { value = Math.round(it * 20f) / 20f },
                    valueRange = 0.25f..3f,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val presets = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
                    itemsIndexed(presets) { _, p ->
                        FilterChip(selected = value == p, onClick = { value = p }, label = { Text(speedLabel(p)) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSet(value)
                onDismiss()
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun SleepDialog(vm: PlayerViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sleep_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                val active = vm.sleepLeft > 0L || vm.sleepEndOfTrack
                SleepRow(stringResource(R.string.sleep_off), !active) {
                    vm.cancelSleep()
                    onDismiss()
                }
                for (m in listOf(10, 15, 30, 45, 60, 90)) {
                    SleepRow(stringResource(R.string.sleep_minutes, m), false) {
                        vm.setSleep(m)
                        onDismiss()
                    }
                }
                SleepRow(stringResource(R.string.sleep_end_track), vm.sleepEndOfTrack) {
                    vm.setSleepEndOfTrack()
                    onDismiss()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun SleepRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
fun TracksDialog(vm: PlayerViewModel, onAddSubtitle: () -> Unit, onDismiss: () -> Unit) {
    val audioOpts = vm.trackOptions(C.TRACK_TYPE_AUDIO)
    val textOpts = vm.trackOptions(C.TRACK_TYPE_TEXT)
    val textDisabled = vm.controller?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true
    val anyText = textOpts.any { it.selected }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tracks_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.audio_tracks),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (audioOpts.isEmpty()) {
                    Text(stringResource(R.string.tracks_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                for (o in audioOpts) {
                    SleepRow(o.label, o.selected) { vm.selectTrack(o) }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.subtitles),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                SleepRow(stringResource(R.string.subtitles_off), textDisabled || !anyText) { vm.disableText() }
                for (o in textOpts) {
                    SleepRow(o.label, o.selected && !textDisabled) { vm.selectTrack(o) }
                }
                TextButton(onClick = {
                    onDismiss()
                    onAddSubtitle()
                }) { Text(stringResource(R.string.subtitles_add)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
