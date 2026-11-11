package com.myplayer.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(vm: PlayerViewModel, onLanguageChanged: () -> Unit) {
    val ctx = LocalContext.current
    val s = vm.settings
    var showFolders by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
    ) {
        Text(
            stringResource(R.string.tab_settings),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )

        // ---- اللغة ----
        SectionTitle(stringResource(R.string.settings_language))
        ChipRow {
            val langs = listOf("system" to R.string.lang_system, "ar" to R.string.lang_arabic, "en" to R.string.lang_english)
            for ((code, label) in langs) {
                FilterChip(
                    selected = s.language == code,
                    onClick = {
                        if (s.language != code) {
                            s.updateLanguage(code)
                            onLanguageChanged()
                        }
                    },
                    label = { Text(stringResource(label)) },
                )
            }
        }

        // ---- المظهر ----
        SectionTitle(stringResource(R.string.settings_appearance))
        Text(
            stringResource(R.string.theme_title),
            modifier = Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.labelLarge,
        )
        ChipRow {
            val themes = listOf(0 to R.string.theme_system, 1 to R.string.theme_light, 2 to R.string.theme_dark)
            for ((mode, label) in themes) {
                FilterChip(selected = s.themeMode == mode, onClick = { s.updateThemeMode(mode) }, label = { Text(stringResource(label)) })
            }
        }
        Text(
            stringResource(R.string.accent_title),
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp),
            style = MaterialTheme.typography.labelLarge,
        )
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            for (i in Accents.indices) {
                val selected = s.accent == i
                val swatch = swatchColor(i)
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(swatch)
                        .then(
                            if (selected) {
                                Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            } else {
                                Modifier
                            },
                        )
                        .clickable { s.updateAccent(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Ico(R.drawable.ic_check, stringResource(Accents[i].nameRes), Modifier.size(20.dp), tint = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
        Text(
            stringResource(Accents[s.accent.coerceIn(0, Accents.lastIndex)].nameRes),
            modifier = Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow(stringResource(R.string.amoled), stringResource(R.string.amoled_desc), s.amoled) { s.updateAmoled(it) }

        // ---- التشغيل ----
        SectionTitle(stringResource(R.string.settings_playback))
        SwitchRow(stringResource(R.string.resume_title), stringResource(R.string.resume_desc), s.resume) { s.updateResume(it) }
        SwitchRow(stringResource(R.string.video_bg_title), stringResource(R.string.video_bg_desc), s.videoBackground) { s.updateVideoBackground(it) }
        SwitchRow(stringResource(R.string.clean_player_title), stringResource(R.string.clean_player_desc), s.cleanPlayer) { s.updateCleanPlayer(it) }
        SwitchRow(stringResource(R.string.pause_call_title), stringResource(R.string.pause_call_desc), s.pauseOnCall) { s.updatePauseOnCall(it) }
        SwitchRow(stringResource(R.string.live_seek_title), stringResource(R.string.live_seek_desc), s.liveSeek) { s.updateLiveSeek(it) }
        SwitchRow(stringResource(R.string.auto_pip_title), stringResource(R.string.auto_pip_desc), s.autoPip) { s.updateAutoPip(it) }

        // ---- المكتبة ----
        SectionTitle(stringResource(R.string.settings_library))
        SwitchRow(stringResource(R.string.hide_rec_title), stringResource(R.string.hide_rec_desc), s.hideRecordings) { vm.setHideRecordings(it) }
        ClickRow(stringResource(R.string.hidden_folders_title), stringResource(R.string.hidden_folders_desc)) { showFolders = true }
        ClickRow(stringResource(R.string.rescan), null) { vm.loadLibrary() }

        // ---- التشخيص ----
        SectionTitle(stringResource(R.string.diagnostics))
        ClickRow(stringResource(R.string.view_log), null) { showLog = true }

        // ---- حول ----
        SectionTitle(stringResource(R.string.settings_about))
        Column(modifier = Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            val version = try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
            } catch (e: Exception) {
                ""
            }
            Text(stringResource(R.string.version_fmt, version), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.developer_label) + ": ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.developer_name), fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.privacy_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showFolders) {
        val folders = remember(vm.libraryLoaded, vm.audio, vm.videos) { vm.allFolders() }
        AlertDialog(
            onDismissRequest = { showFolders = false },
            title = { Text(stringResource(R.string.hidden_folders_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (folders.isEmpty()) Text(stringResource(R.string.empty_list), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for (f in folders) {
                        val hidden = s.hiddenFolders.contains(f)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { vm.toggleHiddenFolder(f) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = hidden, onCheckedChange = { vm.toggleHiddenFolder(f) })
                            Text(f)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showFolders = false }) { Text(stringResource(R.string.close)) } },
        )
    }

    if (showLog) LogDialog { showLog = false }
}

@Composable
private fun LogDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(AppLog.read()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.view_log)) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(10.dp),
            ) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                    Text(
                        if (text.isBlank()) stringResource(R.string.log_empty) else text.takeLast(12000),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("QuickPlayer log", text))
                }) { Text(stringResource(R.string.log_copy)) }
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND)
                    send.type = "text/plain"
                    send.putExtra(Intent.EXTRA_TEXT, text)
                    ctx.startActivity(Intent.createChooser(send, null))
                }) { Text(stringResource(R.string.log_share)) }
                TextButton(onClick = {
                    AppLog.clear()
                    text = ""
                }) { Text(stringResource(R.string.log_clear)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ClickRow(title: String, desc: String?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (desc != null) Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** لون الدائرة: للخيار الأول ("لون الخلفية") بنعرض اللون الحقيقي المأخوذ من خلفية الموبايل. */
@Composable
private fun swatchColor(index: Int): Color {
    val ctx = LocalContext.current
    if (index == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        return if (dark) dynamicDarkColorScheme(ctx).primary else dynamicLightColorScheme(ctx).primary
    }
    return accentPreview(index)
}
