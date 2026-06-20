package com.rechoraccoon.oscraccoon

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.view.Surface
import android.view.TextureView
import android.widget.ImageView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.roundToInt

@Composable
fun TracksContent(showTrackQueue: Boolean) {
    val context = LocalContext.current
    var playlists by remember { mutableStateOf(AppPreferences.loadPlaylists(context)) }
    var selectedPlaylistId by remember { mutableStateOf(ALL_TRACKS_ID) }
    var gridColumns by remember { mutableStateOf(3) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var editingTrack by remember { mutableStateOf<LocalTrack?>(null) }

    // Track drag (to playlist or reorder)
    var draggingTrack by remember { mutableStateOf<LocalTrack?>(null) }
    var dragPosition by remember { mutableStateOf(Offset.Zero) }
    var reorderDragStartY by remember { mutableStateOf(0f) }
    val playlistBounds = remember { mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>() }
    val trackRowBounds = remember { mutableStateMapOf<Int, androidx.compose.ui.geometry.Rect>() }
    var hoveredPlaylistId by remember { mutableStateOf<String?>(null) }

    // Playlist card drag-to-reorder
    var draggingPlaylistId by remember { mutableStateOf<String?>(null) }
    var playlistDragPosition by remember { mutableStateOf(Offset.Zero) }

    // Queue feedback
    var recentlyQueuedUri by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(recentlyQueuedUri) { if (recentlyQueuedUri != null) { delay(1500L); recentlyQueuedUri = null } }

    val allTracksSorted = LocalMediaState.tracks.sortedBy { it.title }

    // tracksVersion forces recomposition after edit (#4)
    val displayedTracks = remember(selectedPlaylistId, playlists, LocalMediaState.tracks.size, LocalMediaState.tracksVersion) {
        if (selectedPlaylistId == ALL_TRACKS_ID) LocalMediaState.tracks.sortedBy { it.title }
        else playlists.find { it.id == selectedPlaylistId }
            ?.trackUris?.mapNotNull { uri -> LocalMediaState.tracks.find { it.uri.toString() == uri } } ?: emptyList()
    }

    val trackListState = rememberLazyListState()
    val playlistGridState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()

    // Root is Box so ghost overlays z-order properly
    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {

            // ── LEFT: Track list + media player ──────────────────────────────
            Column(modifier = Modifier.weight(1f).fillMaxHeight()
                .background(BrownMid.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                .border(1.dp, GreenPrimary, RoundedCornerShape(10.dp))
                .padding(10.dp)) {

                // Header: label + Play button (always shown)
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    val label = if (selectedPlaylistId == ALL_TRACKS_ID) "All Tracks (${allTracksSorted.size})"
                                else "${playlists.find { it.id == selectedPlaylistId }?.name ?: "Tracks"} (${displayedTracks.size})"
                    Text(label, color = GreenPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    // Half-height Play button
                    if (displayedTracks.isNotEmpty()) {
                        Box(modifier = Modifier.height(18.dp)
                            .border(1.dp, GreenPrimary, RoundedCornerShape(4.dp))
                            .clickable { LocalMediaState.loadAndPlayPlaylist(displayedTracks, playlistId = selectedPlaylistId) }
                            .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center) {
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.PlayArrow, "Play", tint = GreenPrimary, modifier = Modifier.size(12.dp))
                                Text("Play", color = GreenPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                if (LocalMediaState.tracks.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("No tracks loaded.\nUse 📁 Folder to pick a folder.", color = GreenPrimary.copy(alpha = 0.5f), fontSize = 12.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
                    }
                } else {
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        GreenScrollbar(listState = trackListState, modifier = Modifier.fillMaxHeight().padding(end = 5.dp, top = 2.dp, bottom = 2.dp))
                        LazyColumn(state = trackListState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            itemsIndexed(displayedTracks, key = { _, t -> t.uri.toString() }) { index, track ->
                                val isCurrent = LocalMediaState.currentTrack?.uri == track.uri
                                val isJustQueued = recentlyQueuedUri == track.uri.toString()
                                val isDragging = draggingTrack == track
                                val rowScale by animateFloatAsState(if (isDragging) 0.94f else 1f, spring(0.6f, 400f), label = "ts")
                                var rowPos by remember { mutableStateOf(Offset.Zero) }

                                val dragMod = Modifier.pointerInput(track, selectedPlaylistId, displayedTracks.size) {
                                    detectDragGestures(
                                        onDragStart = { offset ->
                                            draggingTrack = track; dragPosition = rowPos + offset
                                            reorderDragStartY = rowPos.y + offset.y
                                        },
                                        onDrag = { _, dragAmount ->
                                            dragPosition += dragAmount
                                            // Only update hoveredPlaylist when no playlist is being dragged
                                            if (draggingPlaylistId == null) {
                                                hoveredPlaylistId = playlistBounds.entries
                                                    .firstOrNull { (_, r) -> r.contains(dragPosition) }?.key
                                            }
                                        },
                                        onDragEnd = {
                                            val t = draggingTrack; val targetId = hoveredPlaylistId
                                            if (t != null) {
                                                when {
                                                    targetId != null && targetId != ALL_TRACKS_ID && targetId != selectedPlaylistId -> {
                                                        // Drop on different playlist
                                                        if (selectedPlaylistId == ALL_TRACKS_ID) {
                                                            val upd = playlists.map { pl -> if (pl.id == targetId && !pl.trackUris.contains(t.uri.toString())) pl.copy(trackUris = pl.trackUris + t.uri.toString()) else pl }
                                                            playlists = upd; AppPreferences.savePlaylists(context, upd)
                                                        } else {
                                                            val upd = playlists.map { pl -> when {
                                                                pl.id == selectedPlaylistId -> pl.copy(trackUris = pl.trackUris.filter { it != t.uri.toString() })
                                                                pl.id == targetId && !pl.trackUris.contains(t.uri.toString()) -> pl.copy(trackUris = pl.trackUris + t.uri.toString())
                                                                else -> pl
                                                            }}; playlists = upd; AppPreferences.savePlaylists(context, upd)
                                                        }
                                                    }
                                                    selectedPlaylistId != ALL_TRACKS_ID && (targetId == null || targetId == selectedPlaylistId || targetId == ALL_TRACKS_ID) -> {
                                                        // Reorder in current playlist using trackRowBounds for accuracy
                                                        val pl = playlists.find { it.id == selectedPlaylistId }
                                                        if (pl != null) {
                                                            val targetIdx = trackRowBounds.entries
                                                                .filter { (i, _) -> i != index }
                                                                .minByOrNull { (_, rect) -> kotlin.math.abs((rect.top + rect.bottom) / 2f - dragPosition.y) }
                                                                ?.key ?: index
                                                            if (targetIdx != index) {
                                                                val fromUri = t.uri.toString()
                                                                val toUri = displayedTracks.getOrNull(targetIdx)?.uri?.toString()
                                                                if (toUri != null) {
                                                                    val uris = pl.trackUris.toMutableList()
                                                                    val fi = uris.indexOf(fromUri); val ti = uris.indexOf(toUri)
                                                                    if (fi >= 0 && ti >= 0) {
                                                                        val removed = uris.removeAt(fi); uris.add(ti, removed)
                                                                        val upd = playlists.map { if (it.id == selectedPlaylistId) it.copy(trackUris = uris) else it }
                                                                        playlists = upd; AppPreferences.savePlaylists(context, upd)
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                    else -> {}
                                                }
                                            }
                                            draggingTrack = null; hoveredPlaylistId = null
                                        },
                                        onDragCancel = { draggingTrack = null; hoveredPlaylistId = null }
                                    )
                                }

                                Row(modifier = Modifier.fillMaxWidth().scale(rowScale)
                                    .border(1.dp, if (isCurrent) GreenPrimary else GreenPrimary.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                    .background(if (isCurrent) GreenPrimary.copy(alpha = 0.1f) else if (isDragging) GreenPrimary.copy(alpha = 0.05f) else Color.Transparent, RoundedCornerShape(6.dp))
                                    .onGloballyPositioned { coords ->
                                        rowPos = coords.positionInRoot()
                                        val r = coords.positionInRoot()
                                        trackRowBounds[index] = androidx.compose.ui.geometry.Rect(r.x, r.y, r.x + coords.size.width, r.y + coords.size.height)
                                    }
                                    .then(dragMod)
                                    .clickable { LocalMediaState.loadAndPlayPlaylist(displayedTracks, index, selectedPlaylistId) }
                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(if (isCurrent) "▶" else "${index + 1}.", color = if (isCurrent) GreenPrimary else GreenPrimary.copy(alpha = 0.4f), fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(track.title, color = GreenPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(track.artist, color = GreenPrimary.copy(alpha = 0.7f), fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    IconButton(onClick = { LocalMediaState.addToQueue(track); recentlyQueuedUri = track.uri.toString() }, modifier = Modifier.size(22.dp)) {
                                        Icon(if (isJustQueued) Icons.Default.Check else Icons.Default.QueueMusic,
                                            contentDescription = null, tint = if (isJustQueued) GreenPrimary else GreenPrimary.copy(alpha = 0.7f), modifier = Modifier.size(14.dp)) }
                                    IconButton(onClick = { editingTrack = track }, modifier = Modifier.size(22.dp)) {
                                        Icon(Icons.Default.Edit, null, tint = GreenPrimary.copy(alpha = 0.6f), modifier = Modifier.size(13.dp)) }
                                    if (selectedPlaylistId != ALL_TRACKS_ID) {
                                        IconButton(onClick = {
                                            val upd = playlists.map { pl -> if (pl.id == selectedPlaylistId) pl.copy(trackUris = pl.trackUris.filter { it != track.uri.toString() }) else pl }
                                            playlists = upd; AppPreferences.savePlaylists(context, upd)
                                        }, modifier = Modifier.size(22.dp)) {
                                            Icon(Icons.Default.Remove, null, tint = GreenPrimary.copy(alpha = 0.6f), modifier = Modifier.size(14.dp)) }
                                    }
                                }
                            }
                        }
                    }
                }

                // Media player at bottom of track column
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = GreenPrimary.copy(alpha = 0.3f))
                Spacer(Modifier.height(4.dp))
                CompactLocalPlayer()
            }

            // ── RIGHT: Playlists OR Queue ─────────────────────────────────────
            Column(modifier = Modifier.weight(1f).fillMaxHeight()
                .background(BrownMid.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                .border(1.dp, GreenPrimary, RoundedCornerShape(10.dp))
                .padding(10.dp)) {

                if (showTrackQueue) {
                    Text("Queue", color = GreenPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 8.dp))
                    HorizontalDivider(color = GreenPrimary.copy(alpha = 0.3f)); Spacer(Modifier.height(6.dp))
                    if (LocalMediaState.manualQueue.isEmpty()) {
                        Box(Modifier.fillMaxSize(), Alignment.Center) {
                            Text("No songs queued.\nTap ☰ on any track to add.", color = GreenPrimary.copy(alpha = 0.45f), fontSize = 12.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center) }
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            itemsIndexed(LocalMediaState.manualQueue, key = { i, _ -> i }) { i, track ->
                                Row(modifier = Modifier.fillMaxWidth()
                                    .border(1.dp, GreenPrimary.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("${i+1}.", color = GreenPrimary.copy(alpha = 0.5f), fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(18.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(track.title, color = GreenPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(track.artist, color = GreenPrimary.copy(alpha = 0.7f), fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    IconButton(onClick = { LocalMediaState.removeFromQueue(i) }, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Default.Close, null, tint = GreenPrimary.copy(alpha = 0.7f), modifier = Modifier.size(14.dp)) }
                                }
                            }
                        }
                    }
                } else {
                    // Playlists
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Playlists", color = GreenPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Box(modifier = Modifier.height(18.dp).border(1.dp, GreenPrimary, RoundedCornerShape(4.dp)).clickable { showCreatePlaylist = true }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                            Text("+ New", color = GreenPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) }
                    }

                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        LazyVerticalGrid(columns = GridCells.Fixed(gridColumns.coerceIn(1, 5)),
                            state = playlistGridState,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentPadding = PaddingValues(top = 2.dp, bottom = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(playlists, key = { _, pl -> pl.id }) { _, playlist ->
                            val isAllTracks = playlist.id == ALL_TRACKS_ID
                            var isRenaming by remember { mutableStateOf(false) }
                            var renameText by remember(playlist.name) { mutableStateOf(playlist.name) }
                            val isHov = hoveredPlaylistId == playlist.id || (draggingPlaylistId != null && playlistBounds[playlist.id]?.contains(playlistDragPosition) == true && draggingPlaylistId != playlist.id)
                            val isSel = selectedPlaylistId == playlist.id
                            val cardScale by animateFloatAsState(when { isHov -> 1.06f; isSel -> 1.02f; else -> 1f }, spring(0.5f, 500f), label = "cs")
                            var cardPos by remember { mutableStateOf(Offset.Zero) }

                            val editCoverLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                                uri?.let {
                                    try { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) {}
                                    val upd = playlists.map { pl -> if (pl.id == playlist.id) pl.copy(coverUri = it.toString(), coverOffsetX = 0f, coverOffsetY = 0f, coverScale = 1f) else pl }
                                    playlists = upd; AppPreferences.savePlaylists(context, upd)
                                }
                            }

                            Column(modifier = Modifier.scale(cardScale)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isHov) GreenPrimary.copy(alpha = 0.15f) else if (isSel) GreenPrimary.copy(alpha = 0.1f) else BrownMid.copy(alpha = 0.6f))
                                .border(1.dp, if (isHov || isSel) GreenPrimary else GreenPrimary.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .onGloballyPositioned { coords ->
                                    val pos = coords.positionInRoot()
                                    cardPos = pos
                                    playlistBounds[playlist.id] = androidx.compose.ui.geometry.Rect(pos.x, pos.y, pos.x + coords.size.width, pos.y + coords.size.height)
                                }
                                .then(if (!isRenaming) Modifier.pointerInput(playlist.id) {
                                    detectDragGestures(
                                        onDragStart = { offset -> draggingPlaylistId = playlist.id; playlistDragPosition = cardPos + offset },
                                        onDrag = { _, dragAmount -> playlistDragPosition += dragAmount },
                                        onDragEnd = {
                                            val fromId = draggingPlaylistId
                                            if (fromId != null) {
                                                val targetId = playlistBounds.entries
                                                    .filter { (id, _) -> id != fromId && id != ALL_TRACKS_ID }
                                                    .firstOrNull { (_, r) -> r.contains(playlistDragPosition) }?.key
                                                    ?: playlistBounds.entries
                                                        .filter { (id, _) -> id != fromId && id != ALL_TRACKS_ID }
                                                        .minByOrNull { (_, r) ->
                                                            val cx = (r.left + r.right) / 2f; val cy = (r.top + r.bottom) / 2f
                                                            (cx - playlistDragPosition.x).let { it * it } + (cy - playlistDragPosition.y).let { it * it }
                                                        }?.key
                                                if (targetId != null) {
                                                    val fi = playlists.indexOfFirst { it.id == fromId }
                                                    val ti = playlists.indexOfFirst { it.id == targetId }
                                                    if (fi >= 0 && ti >= 0 && fi != ti) {
                                                        val m = playlists.toMutableList(); val item = m.removeAt(fi); m.add(ti, item)
                                                        playlists = m; AppPreferences.savePlaylists(context, m)
                                                    }
                                                }
                                                draggingPlaylistId = null
                                            }
                                        },
                                        onDragCancel = { draggingPlaylistId = null }
                                    )
                                } else Modifier)
                                .clickable { if (!isRenaming) selectedPlaylistId = playlist.id }
                            ) {
                                // Cover + edit overlay
                                Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
                                    PlaylistCoverView(playlist = playlist, modifier = Modifier.fillMaxSize())
                                    if (isRenaming) {
                                        // Edit cover overlay (lower z)
                                        Box(modifier = Modifier.fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.5f))
                                            .clickable { editCoverLauncher.launch(arrayOf("image/*", "video/*")) },
                                            contentAlignment = Alignment.Center) {
                                            Text("Edit Cover", color = GreenPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                        }
                                        // Delete cover X — higher z so it doesn't trigger edit cover
                                        if (playlist.coverUri.isNotEmpty()) {
                                            Box(modifier = Modifier.size(26.dp).align(Alignment.TopEnd)
                                                .padding(3.dp)
                                                .background(BrownDark.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
                                                .border(1.dp, GreenPrimary.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                                    val upd = playlists.map { if (it.id == playlist.id) it.copy(coverUri = "") else it }
                                                    playlists = upd; AppPreferences.savePlaylists(context, upd)
                                                },
                                                contentAlignment = Alignment.Center) {
                                                Icon(Icons.Default.Delete, "Remove cover", tint = GreenPrimary, modifier = Modifier.size(13.dp))
                                            }
                                        }
                                    }
                                }
                                // Info row: name + count + buttons
                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Row(modifier = Modifier.weight(1f).padding(end = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (isRenaming) {
                                            BasicTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true,
                                                textStyle = TextStyle(color = GreenPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                                                cursorBrush = SolidColor(GreenPrimary),
                                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                                keyboardActions = KeyboardActions(onDone = {
                                                    val u = playlists.map { if (it.id == playlist.id) it.copy(name = renameText) else it }
                                                    playlists = u; AppPreferences.savePlaylists(context, u); isRenaming = false
                                                }), modifier = Modifier.weight(1f))
                                        } else {
                                            Text(playlist.name, color = GreenPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                            val cnt = if (isAllTracks) LocalMediaState.tracks.size else playlist.trackUris.size
                                            Text("$cnt", color = GreenPrimary.copy(alpha = 0.5f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                        }
                                    }
                                    Row {
                                        if (isRenaming) {
                                            IconButton(onClick = {
                                                val u = playlists.map { if (it.id == playlist.id) it.copy(name = renameText) else it }
                                                playlists = u; AppPreferences.savePlaylists(context, u); isRenaming = false
                                            }, modifier = Modifier.size(20.dp)) { Icon(Icons.Default.Check, null, tint = GreenPrimary, modifier = Modifier.size(12.dp)) }
                                        } else {
                                            IconButton(onClick = { isRenaming = true }, modifier = Modifier.size(20.dp)) { Icon(Icons.Default.Edit, null, tint = GreenPrimary.copy(alpha = 0.7f), modifier = Modifier.size(12.dp)) }
                                            if (!isAllTracks) {
                                                IconButton(onClick = {
                                                    val u = playlists.filter { it.id != playlist.id }
                                                    playlists = u; AppPreferences.savePlaylists(context, u)
                                                    if (selectedPlaylistId == playlist.id) selectedPlaylistId = ALL_TRACKS_ID
                                                }, modifier = Modifier.size(20.dp)) { Icon(Icons.Default.Delete, null, tint = GreenPrimary.copy(alpha = 0.7f), modifier = Modifier.size(12.dp)) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        }
                        GreenGridScrollbar(gridState = playlistGridState, modifier = Modifier.fillMaxHeight().padding(start = 5.dp, top = 2.dp, bottom = 2.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        Text("Size:", color = GreenPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        RaccoonSlider(value = gridColumns.toFloat(), onValueChange = { gridColumns = it.toInt() }, valueRange = 1f..5f, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // Drag ghost — visible because root is Box
        if (draggingTrack != null) {
            Box(modifier = Modifier.offset { IntOffset(dragPosition.x.roundToInt() - 20, dragPosition.y.roundToInt() - 24) }
                .widthIn(max = 260.dp).zIndex(99f)
                .background(BrownMid.copy(alpha = 0.96f), RoundedCornerShape(6.dp))
                .border(1.dp, GreenPrimary, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)) {
                Column {
                    Text(draggingTrack!!.title, color = GreenPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(draggingTrack!!.artist, color = GreenPrimary.copy(alpha = 0.7f), fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // Playlist drag ghost
        if (draggingPlaylistId != null) {
            val pl = playlists.find { it.id == draggingPlaylistId }
            if (pl != null) {
                Box(modifier = Modifier.offset { IntOffset(playlistDragPosition.x.roundToInt() - 30, playlistDragPosition.y.roundToInt() - 30) }
                    .size(80.dp).zIndex(99f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(BrownMid.copy(alpha = 0.96f))
                    .border(1.dp, GreenPrimary, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center) {
                    Text(pl.name, color = GreenPrimary, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(4.dp))
                }
            }
        }

        // Dialogs in root Box so they render above everything
        if (showCreatePlaylist) {
            CreatePlaylistDialog(
                onConfirm = { name, coverUri, oX, oY, sc ->
                    val newPl = VirtualPlaylist(id = UUID.randomUUID().toString(), name = name, coverUri = coverUri, coverOffsetX = oX, coverOffsetY = oY, coverScale = sc)
                    val upd = playlists + newPl; playlists = upd; AppPreferences.savePlaylists(context, upd)
                    showCreatePlaylist = false
                },
                onDismiss = { showCreatePlaylist = false }
            )
        }
        editingTrack?.let { track ->
            EditTrackDialog(track = track,
                onConfirm = { newTitle, newArtist ->
                    var finalUri = track.uri
                    try {
                        val displayName = context.contentResolver.query(track.uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "track.mp3"
                        val ext = displayName.substringAfterLast('.', "mp3")
                        val renamed = DocumentsContract.renameDocument(context.contentResolver, track.uri, "$newTitle - $newArtist.$ext")
                        if (renamed != null) finalUri = renamed
                    } catch (e: Exception) { e.printStackTrace() }
                    // Save the override keyed to the FINAL uri (renameDocument returns a new
                    // uri since the document id encodes the filename) — saving under the old
                    // uri here was the bug: on next launch the rescan finds the file under its
                    // new uri, the override lookup misses, and the stale ID3 tags win back.
                    val overrides = AppPreferences.loadTrackOverrides(context).toMutableMap()
                    if (finalUri.toString() != track.uri.toString()) overrides.remove(track.uri.toString())
                    overrides[finalUri.toString()] = TrackOverride(newTitle, newArtist)
                    AppPreferences.saveTrackOverrides(context, overrides)
                    val updatedTrack = track.copy(uri = finalUri, title = newTitle, artist = newArtist)
                    LocalMediaState.updateTrackInfo(track.uri, updatedTrack)
                    if (finalUri != track.uri) {
                        val upd = playlists.map { pl -> pl.copy(trackUris = pl.trackUris.map { u -> if (u == track.uri.toString()) finalUri.toString() else u }) }
                        playlists = upd; AppPreferences.savePlaylists(context, upd)
                    }
                    editingTrack = null
                },
                onDismiss = { editingTrack = null })
        }
    }
}

// ── Green Scrollbar (smooth, uses viewportSize) ───────────────────────────────
@Composable
fun GreenScrollbar(listState: LazyListState, modifier: Modifier = Modifier) {
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    val info = listState.layoutInfo
    val totalItems = info.totalItemsCount
    val visibleItems = info.visibleItemsInfo
    val viewportH = info.viewportSize.height.toFloat().coerceAtLeast(1f)
    if (totalItems == 0 || visibleItems.isEmpty()) return

    val avgItemH = visibleItems.sumOf { it.size }.toFloat() / visibleItems.size
    val totalContentH = avgItemH * totalItems
    val thumbFraction = (viewportH / totalContentH).coerceIn(0.07f, 0.88f)
    val scrollableContentH = (totalContentH - viewportH).coerceAtLeast(1f)
    val scrollOffset = listState.firstVisibleItemIndex * avgItemH + listState.firstVisibleItemScrollOffset
    val scrollFraction = (scrollOffset / scrollableContentH).coerceIn(0f, 1f)

    if (thumbFraction >= 1f) return

    var trackHeightPx by remember { mutableStateOf(viewportH) }
    val thumbHeightPx = trackHeightPx * thumbFraction
    val thumbOffsetPx = (trackHeightPx - thumbHeightPx) * scrollFraction

    Box(modifier = modifier.width(14.dp).onGloballyPositioned { trackHeightPx = it.size.height.toFloat().coerceAtLeast(1f) }) {
        Box(Modifier.fillMaxSize().padding(horizontal = 3.dp).background(GreenPrimary.copy(alpha = 0.15f), RoundedCornerShape(4.dp)))
        val thumbH = with(density) { thumbHeightPx.toDp() }
        val thumbOff = with(density) { thumbOffsetPx.toDp() }
        Box(Modifier.fillMaxWidth().height(thumbH).offset(y = thumbOff)
            .background(GreenPrimary, RoundedCornerShape(4.dp))
            .pointerInput(Unit) {
                detectDragGestures { _, dragAmount ->
                    val i = listState.layoutInfo
                    val vi = i.visibleItemsInfo
                    if (vi.isEmpty()) return@detectDragGestures
                    val aH = vi.sumOf { it.size }.toFloat() / vi.size
                    val totH = aH * i.totalItemsCount
                    val vpH = i.viewportSize.height.toFloat().coerceAtLeast(1f)
                    val scrollableH = (totH - vpH).coerceAtLeast(1f)
                    val trkH = trackHeightPx.coerceAtLeast(1f)
                    val tmbH = trkH * (vpH / totH).coerceIn(0.07f, 0.88f)
                    val scrollableTrack = (trkH - tmbH).coerceAtLeast(1f)
                    val ratio = scrollableH / scrollableTrack
                    coroutineScope.launch { listState.scrollBy(dragAmount.y * ratio) }
                }
            }
        )
    }
}

// ── Green Pill Scrollbar for Grid (Playlists) ─────────────────────────────────
@Composable
fun GreenGridScrollbar(gridState: LazyGridState, modifier: Modifier = Modifier) {
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    val info = gridState.layoutInfo
    val totalItems = info.totalItemsCount
    val visibleItems = info.visibleItemsInfo
    val viewportH = info.viewportSize.height.toFloat().coerceAtLeast(1f)
    if (totalItems == 0 || visibleItems.isEmpty()) return

    // Estimate rows from columns count present in a single row's item span
    val columnsInRow = visibleItems.count { it.row == visibleItems.first().row }.coerceAtLeast(1)
    val totalRows = ((totalItems + columnsInRow - 1) / columnsInRow).coerceAtLeast(1)
    val avgRowH = visibleItems.groupBy { it.row }.values.firstOrNull()?.maxOfOrNull { it.size.height }?.toFloat() ?: 60f
    val totalContentH = avgRowH * totalRows
    val thumbFraction = (viewportH / totalContentH).coerceIn(0.07f, 0.88f)

    if (thumbFraction >= 1f) return

    val scrollableContentH = (totalContentH - viewportH).coerceAtLeast(1f)
    val currentRow = gridState.firstVisibleItemIndex / columnsInRow
    val scrollOffset = currentRow * avgRowH + gridState.firstVisibleItemScrollOffset
    val scrollFraction = (scrollOffset / scrollableContentH).coerceIn(0f, 1f)

    var trackHeightPx by remember { mutableStateOf(viewportH) }
    val thumbHeightPx = trackHeightPx * thumbFraction
    val thumbOffsetPx = (trackHeightPx - thumbHeightPx) * scrollFraction

    Box(modifier = modifier.width(14.dp).onGloballyPositioned { trackHeightPx = it.size.height.toFloat().coerceAtLeast(1f) }) {
        Box(Modifier.fillMaxSize().padding(horizontal = 3.dp).background(GreenPrimary.copy(alpha = 0.15f), RoundedCornerShape(4.dp)))
        val thumbH = with(density) { thumbHeightPx.toDp() }
        val thumbOff = with(density) { thumbOffsetPx.toDp() }
        Box(Modifier.fillMaxWidth().height(thumbH).offset(y = thumbOff)
            .background(GreenPrimary, RoundedCornerShape(4.dp))
            .pointerInput(Unit) {
                detectDragGestures { _, dragAmount ->
                    val i = gridState.layoutInfo
                    val vi = i.visibleItemsInfo
                    if (vi.isEmpty()) return@detectDragGestures
                    val colsInRow = vi.count { it.row == vi.first().row }.coerceAtLeast(1)
                    val rows = ((i.totalItemsCount + colsInRow - 1) / colsInRow).coerceAtLeast(1)
                    val rowH = vi.groupBy { it.row }.values.firstOrNull()?.maxOfOrNull { it.size.height }?.toFloat() ?: 60f
                    val totH = rowH * rows
                    val vpH = i.viewportSize.height.toFloat().coerceAtLeast(1f)
                    val scrollableH = (totH - vpH).coerceAtLeast(1f)
                    val trkH = trackHeightPx.coerceAtLeast(1f)
                    val tmbH = trkH * (vpH / totH).coerceIn(0.07f, 0.88f)
                    val scrollableTrack = (trkH - tmbH).coerceAtLeast(1f)
                    val ratio = scrollableH / scrollableTrack
                    coroutineScope.launch { gridState.scrollBy(dragAmount.y * ratio) }
                }
            }
        )
    }
}


@Composable
fun PlaylistCoverView(playlist: VirtualPlaylist, modifier: Modifier) {
    val context = LocalContext.current
    val raccoonIcon: ImageBitmap? = remember {
        try { BitmapFactory.decodeStream(context.assets.open("osc_raccoon_icon.png"))?.asImageBitmap() } catch (e: Exception) { null }
    }
    if (playlist.coverUri.isEmpty()) {
        Box(modifier = modifier.background(BrownDark), contentAlignment = Alignment.Center) {
            if (raccoonIcon != null) Image(bitmap = raccoonIcon, null, modifier = Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
            else Text("🎵", fontSize = 22.sp)
        }
        return
    }
    val uri = remember(playlist.coverUri) { try { Uri.parse(playlist.coverUri) } catch (e: Exception) { null } }
    val mimeType = remember(playlist.coverUri) { try { uri?.let { context.contentResolver.getType(it) } ?: "" } catch (e: Exception) { "" } }

    when {
        mimeType.startsWith("video/") && uri != null -> {
            val mp = remember(playlist.coverUri) { MediaPlayer() }
            DisposableEffect(playlist.coverUri) { onDispose { try { mp.stop(); mp.reset(); mp.release() } catch (e: Exception) {} } }
            AndroidView(factory = { ctx ->
                TextureView(ctx).apply {
                    val tv = this
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                            try {
                                mp.reset(); mp.setDataSource(ctx, uri); mp.setSurface(Surface(st))
                                mp.isLooping = true; mp.setVolume(0f, 0f)
                                mp.setOnPreparedListener { player ->
                                    val vW = player.videoWidth.toFloat().coerceAtLeast(1f); val vH = player.videoHeight.toFloat().coerceAtLeast(1f)
                                    val viewW = w.toFloat().coerceAtLeast(1f); val viewH = h.toFloat().coerceAtLeast(1f)
                                    val defSX = viewW / vW; val defSY = viewH / vH; val uniScale = maxOf(defSX, defSY)
                                    val matrix = android.graphics.Matrix()
                                    matrix.setScale(uniScale / defSX, uniScale / defSY, viewW / 2f, viewH / 2f)
                                    tv.setTransform(matrix); player.start()
                                }
                                mp.prepareAsync()
                            } catch (e: Exception) { e.printStackTrace() }
                        }
                        override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                        override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean { try { mp.stop() } catch (e: Exception) {}; return true }
                        override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                    }
                }
            }, modifier = modifier)
        }
        mimeType == "image/gif" && uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
            AndroidView(factory = { ctx ->
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    try {
                        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
                        val drawable = ImageDecoder.decodeDrawable(src)
                        setImageDrawable(drawable); (drawable as? AnimatedImageDrawable)?.start()
                    } catch (e: Exception) {}
                }
            }, modifier = modifier)
        }
        mimeType.startsWith("image/") && uri != null -> {
            val bitmap = remember(playlist.coverUri) {
                try { context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it)?.asImageBitmap() } } catch (e: Exception) { null }
            }
            if (bitmap != null) {
                val oX = playlist.coverOffsetX; val oY = playlist.coverOffsetY; val sc = playlist.coverScale.coerceAtLeast(1f)
                Canvas(modifier = modifier) {
                    val bW = bitmap.width.toFloat().coerceAtLeast(1f); val bH = bitmap.height.toFloat().coerceAtLeast(1f)
                    val cW = size.width; val cH = size.height
                    val base = maxOf(cW / bW, cH / bH) * sc
                    val sW = (cW / base).coerceAtMost(bW); val sH = (cH / base).coerceAtMost(bH)
                    val cx = bW / 2f + oX / base; val cy = bH / 2f + oY / base
                    val sl = (cx - sW / 2f).coerceIn(0f, (bW - sW).coerceAtLeast(0f))
                    val st = (cy - sH / 2f).coerceIn(0f, (bH - sH).coerceAtLeast(0f))
                    drawImage(bitmap, srcOffset = IntOffset(sl.toInt(), st.toInt()),
                        srcSize = IntSize(sW.toInt().coerceAtLeast(1), sH.toInt().coerceAtLeast(1)),
                        dstOffset = IntOffset.Zero, dstSize = IntSize(cW.toInt().coerceAtLeast(1), cH.toInt().coerceAtLeast(1)))
                }
            } else {
                Box(modifier = modifier.background(BrownDark), contentAlignment = Alignment.Center) {
                    if (raccoonIcon != null) Image(bitmap = raccoonIcon, null, modifier = Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
                    else Text("🎵", fontSize = 22.sp)
                }
            }
        }
        else -> Box(modifier = modifier.background(BrownDark), contentAlignment = Alignment.Center) {
            if (raccoonIcon != null) Image(bitmap = raccoonIcon, null, modifier = Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
            else Text("🎵", fontSize = 22.sp)
        }
    }
}

// ── Edit Track Dialog ─────────────────────────────────────────────────────────
@Composable
fun EditTrackDialog(track: LocalTrack, onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(track.title) }
    var artist by remember { mutableStateOf(track.artist) }
    Dialog(onDismissRequest = onDismiss) {
        Box(modifier = Modifier.width(320.dp).clip(RoundedCornerShape(12.dp)).drawBehind { drawCheckerboard() }.border(2.dp, GreenPrimary, RoundedCornerShape(12.dp)).padding(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Edit Track", color = GreenPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text("Saves to file on confirm.", color = GreenPrimary.copy(alpha = 0.55f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                HorizontalDivider(color = GreenPrimary.copy(alpha = 0.3f))
                Text("Title", color = GreenPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                BasicTextField(value = title, onValueChange = { title = it }, singleLine = true,
                    textStyle = TextStyle(color = GreenPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace), cursorBrush = SolidColor(GreenPrimary),
                    modifier = Modifier.fillMaxWidth().border(1.dp, GreenPrimary, RoundedCornerShape(6.dp)).padding(9.dp))
                Text("Artist", color = GreenPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                BasicTextField(value = artist, onValueChange = { artist = it }, singleLine = true,
                    textStyle = TextStyle(color = GreenPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace), cursorBrush = SolidColor(GreenPrimary),
                    modifier = Modifier.fillMaxWidth().border(1.dp, GreenPrimary, RoundedCornerShape(6.dp)).padding(9.dp))
                HorizontalDivider(color = GreenPrimary.copy(alpha = 0.3f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RaccoonButton(text = "Save", highlighted = true, onClick = { if (title.isNotBlank()) onConfirm(title.trim(), artist.trim()) })
                    RaccoonButton(text = "Cancel", onClick = onDismiss)
                }
            }
        }
    }
}

// ── Create Playlist Dialog ────────────────────────────────────────────────────
@Composable
fun CreatePlaylistDialog(onConfirm: (String, String, Float, Float, Float) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var coverUri by remember { mutableStateOf("") }
    var coverMimeType by remember { mutableStateOf("") }
    var previewBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var coverOffsetX by remember { mutableStateOf(0f) }
    var coverOffsetY by remember { mutableStateOf(0f) }
    var coverScale by remember { mutableStateOf(1f) }

    val coverPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) {}
            coverUri = it.toString(); coverMimeType = try { context.contentResolver.getType(it) ?: "" } catch (e: Exception) { "" }
            coverOffsetX = 0f; coverOffsetY = 0f; coverScale = 1f
            previewBitmap = if (coverMimeType.startsWith("image/") && coverMimeType != "image/gif")
                try { context.contentResolver.openInputStream(it)?.use { s -> android.graphics.BitmapFactory.decodeStream(s)?.asImageBitmap() } } catch (e: Exception) { null }
            else null
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(modifier = Modifier.width(250.dp).clip(RoundedCornerShape(12.dp)).drawBehind { drawCheckerboard() }.border(2.dp, GreenPrimary, RoundedCornerShape(12.dp)).padding(16.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Name field — no label
                BasicTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    textStyle = TextStyle(color = GreenPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace), cursorBrush = SolidColor(GreenPrimary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().border(1.dp, GreenPrimary, RoundedCornerShape(6.dp)).padding(9.dp),
                    decorationBox = { inner -> if (name.isEmpty()) Text("Playlist name...", color = GreenPrimary.copy(alpha = 0.4f), fontSize = 13.sp, fontFamily = FontFamily.Monospace); inner() })

                // Cover square — click to pick, "Add Cover" centered, no logo
                Box(modifier = Modifier.size(140.dp).clip(RoundedCornerShape(8.dp))
                    .border(1.dp, GreenPrimary.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                    .clickable { coverPickerLauncher.launch(arrayOf("image/*", "video/*")) }) {
                    when {
                        coverUri.isEmpty() -> Box(Modifier.fillMaxSize().background(BrownDark), contentAlignment = Alignment.Center) {
                            Text("Add Cover", color = GreenPrimary.copy(alpha = 0.7f), fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        }
                        coverMimeType.startsWith("video/") -> {
                            val uri = remember(coverUri) { try { Uri.parse(coverUri) } catch (e: Exception) { null } }
                            if (uri != null) {
                                val mp = remember(coverUri) { MediaPlayer() }
                                DisposableEffect(coverUri) { onDispose { try { mp.stop(); mp.reset(); mp.release() } catch (e: Exception) {} } }
                                AndroidView(factory = { ctx ->
                                    TextureView(ctx).apply {
                                        val tv = this
                                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                                                try { mp.reset(); mp.setDataSource(ctx, uri); mp.setSurface(Surface(st)); mp.isLooping = true; mp.setVolume(0f, 0f)
                                                    mp.setOnPreparedListener { p -> val vW = p.videoWidth.toFloat().coerceAtLeast(1f); val vH = p.videoHeight.toFloat().coerceAtLeast(1f); val viewW = w.toFloat().coerceAtLeast(1f); val viewH = h.toFloat().coerceAtLeast(1f)
                                                        val defSX = viewW / vW; val defSY = viewH / vH; val uniScale = maxOf(defSX, defSY); val matrix = android.graphics.Matrix(); matrix.setScale(uniScale / defSX, uniScale / defSY, viewW / 2f, viewH / 2f); tv.setTransform(matrix); p.start() }
                                                    mp.prepareAsync() } catch (e: Exception) { e.printStackTrace() }
                                            }
                                            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                                            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean { try { mp.stop() } catch (e: Exception) {}; return true }
                                            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                                        }
                                    }
                                }, modifier = Modifier.fillMaxSize())
                            }
                        }
                        coverMimeType == "image/gif" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                            val uri = remember(coverUri) { try { Uri.parse(coverUri) } catch (e: Exception) { null } }
                            if (uri != null) AndroidView(factory = { ctx ->
                                ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP
                                    try { val src = ImageDecoder.createSource(ctx.contentResolver, uri); val d = ImageDecoder.decodeDrawable(src); setImageDrawable(d); (d as? AnimatedImageDrawable)?.start() } catch (e: Exception) {}
                                }
                            }, modifier = Modifier.fillMaxSize())
                        }
                        previewBitmap != null -> {
                            val bmp = previewBitmap!!
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val bW = bmp.width.toFloat().coerceAtLeast(1f); val bH = bmp.height.toFloat().coerceAtLeast(1f)
                                val cW = size.width; val cH = size.height; val base = maxOf(cW / bW, cH / bH) * coverScale.coerceAtLeast(1f)
                                val sW = (cW / base).coerceAtMost(bW); val sH = (cH / base).coerceAtMost(bH)
                                val cx = bW / 2f + coverOffsetX / base; val cy = bH / 2f + coverOffsetY / base
                                val sl = (cx - sW / 2f).coerceIn(0f, (bW - sW).coerceAtLeast(0f)); val st = (cy - sH / 2f).coerceIn(0f, (bH - sH).coerceAtLeast(0f))
                                drawImage(bmp, srcOffset = IntOffset(sl.toInt(), st.toInt()), srcSize = IntSize(sW.toInt().coerceAtLeast(1), sH.toInt().coerceAtLeast(1)), dstOffset = IntOffset.Zero, dstSize = IntSize(cW.toInt().coerceAtLeast(1), cH.toInt().coerceAtLeast(1)))
                            }
                        }
                        else -> Box(Modifier.fillMaxSize().background(BrownDark), contentAlignment = Alignment.Center) { Text("⚠️", fontSize = 20.sp) }
                    }
                }

                // Crop sliders for static images
                if (previewBitmap != null) {
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Zoom", color = GreenPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        RaccoonSlider(value = coverScale, onValueChange = { coverScale = it }, valueRange = 1f..3f, modifier = Modifier.fillMaxWidth())
                        Text("Pan X", color = GreenPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        RaccoonSlider(value = coverOffsetX, onValueChange = { coverOffsetX = it }, valueRange = -600f..600f, modifier = Modifier.fillMaxWidth())
                        Text("Pan Y", color = GreenPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        RaccoonSlider(value = coverOffsetY, onValueChange = { coverOffsetY = it }, valueRange = -600f..600f, modifier = Modifier.fillMaxWidth())
                    }
                }

                HorizontalDivider(color = GreenPrimary.copy(alpha = 0.3f))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.weight(1f).height(32.dp).background(GreenPrimary, RoundedCornerShape(6.dp)).clickable { if (name.isNotBlank()) onConfirm(name.trim(), coverUri, coverOffsetX, coverOffsetY, coverScale) }, contentAlignment = Alignment.Center) {
                        Text("Create", color = BrownDark, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) }
                    Box(modifier = Modifier.weight(1f).height(32.dp).border(1.dp, GreenPrimary, RoundedCornerShape(6.dp)).clickable { onDismiss() }, contentAlignment = Alignment.Center) {
                        Text("Cancel", color = GreenPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) }
                }
            }
        }
    }
}
