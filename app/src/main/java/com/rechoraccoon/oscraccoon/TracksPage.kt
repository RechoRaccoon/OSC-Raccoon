package com.rechoraccoon.oscraccoon

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.widget.ImageView
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
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

// ── Main Tracks Content ───────────────────────────────────────────────────────
@Composable
fun TracksContent(showTrackQueue: Boolean) {
    val context = LocalContext.current
    var playlists by remember { mutableStateOf(AppPreferences.loadPlaylists(context)) }
    var selectedPlaylistId by remember { mutableStateOf(ALL_TRACKS_ID) }
    var gridColumns by remember { mutableStateOf(3) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var editingTrack by remember { mutableStateOf<LocalTrack?>(null) }

    // Shared drag state (used in both all-tracks and playlist views)
    var draggingTrack by remember { mutableStateOf<LocalTrack?>(null) }
    var dragPosition by remember { mutableStateOf(Offset.Zero) }
    var reorderDragStartY by remember { mutableStateOf(0f) }
    val playlistBounds = remember { mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>() }
    var hoveredPlaylistId by remember { mutableStateOf<String?>(null) }

    // Queue feedback
    var recentlyQueuedUri by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(recentlyQueuedUri) {
        if (recentlyQueuedUri != null) { delay(1500L); recentlyQueuedUri = null }
    }

    val allTracks = LocalMediaState.tracks.sortedBy { it.title }
    val displayedTracks = remember(selectedPlaylistId, playlists, LocalMediaState.tracks.size) {
        if (selectedPlaylistId == ALL_TRACKS_ID) {
            LocalMediaState.tracks.sortedBy { it.title }
        } else {
            playlists.find { it.id == selectedPlaylistId }
                ?.trackUris?.mapNotNull { uri -> LocalMediaState.tracks.find { it.uri.toString() == uri } } ?: emptyList()
        }
    }

    val trackListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    Row(modifier = Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {

        // ── LEFT: Track list + media player ──────────────────────────────────
        Column(modifier = Modifier.weight(1f).fillMaxHeight()
            .background(BrownMid.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .border(1.dp, GreenPrimary, RoundedCornerShape(10.dp))
            .padding(10.dp)) {

            // Header: label + optional play button
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                val label = if (selectedPlaylistId == ALL_TRACKS_ID) "All Tracks (${allTracks.size})"
                            else "${playlists.find { it.id == selectedPlaylistId }?.name ?: "Tracks"} (${displayedTracks.size})"
                Text(label, color = GreenPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                if (selectedPlaylistId != ALL_TRACKS_ID && displayedTracks.isNotEmpty()) {
                    Box(modifier = Modifier.size(28.dp)
                        .border(1.dp, GreenPrimary, RoundedCornerShape(6.dp))
                        .clickable {
                            val tracks = displayedTracks
                            if (tracks.isNotEmpty()) LocalMediaState.loadAndPlayPlaylist(tracks)
                        }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play playlist", tint = GreenPrimary, modifier = Modifier.size(18.dp))
                    }
                }
            }

            if (LocalMediaState.tracks.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No tracks loaded.\nUse the 📁 Folder button above to pick a folder.", color = GreenPrimary.copy(alpha = 0.5f), fontSize = 12.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
                }
            } else {
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    // Green pill scrollbar — smooth pixel-level scrolling
                    GreenScrollbar(listState = trackListState, modifier = Modifier.fillMaxHeight().padding(end = 6.dp, top = 2.dp, bottom = 2.dp))

                    LazyColumn(state = trackListState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(displayedTracks, key = { _, track -> track.uri.toString() }) { index, track ->
                            val isCurrent = LocalMediaState.currentTrack?.uri == track.uri
                            val isJustQueued = recentlyQueuedUri == track.uri.toString()
                            val isDraggingThis = draggingTrack == track
                            val rowScale by animateFloatAsState(if (isDraggingThis) 0.94f else 1f, spring(0.6f, 400f), label = "ts")
                            var rowPos by remember { mutableStateOf(Offset.Zero) }

                            // Unified drag logic: all-tracks → add to playlist; playlist view → reorder OR move to playlist
                            val dragModifier = Modifier.pointerInput(track, selectedPlaylistId, displayedTracks.size) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        draggingTrack = track
                                        dragPosition = rowPos + offset
                                        reorderDragStartY = rowPos.y + offset.y
                                    },
                                    onDrag = { _, dragAmount ->
                                        dragPosition += dragAmount
                                        hoveredPlaylistId = playlistBounds.entries
                                            .firstOrNull { (_, rect) -> rect.contains(dragPosition) }?.key
                                    },
                                    onDragEnd = {
                                        val t = draggingTrack
                                        val targetId = hoveredPlaylistId
                                        if (t != null) {
                                            when {
                                                // Dropped on a different, non-All-Tracks playlist
                                                targetId != null && targetId != ALL_TRACKS_ID && targetId != selectedPlaylistId -> {
                                                    if (selectedPlaylistId == ALL_TRACKS_ID) {
                                                        // Add to target
                                                        val updated = playlists.map { pl ->
                                                            if (pl.id == targetId && !pl.trackUris.contains(t.uri.toString()))
                                                                pl.copy(trackUris = pl.trackUris + t.uri.toString()) else pl
                                                        }
                                                        playlists = updated; AppPreferences.savePlaylists(context, updated)
                                                    } else {
                                                        // Move from current playlist to target
                                                        val updated = playlists.map { pl ->
                                                            when {
                                                                pl.id == selectedPlaylistId -> pl.copy(trackUris = pl.trackUris.filter { it != t.uri.toString() })
                                                                pl.id == targetId && !pl.trackUris.contains(t.uri.toString()) -> pl.copy(trackUris = pl.trackUris + t.uri.toString())
                                                                else -> pl
                                                            }
                                                        }
                                                        playlists = updated; AppPreferences.savePlaylists(context, updated)
                                                    }
                                                }
                                                // Dropped in list area within a playlist → reorder
                                                selectedPlaylistId != ALL_TRACKS_ID && (targetId == null || targetId == selectedPlaylistId || targetId == ALL_TRACKS_ID) -> {
                                                    val pl = playlists.find { it.id == selectedPlaylistId }
                                                    if (pl != null) {
                                                        val estH = 52.dp.toPx()
                                                        val totalDragY = dragPosition.y - reorderDragStartY
                                                        val delta = (totalDragY / estH).roundToInt()
                                                        val to = (index + delta).coerceIn(0, displayedTracks.size - 1)
                                                        if (to != index) {
                                                            val fromUri = t.uri.toString()
                                                            val toUri = displayedTracks.getOrNull(to)?.uri?.toString()
                                                            if (toUri != null) {
                                                                val uris = pl.trackUris.toMutableList()
                                                                val fi = uris.indexOf(fromUri); val ti = uris.indexOf(toUri)
                                                                if (fi >= 0 && ti >= 0) {
                                                                    val removed = uris.removeAt(fi); uris.add(ti, removed)
                                                                    val updated = playlists.map { if (it.id == selectedPlaylistId) it.copy(trackUris = uris) else it }
                                                                    playlists = updated; AppPreferences.savePlaylists(context, updated)
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
                                .background(if (isCurrent) GreenPrimary.copy(alpha = 0.1f) else if (isDraggingThis) GreenPrimary.copy(alpha = 0.05f) else Color.Transparent, RoundedCornerShape(6.dp))
                                .onGloballyPositioned { coords -> rowPos = coords.positionInRoot() }
                                .then(dragModifier)
                                .clickable {
                                    val qi = LocalMediaState.playQueue.indexOfFirst { it.uri == track.uri }
                                    if (qi >= 0) LocalMediaState.playTrack(qi)
                                    else { LocalMediaState.loadAndPlayPlaylist(displayedTracks); LocalMediaState.playTrack(index) }
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (isCurrent) "▶" else "${index + 1}.", color = if (isCurrent) GreenPrimary else GreenPrimary.copy(alpha = 0.4f), fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(24.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(track.title, color = GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(track.artist, color = GreenPrimary.copy(alpha = 0.7f), fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                // Queue button with ✓ feedback
                                IconButton(onClick = { LocalMediaState.addToQueue(track); recentlyQueuedUri = track.uri.toString() }, modifier = Modifier.size(28.dp)) {
                                    Icon(if (isJustQueued) Icons.Default.Check else Icons.Default.QueueMusic,
                                        contentDescription = if (isJustQueued) "Added!" else "Queue",
                                        tint = if (isJustQueued) GreenPrimary else GreenPrimary.copy(alpha = 0.7f),
                                        modifier = Modifier.size(18.dp))
                                }
                                // Edit track info button
                                IconButton(onClick = { editingTrack = track }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit Track", tint = GreenPrimary.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                                }
                                // Remove from playlist (playlist view only)
                                if (selectedPlaylistId != ALL_TRACKS_ID) {
                                    IconButton(onClick = {
                                        val updated = playlists.map { pl ->
                                            if (pl.id == selectedPlaylistId) pl.copy(trackUris = pl.trackUris.filter { it != track.uri.toString() }) else pl
                                        }
                                        playlists = updated; AppPreferences.savePlaylists(context, updated)
                                    }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.Remove, contentDescription = "Remove from playlist", tint = GreenPrimary.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Media player at bottom of track column
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = GreenPrimary.copy(alpha = 0.3f))
            Spacer(Modifier.height(6.dp))
            CompactLocalPlayer()
        }

        // ── RIGHT: Playlists OR Queue ─────────────────────────────────────────
        Column(modifier = Modifier.weight(1f).fillMaxHeight()
            .background(BrownMid.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .border(1.dp, GreenPrimary, RoundedCornerShape(10.dp))
            .padding(10.dp)) {

            if (showTrackQueue) {
                // ── Queue view (manual queue only) ────────────────────────────
                Text("Queue", color = GreenPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 8.dp))
                HorizontalDivider(color = GreenPrimary.copy(alpha = 0.3f))
                Spacer(Modifier.height(8.dp))
                if (LocalMediaState.manualQueue.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No songs queued.\nTap the queue icon on any track to add it.", color = GreenPrimary.copy(alpha = 0.45f), fontSize = 12.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(LocalMediaState.manualQueue, key = { i, _ -> i }) { i, track ->
                            Row(modifier = Modifier.fillMaxWidth()
                                .border(1.dp, GreenPrimary.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${i + 1}.", color = GreenPrimary.copy(alpha = 0.5f), fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(20.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(track.title, color = GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(track.artist, color = GreenPrimary.copy(alpha = 0.7f), fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = { LocalMediaState.removeFromQueue(i) }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove", tint = GreenPrimary.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            } else {
                // ── Playlist grid ──────────────────────────────────────────────
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Playlists", color = GreenPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    // Half-height + New button
                    Box(modifier = Modifier.height(18.dp)
                        .border(1.dp, GreenPrimary, RoundedCornerShape(4.dp))
                        .clickable { showCreatePlaylist = true }
                        .padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                        Text("+ New", color = GreenPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(gridColumns.coerceIn(1, 5)),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(playlists, key = { _, pl -> pl.id }) { _, playlist ->
                        val isAllTracks = playlist.id == ALL_TRACKS_ID
                        var isRenaming by remember { mutableStateOf(false) }
                        var renameText by remember(playlist.name) { mutableStateOf(playlist.name) }
                        val isHov = hoveredPlaylistId == playlist.id
                        val isSel = selectedPlaylistId == playlist.id
                        val cardScale by animateFloatAsState(when { isHov -> 1.06f; isSel -> 1.02f; else -> 1f }, spring(0.5f, 500f), label = "cs")

                        val editCoverLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                            uri?.let {
                                try { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) {}
                                val newCoverUri = it.toString()
                                val updated = playlists.map { pl ->
                                    if (pl.id == playlist.id) pl.copy(coverUri = newCoverUri, coverOffsetX = 0f, coverOffsetY = 0f, coverScale = 1f) else pl
                                }
                                playlists = updated; AppPreferences.savePlaylists(context, updated)
                            }
                        }

                        // Compact card — single border around cover + info row
                        Column(modifier = Modifier.scale(cardScale)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isHov) GreenPrimary.copy(alpha = 0.15f) else if (isSel) GreenPrimary.copy(alpha = 0.1f) else BrownMid.copy(alpha = 0.6f))
                            .border(1.dp, if (isHov || isSel) GreenPrimary else GreenPrimary.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .onGloballyPositioned { coords ->
                                val pos = coords.positionInRoot()
                                playlistBounds[playlist.id] = androidx.compose.ui.geometry.Rect(pos.x, pos.y, pos.x + coords.size.width, pos.y + coords.size.height)
                            }
                            .clickable { if (!isRenaming) selectedPlaylistId = playlist.id }
                        ) {
                            // Cover — square, with "Edit Cover" overlay when renaming
                            Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
                                PlaylistCoverView(playlist = playlist, modifier = Modifier.fillMaxSize())
                                if (isRenaming) {
                                    Box(modifier = Modifier.fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.55f))
                                        .clickable { editCoverLauncher.launch(arrayOf("image/*", "video/*")) },
                                        contentAlignment = Alignment.Center) {
                                        Text("Edit Cover", color = GreenPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            // Info row — name + count on same line + buttons
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                // Name + count side by side
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
                                        val count = if (isAllTracks) LocalMediaState.tracks.size else playlist.trackUris.size
                                        Text("$count", color = GreenPrimary.copy(alpha = 0.55f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                    }
                                }
                                // Buttons
                                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                                    if (isRenaming) {
                                        IconButton(onClick = {
                                            val u = playlists.map { if (it.id == playlist.id) it.copy(name = renameText) else it }
                                            playlists = u; AppPreferences.savePlaylists(context, u); isRenaming = false
                                        }, modifier = Modifier.size(20.dp)) {
                                            Icon(Icons.Default.Check, "Done", tint = GreenPrimary, modifier = Modifier.size(12.dp))
                                        }
                                    } else {
                                        IconButton(onClick = { isRenaming = true }, modifier = Modifier.size(20.dp)) {
                                            Icon(Icons.Default.Edit, "Rename", tint = GreenPrimary.copy(alpha = 0.7f), modifier = Modifier.size(12.dp))
                                        }
                                        if (!isAllTracks) {
                                            IconButton(onClick = {
                                                val u = playlists.filter { it.id != playlist.id }
                                                playlists = u; AppPreferences.savePlaylists(context, u)
                                                if (selectedPlaylistId == playlist.id) selectedPlaylistId = ALL_TRACKS_ID
                                            }, modifier = Modifier.size(20.dp)) {
                                                Icon(Icons.Default.Delete, "Delete", tint = GreenPrimary.copy(alpha = 0.7f), modifier = Modifier.size(12.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Grid size slider
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("Size:", color = GreenPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Slider(value = gridColumns.toFloat(), onValueChange = { gridColumns = it.toInt() }, valueRange = 1f..5f, steps = 3, modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = GreenPrimary, activeTrackColor = GreenPrimary, inactiveTrackColor = GreenPrimary.copy(alpha = 0.3f)))
                }
            }
        }
    }

    // Drag ghost
    if (draggingTrack != null) {
        Box(modifier = Modifier.fillMaxSize().zIndex(99f)) {
            Box(modifier = Modifier.offset { IntOffset(dragPosition.x.roundToInt() - 20, dragPosition.y.roundToInt() - 24) }
                .widthIn(max = 260.dp)
                .background(BrownMid.copy(alpha = 0.95f), RoundedCornerShape(6.dp))
                .border(1.dp, GreenPrimary, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp)) {
                Column {
                    Text(draggingTrack!!.title, color = GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(draggingTrack!!.artist, color = GreenPrimary.copy(alpha = 0.7f), fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    if (showCreatePlaylist) {
        CreatePlaylistDialog(
            onConfirm = { name, coverUri, offsetX, offsetY, scale ->
                val newPl = VirtualPlaylist(id = UUID.randomUUID().toString(), name = name, coverUri = coverUri, coverOffsetX = offsetX, coverOffsetY = offsetY, coverScale = scale)
                val updated = playlists + newPl
                playlists = updated; AppPreferences.savePlaylists(context, updated)
                showCreatePlaylist = false
            },
            onDismiss = { showCreatePlaylist = false }
        )
    }

    // Edit track dialog
    editingTrack?.let { track ->
        EditTrackDialog(
            track = track,
            onConfirm = { newTitle, newArtist ->
                // Save override
                val overrides = AppPreferences.loadTrackOverrides(context).toMutableMap()
                overrides[track.uri.toString()] = TrackOverride(newTitle, newArtist)
                AppPreferences.saveTrackOverrides(context, overrides)

                // Try to rename the actual document file
                var finalUri = track.uri
                try {
                    val displayName = context.contentResolver.query(track.uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: "track.mp3"
                    val ext = displayName.substringAfterLast('.', "mp3")
                    val newName = "$newTitle - $newArtist.$ext"
                    val renamed = DocumentsContract.renameDocument(context.contentResolver, track.uri, newName)
                    if (renamed != null) finalUri = renamed
                } catch (e: Exception) { e.printStackTrace() }

                val updatedTrack = track.copy(uri = finalUri, title = newTitle, artist = newArtist)
                LocalMediaState.updateTrackInfo(track.uri, updatedTrack)

                // Update playlists that referenced the old URI
                if (finalUri != track.uri) {
                    val updated = playlists.map { pl ->
                        pl.copy(trackUris = pl.trackUris.map { u -> if (u == track.uri.toString()) finalUri.toString() else u })
                    }
                    playlists = updated; AppPreferences.savePlaylists(context, updated)
                }
                editingTrack = null
            },
            onDismiss = { editingTrack = null }
        )
    }
}

// ── Green Pill Scrollbar (smooth scrollBy) ────────────────────────────────────
@Composable
fun GreenScrollbar(listState: LazyListState, modifier: Modifier = Modifier) {
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    val totalItems = listState.layoutInfo.totalItemsCount
    val visibleItems = listState.layoutInfo.visibleItemsInfo
    val visibleCount = visibleItems.size
    val scrollRange = (totalItems - visibleCount).coerceAtLeast(0)
    if (scrollRange == 0 || totalItems == 0) return

    var trackHeightPx by remember { mutableStateOf(1f) }
    val avgItemHeight = if (visibleItems.isNotEmpty()) visibleItems.map { it.size }.average().toFloat() else 60f
    val thumbFraction = (visibleCount.toFloat() / totalItems.toFloat()).coerceIn(0.06f, 0.88f)
    val scrollFraction = listState.firstVisibleItemIndex.toFloat() / scrollRange.toFloat()
    val thumbHeightPx = trackHeightPx * thumbFraction
    val thumbOffsetPx = (trackHeightPx - thumbHeightPx) * scrollFraction.coerceIn(0f, 1f)

    // Precompute scroll ratio for drag: how many list-pixels per scrollbar-pixel
    val totalScrollableListPx = (avgItemHeight * totalItems - trackHeightPx).coerceAtLeast(1f)
    val scrollableTrackPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(1f)
    val scrollRatio = totalScrollableListPx / scrollableTrackPx

    Box(modifier = modifier
        .width(14.dp)
        .onGloballyPositioned { trackHeightPx = it.size.height.toFloat().coerceAtLeast(1f) }
    ) {
        // Track background
        Box(Modifier.fillMaxSize().padding(horizontal = 3.dp).background(GreenPrimary.copy(alpha = 0.15f), RoundedCornerShape(4.dp)))

        val thumbH = with(density) { thumbHeightPx.toDp() }
        val thumbOff = with(density) { thumbOffsetPx.toDp() }

        // Thumb — wider for easier grabbing
        Box(Modifier
            .fillMaxWidth()
            .height(thumbH)
            .offset(y = thumbOff)
            .background(GreenPrimary, RoundedCornerShape(4.dp))
            .pointerInput(scrollRatio) {
                detectDragGestures { _, dragAmount ->
                    // scrollBy gives immediate pixel-level smooth scrolling
                    val listScrollDelta = dragAmount.y * scrollRatio
                    coroutineScope.launch { listState.scrollBy(listScrollDelta) }
                }
            }
        )
    }
}

// ── Playlist Cover View ───────────────────────────────────────────────────────
@Composable
fun PlaylistCoverView(playlist: VirtualPlaylist, modifier: Modifier) {
    val context = LocalContext.current

    // OSCRaccoon icon as default (item 10)
    val raccoonIcon: ImageBitmap? = remember {
        try { BitmapFactory.decodeStream(context.assets.open("osc_raccoon_icon.png"))?.asImageBitmap() } catch (e: Exception) { null }
    }

    if (playlist.coverUri.isEmpty()) {
        Box(modifier = modifier.background(BrownDark), contentAlignment = Alignment.Center) {
            if (raccoonIcon != null)
                Image(bitmap = raccoonIcon, contentDescription = null, modifier = Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
            else
                Text("🎵", fontSize = 22.sp)
        }
        return
    }

    val uri = remember(playlist.coverUri) { try { Uri.parse(playlist.coverUri) } catch (e: Exception) { null } }
    val mimeType = remember(playlist.coverUri) { try { uri?.let { context.contentResolver.getType(it) } ?: "" } catch (e: Exception) { "" } }

    when {
        mimeType.startsWith("video/") && uri != null -> {
            AndroidView(factory = { ctx -> VideoView(ctx).apply { setVideoURI(uri); setOnPreparedListener { mp -> mp.isLooping = true; mp.setVolume(0f, 0f); start() } } },
                onRelease = { it.stopPlayback() }, modifier = modifier)
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
                    if (raccoonIcon != null) Image(bitmap = raccoonIcon, contentDescription = null, modifier = Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
                    else Text("🎵", fontSize = 22.sp)
                }
            }
        }
        else -> {
            Box(modifier = modifier.background(BrownDark), contentAlignment = Alignment.Center) {
                if (raccoonIcon != null) Image(bitmap = raccoonIcon, contentDescription = null, modifier = Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
                else Text("🎵", fontSize = 22.sp)
            }
        }
    }
}

// ── Edit Track Dialog (saves to file) ────────────────────────────────────────
@Composable
fun EditTrackDialog(track: LocalTrack, onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(track.title) }
    var artist by remember { mutableStateOf(track.artist) }
    Dialog(onDismissRequest = onDismiss) {
        Box(modifier = Modifier.width(340.dp).clip(RoundedCornerShape(12.dp)).drawBehind { drawCheckerboard() }.border(2.dp, GreenPrimary, RoundedCornerShape(12.dp)).padding(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Edit Track Info", color = GreenPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text("Changes will rename the audio file.", color = GreenPrimary.copy(alpha = 0.6f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                HorizontalDivider(color = GreenPrimary.copy(alpha = 0.3f))
                Text("Title", color = GreenPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                BasicTextField(value = title, onValueChange = { title = it }, singleLine = true,
                    textStyle = TextStyle(color = GreenPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    cursorBrush = SolidColor(GreenPrimary),
                    modifier = Modifier.fillMaxWidth().border(1.dp, GreenPrimary, RoundedCornerShape(6.dp)).padding(10.dp))
                Text("Artist", color = GreenPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                BasicTextField(value = artist, onValueChange = { artist = it }, singleLine = true,
                    textStyle = TextStyle(color = GreenPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    cursorBrush = SolidColor(GreenPrimary),
                    modifier = Modifier.fillMaxWidth().border(1.dp, GreenPrimary, RoundedCornerShape(6.dp)).padding(10.dp))
                HorizontalDivider(color = GreenPrimary.copy(alpha = 0.3f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RaccoonButton(text = "Save", highlighted = true, onClick = { if (title.isNotBlank()) onConfirm(title.trim(), artist.trim()) })
                    RaccoonButton(text = "Cancel", onClick = onDismiss)
                }
            }
        }
    }
}

// ── Create Playlist Dialog (compact, cover-click to pick) ─────────────────────
@Composable
fun CreatePlaylistDialog(
    onConfirm: (name: String, coverUri: String, offsetX: Float, offsetY: Float, scale: Float) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var coverUri by remember { mutableStateOf("") }
    var coverMimeType by remember { mutableStateOf("") }
    var previewBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var coverOffsetX by remember { mutableStateOf(0f) }
    var coverOffsetY by remember { mutableStateOf(0f) }
    var coverScale by remember { mutableStateOf(1f) }

    val raccoonIcon: ImageBitmap? = remember {
        try { android.graphics.BitmapFactory.decodeStream(context.assets.open("osc_raccoon_icon.png"))?.asImageBitmap() } catch (e: Exception) { null }
    }

    val coverPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) {}
            coverUri = it.toString()
            coverMimeType = try { context.contentResolver.getType(it) ?: "" } catch (e: Exception) { "" }
            coverOffsetX = 0f; coverOffsetY = 0f; coverScale = 1f
            previewBitmap = if (coverMimeType.startsWith("image/") && coverMimeType != "image/gif") {
                try { context.contentResolver.openInputStream(it)?.use { s -> android.graphics.BitmapFactory.decodeStream(s)?.asImageBitmap() } } catch (e: Exception) { null }
            } else null
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(modifier = Modifier.width(260.dp).clip(RoundedCornerShape(12.dp)).drawBehind { drawCheckerboard() }.border(2.dp, GreenPrimary, RoundedCornerShape(12.dp)).padding(18.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {

                // Name field (no "Name:" label)
                BasicTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    textStyle = TextStyle(color = GreenPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    cursorBrush = SolidColor(GreenPrimary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().border(1.dp, GreenPrimary, RoundedCornerShape(6.dp)).padding(10.dp),
                    decorationBox = { inner ->
                        if (name.isEmpty()) Text("Playlist name...", color = GreenPrimary.copy(alpha = 0.4f), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        inner()
                    })

                // Cover square — clickable to pick (no separate button)
                Box(modifier = Modifier.size(140.dp).clip(RoundedCornerShape(8.dp))
                    .border(1.dp, GreenPrimary.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                    .clickable { coverPickerLauncher.launch(arrayOf("image/*", "video/*")) }) {
                    when {
                        coverUri.isEmpty() -> {
                            Box(Modifier.fillMaxSize().background(BrownDark), contentAlignment = Alignment.Center) {
                                if (raccoonIcon != null)
                                    Image(bitmap = raccoonIcon, contentDescription = null, modifier = Modifier.fillMaxSize().padding(16.dp), contentScale = ContentScale.Fit)
                                Text("Add Cover", color = GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
                            }
                        }
                        coverMimeType.startsWith("video/") -> {
                            val uri = remember(coverUri) { try { Uri.parse(coverUri) } catch (e: Exception) { null } }
                            if (uri != null) AndroidView(factory = { ctx -> VideoView(ctx).apply { setVideoURI(uri); setOnPreparedListener { mp -> mp.isLooping = true; mp.setVolume(0f, 0f); start() } } }, onRelease = { it.stopPlayback() }, modifier = Modifier.fillMaxSize())
                        }
                        coverMimeType == "image/gif" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                            val uri = remember(coverUri) { try { Uri.parse(coverUri) } catch (e: Exception) { null } }
                            if (uri != null) AndroidView(factory = { ctx ->
                                ImageView(ctx).apply {
                                    scaleType = ImageView.ScaleType.CENTER_CROP
                                    try { val src = ImageDecoder.createSource(ctx.contentResolver, uri); val d = ImageDecoder.decodeDrawable(src); setImageDrawable(d); (d as? AnimatedImageDrawable)?.start() } catch (e: Exception) {}
                                }
                            }, modifier = Modifier.fillMaxSize())
                        }
                        previewBitmap != null -> {
                            val bmp = previewBitmap!!
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val bW = bmp.width.toFloat().coerceAtLeast(1f); val bH = bmp.height.toFloat().coerceAtLeast(1f)
                                val cW = size.width; val cH = size.height
                                val base = maxOf(cW / bW, cH / bH) * coverScale.coerceAtLeast(1f)
                                val sW = (cW / base).coerceAtMost(bW); val sH = (cH / base).coerceAtMost(bH)
                                val cx = bW / 2f + coverOffsetX / base; val cy = bH / 2f + coverOffsetY / base
                                val sl = (cx - sW / 2f).coerceIn(0f, (bW - sW).coerceAtLeast(0f))
                                val st = (cy - sH / 2f).coerceIn(0f, (bH - sH).coerceAtLeast(0f))
                                drawImage(bmp, srcOffset = IntOffset(sl.toInt(), st.toInt()),
                                    srcSize = IntSize(sW.toInt().coerceAtLeast(1), sH.toInt().coerceAtLeast(1)),
                                    dstOffset = IntOffset.Zero, dstSize = IntSize(cW.toInt().coerceAtLeast(1), cH.toInt().coerceAtLeast(1)))
                            }
                        }
                        else -> Box(Modifier.fillMaxSize().background(BrownDark), contentAlignment = Alignment.Center) { Text("⚠️", fontSize = 20.sp) }
                    }
                }

                // Crop sliders (only for static images)
                if (previewBitmap != null) {
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Zoom", color = GreenPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Slider(value = coverScale, onValueChange = { coverScale = it }, valueRange = 1f..3f, modifier = Modifier.fillMaxWidth(),
                            colors = SliderDefaults.colors(thumbColor = GreenPrimary, activeTrackColor = GreenPrimary, inactiveTrackColor = GreenPrimary.copy(alpha = 0.3f)))
                        Text("Pan X", color = GreenPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Slider(value = coverOffsetX, onValueChange = { coverOffsetX = it }, valueRange = -600f..600f, modifier = Modifier.fillMaxWidth(),
                            colors = SliderDefaults.colors(thumbColor = GreenPrimary, activeTrackColor = GreenPrimary, inactiveTrackColor = GreenPrimary.copy(alpha = 0.3f)))
                        Text("Pan Y", color = GreenPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Slider(value = coverOffsetY, onValueChange = { coverOffsetY = it }, valueRange = -600f..600f, modifier = Modifier.fillMaxWidth(),
                            colors = SliderDefaults.colors(thumbColor = GreenPrimary, activeTrackColor = GreenPrimary, inactiveTrackColor = GreenPrimary.copy(alpha = 0.3f)))
                    }
                }

                HorizontalDivider(color = GreenPrimary.copy(alpha = 0.3f))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.weight(1f).height(36.dp).background(GreenPrimary, RoundedCornerShape(6.dp)).clickable { if (name.isNotBlank()) onConfirm(name.trim(), coverUri, coverOffsetX, coverOffsetY, coverScale) }, contentAlignment = Alignment.Center) {
                        Text("Create", color = BrownDark, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                    Box(modifier = Modifier.weight(1f).height(36.dp).border(1.dp, GreenPrimary, RoundedCornerShape(6.dp)).clickable { onDismiss() }, contentAlignment = Alignment.Center) {
                        Text("Cancel", color = GreenPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}
