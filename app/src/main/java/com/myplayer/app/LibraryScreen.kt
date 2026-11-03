package com.myplayer.app

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalContext

@Composable
fun LibraryScreen(
    vm: PlayerViewModel,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onLanguageChanged: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column {
                if (vm.currentItem != null && !vm.showPlayer) MiniPlayer(vm)
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    val items = listOf(
                        Triple(R.drawable.ic_music, R.string.tab_audio, 0),
                        Triple(R.drawable.ic_video, R.string.tab_video, 1),
                        Triple(R.drawable.ic_playlist, R.string.tab_playlists, 2),
                        Triple(R.drawable.ic_settings, R.string.tab_settings, 3),
                    )
                    for ((icon, label, index) in items) {
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { Ico(icon, null) },
                            label = { Text(stringResource(label), maxLines = 1) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (tab == 3) {
                SettingsScreen(vm, onLanguageChanged)
            } else if (!hasPermission) {
                PermissionScreen(onRequestPermission)
            } else {
                when (tab) {
                    0 -> MediaTab(vm, video = false)
                    1 -> MediaTab(vm, video = true)
                    else -> PlaylistsTab(vm)
                }
            }
        }
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Ico(R.drawable.ic_folder, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.perm_needed), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRequest) { Text(stringResource(R.string.perm_grant)) }
    }
}

private fun sortEntries(list: List<MediaEntry>, sort: Int): List<MediaEntry> {
    return when (sort) {
        1 -> list.sortedBy { it.title.lowercase() }
        2 -> list.sortedByDescending { it.durationMs }
        else -> list.sortedByDescending { it.dateAdded }
    }
}

@Composable
private fun MediaTab(vm: PlayerViewModel, video: Boolean) {
    val ctx = LocalContext.current
    var query by rememberSaveable(video) { mutableStateOf("") }
    var sort by rememberSaveable(video) { mutableIntStateOf(0) }
    var favOnly by rememberSaveable(video) { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }

    val source = if (video) vm.videos else vm.audio
    val list = remember(source, query, sort, favOnly, vm.favorites) {
        val q = query.trim()
        val filtered = source.filter { e ->
            (!favOnly || vm.favorites.contains(e.key)) &&
                (q.isEmpty() || e.title.contains(q, true) || e.artist.contains(q, true) || e.folder.contains(q, true))
        }
        sortEntries(filtered, sort)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val type = ctx.contentResolver.getType(uri) ?: ""
            vm.playExternal(uri, type.startsWith("video"))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Ico(R.drawable.ic_search, stringResource(R.string.search), Modifier.size(22.dp)) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Ico(R.drawable.ic_close, stringResource(R.string.close), Modifier.size(20.dp)) }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        )

        // الصف الأول: زرين بنفس العرض (بيتحملوا النص العربي الطويل من غير تداخل)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = { vm.play(list, 0) },
                enabled = list.isNotEmpty(),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Ico(R.drawable.ic_play, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.play_all), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(
                onClick = { vm.play(list, 0, shuffle = true) },
                enabled = list.isNotEmpty(),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Ico(R.drawable.ic_shuffle, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.shuffle), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        // الصف التاني: الفلاتر على جنب، وأدوات الترتيب والفتح والتحديث على الجنب التاني
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = !favOnly,
                onClick = { favOnly = false },
                label = { Text(stringResource(R.string.filter_all) + " · " + list.size, maxLines = 1) },
            )
            FilterChip(selected = favOnly, onClick = { favOnly = true }, label = { Text(stringResource(R.string.filter_favorites), maxLines = 1) })
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { sortMenu = true }, modifier = Modifier.size(40.dp)) { Ico(R.drawable.ic_sort, stringResource(R.string.sort)) }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    val options = listOf(R.string.sort_newest, R.string.sort_name, R.string.sort_duration)
                    for ((i, res) in options.withIndex()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(res), fontWeight = if (sort == i) FontWeight.Bold else FontWeight.Normal) },
                            onClick = {
                                sort = i
                                sortMenu = false
                            },
                        )
                    }
                }
            }
            IconButton(onClick = { picker.launch(arrayOf("audio/*", "video/*", "application/ogg")) }, modifier = Modifier.size(40.dp)) {
                Ico(R.drawable.ic_open, stringResource(R.string.open_files))
            }
            IconButton(onClick = { vm.loadLibrary() }, modifier = Modifier.size(40.dp)) { Ico(R.drawable.ic_refresh, stringResource(R.string.refresh)) }
        }

        if (list.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.empty_list), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(list, key = { _, e -> e.key }) { index, entry ->
                    MediaRow(vm, entry, onClick = { vm.play(list, index) })
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

@Composable
fun MediaRow(
    vm: PlayerViewModel,
    entry: MediaEntry,
    onClick: () -> Unit,
    removeFromList: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    var pickList by remember { mutableStateOf(false) }
    val current = vm.currentItem?.mediaId == entry.key
    val fav = vm.favorites.contains(entry.key)
    val progress = if (entry.isVideo) vm.savedFraction(entry) else 0f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            MediaThumb(entry = entry, isVideo = entry.isVideo, size = 54.dp, corner = 14.dp)
            if (progress > 0f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                    trackColor = androidx.compose.ui.graphics.Color(0x66000000),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            val sub = buildString {
                if (entry.artist.isNotBlank()) append(entry.artist).append(" · ")
                else if (entry.folder.isNotBlank()) append(entry.folder).append(" · ")
                append(formatTime(entry.durationMs))
            }
            Text(
                sub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (fav) {
            Ico(R.drawable.ic_favorite, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Box {
            IconButton(onClick = { menu = true }) { Ico(R.drawable.ic_more, stringResource(R.string.more)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_play_next)) },
                    onClick = {
                        menu = false
                        vm.playNext(entry)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_add_queue)) },
                    onClick = {
                        menu = false
                        vm.addToQueue(entry)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.favorite)) },
                    onClick = {
                        menu = false
                        vm.toggleFavorite(entry.key)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_add_playlist)) },
                    onClick = {
                        menu = false
                        pickList = true
                    },
                )
                if (removeFromList != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_remove_playlist)) },
                        onClick = {
                            menu = false
                            removeFromList()
                        },
                    )
                }
            }
        }
    }

    if (pickList) AddToPlaylistDialog(vm, entry.key) { pickList = false }
}

@Composable
private fun AddToPlaylistDialog(vm: PlayerViewModel, key: String, onDismiss: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_to_playlist_title)) },
        text = {
            Column {
                if (creating) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.playlist_name_hint)) },
                    )
                } else {
                    LazyColumn(modifier = Modifier.height(240.dp)) {
                        items(vm.playlists.keys.toList()) { n ->
                            Text(
                                n,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.addToPlaylist(n, key)
                                        onDismiss()
                                    }
                                    .padding(vertical = 12.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                    TextButton(onClick = { creating = true }) { Text(stringResource(R.string.playlist_new_plus)) }
                }
            }
        },
        confirmButton = {
            if (creating) {
                TextButton(onClick = {
                    if (vm.createPlaylist(name)) vm.addToPlaylist(name.trim(), key)
                    onDismiss()
                }) { Text(stringResource(R.string.create)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PlaylistsTab(vm: PlayerViewModel) {
    // "fav" / "recent" / "pl:<name>" / null
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = open != null && !vm.showPlayer) { open = null }

    val current = open
    if (current == null) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                PlaylistRow(R.drawable.ic_favorite, stringResource(R.string.favorites_title), vm.entriesFor(vm.favorites.toList()).size) { open = "fav" }
            }
            item {
                PlaylistRow(R.drawable.ic_history, stringResource(R.string.recent_title), vm.entriesFor(vm.recent).size) { open = "recent" }
            }
            items(vm.playlists.keys.toList()) { name ->
                PlaylistRow(R.drawable.ic_playlist, name, vm.entriesFor(vm.playlists[name] ?: emptyList()).size) { open = "pl:$name" }
            }
            item {
                TextButton(
                    onClick = { creating = true },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                ) { Text(stringResource(R.string.playlist_new_plus)) }
            }
            if (vm.playlists.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.empty_playlists_hint),
                        modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    } else {
        val isUser = current.startsWith("pl:")
        val plName = if (isUser) current.removePrefix("pl:") else ""
        val title = when {
            current == "fav" -> stringResource(R.string.favorites_title)
            current == "recent" -> stringResource(R.string.recent_title)
            else -> plName
        }
        val entries = when {
            current == "fav" -> vm.entriesFor(vm.favorites.toList())
            current == "recent" -> vm.entriesFor(vm.recent)
            else -> vm.entriesFor(vm.playlists[plName] ?: emptyList())
        }
        Column(modifier = Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                IconButton(onClick = { open = null }) { Ico(R.drawable.ic_back, stringResource(R.string.back)) }
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { vm.play(entries, 0) }, enabled = entries.isNotEmpty()) { Ico(R.drawable.ic_play, stringResource(R.string.play_all)) }
                IconButton(onClick = { vm.play(entries, 0, shuffle = true) }, enabled = entries.isNotEmpty()) { Ico(R.drawable.ic_shuffle, stringResource(R.string.shuffle)) }
                if (isUser) {
                    IconButton(onClick = { confirmDelete = plName }) { Ico(R.drawable.ic_delete, stringResource(R.string.delete)) }
                }
            }
            if (entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.empty_playlist), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(entries, key = { _, e -> e.key }) { index, entry ->
                        MediaRow(
                            vm,
                            entry,
                            onClick = { vm.play(entries, index) },
                            removeFromList = if (isUser) ({ vm.removeFromPlaylist(plName, entry.key) }) else null,
                        )
                    }
                }
            }
        }
    }

    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text(stringResource(R.string.playlist_new)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.playlist_name_hint)) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.createPlaylist(newName)
                    newName = ""
                    creating = false
                }) { Text(stringResource(R.string.create)) }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    val del = confirmDelete
    if (del != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.delete_playlist_title)) },
            text = { Text(stringResource(R.string.delete_playlist_msg, del)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deletePlaylist(del)
                    confirmDelete = null
                    open = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun PlaylistRow(icon: Int, title: String, count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .androidx_clip(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Ico(icon, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.items_count, count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Modifier.androidx_clip(corner: androidx.compose.ui.unit.Dp): Modifier {
    return this
        .clip(RoundedCornerShape(corner))
        .background(MaterialTheme.colorScheme.primaryContainer)
}
