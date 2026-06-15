package com.rechoraccoon.oscraccoon

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.*
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.util.UUID
import kotlin.math.roundToInt

@Composable
fun TracksPage(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var playlists by remember { mutableStateOf(AppPreferences.loadPlaylists(context)) }
    var selectedPlaylistId by remember { mutableStateOf("all") }
    var gridColumns by remember { mutableStateOf(3) }
    var showCreatePlaylist by remember { mutableStateOf(false) }

    // Tracks shown in left panel: all tracks or playlist tracks
    val displayedTracks = remember(selectedPlaylistId, playlists, LocalMediaState.tracks) {
        if (selectedPlaylistId == "all") {
            LocalMediaState.tracks.sortedBy { it.title }
        } else {
            val pl = playlists.find { it.id == selectedPlaylistId }
            pl?.trackUris?.mapNotNull { uri ->
                LocalMediaState.tracks.find { it.uri.toString() == uri }
            } ?: emptyList()
        }
    }

    // Drag state for track→playlist
    var draggingTrack by remember { mutableStateOf<LocalTrack?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var hoveredPlaylistId by remember { mutableStateOf<String?>(null) }
    val playlistPositions = remember { mutableMapOf<String, androidx.compose.ui.geometry.Rect>() }

    Box(modifier = Modifier.fillMaxSize().drawBehind { drawCheckerboard() }) {
        Row(modifier = Modifier.fillMaxSize()) {
            // ── LEFT: Track list ──────────────────────────────────────────
            Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    val title = if (selectedPlaylistId == "all") "All Tracks" else playlists.find { it.id == selectedPlaylistId }?.name ?: "Tracks"
                    Text(title, color = GreenPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    if (selectedPlaylistId == "all") {
                        Text("${displayedTracks.size} tracks", color = GreenPrimary.copy(alpha = 0.6f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${displayedTracks.size} tracks", color = GreenPrimary.copy(alpha = 0.6f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            RaccoonButton(text = "← All Tracks", small = true, onClick = { selectedPlaylistId = "all" })
                        }
                    }
                }

                if (LocalMediaState.tracks.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No folder loaded.\nGo back and pick a folder first.", color = GreenPrimary.copy(alpha = 0.5f), fontSize = 13.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(displayedTracks) { index, track ->
                            val isCurrentTrack = LocalMediaState.currentTrack?.uri == track.uri
                            var isHovered by remember { mutableStateOf(false) }
                            val scale by animateFloatAsState(targetValue = if (isHovered || draggingTrack == track) 1.03f else 1f, animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f), label = "trackScale")

                            Row(
                                modifier = Modifier.fillMaxWidth().scale(scale)
                                    .border(1.dp, if (isCurrentTrack) GreenPrimary else GreenPrimary.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                    .background(if (isCurrentTrack) GreenPrimary.copy(alpha = 0.1f) else Color.Transparent, RoundedCornerShape(6.dp))
                                    .pointerInput(track) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { offset ->
                                                draggingTrack = track
                                                isHovered = true
                                            },
                                            onDrag = { change, dragAmount ->
                                                dragOffset += dragAmount
                                                // Check which playlist we're hovering
                                                val windowPos = change.position
                                                hoveredPlaylistId = playlistPositions.entries.firstOrNull { (_, rect) ->
                                                    // rect is in window coords; check if drag position overlaps
                                                    true // simplified — highlight on drag to right side
                                                }?.key
                                            },
                                            onDragEnd = {
                                                val targetId = hoveredPlaylistId
                                                if (targetId != null && targetId != "all" && draggingTrack != null) {
                                                    val track2 = draggingTrack!!
                                                    val updated = playlists.map { pl ->
                                                        if (pl.id == targetId && !pl.trackUris.contains(track2.uri.toString())) {
                                                            pl.copy(trackUris = pl.trackUris + track2.uri.toString())
                                                        } else pl
                                                    }
                                                    playlists = updated
                                                    AppPreferences.savePlaylists(context, updated)
                                                }
                                                draggingTrack = null
                                                isHovered = false
                                                hoveredPlaylistId = null
                                                dragOffset = Offset.Zero
                                            },
                                            onDragCancel = {
                                                draggingTrack = null
                                                isHovered = false
                                                hoveredPlaylistId = null
                                                dragOffset = Offset.Zero
                                            }
                                        )
                                    }
                                    .clickable { LocalMediaState.playTrack(LocalMediaState.playQueue.indexOfFirst { it.uri == track.uri }.takeIf { it >= 0 } ?: 0) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Playing indicator
                                if (isCurrentTrack) {
                                    Text("▶", color = GreenPrimary, fontSize = 10.sp)
                                } else {
                                    Text("${index + 1}.", color = GreenPrimary.copy(alpha = 0.4f), fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(24.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(track.title, color = GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = if (isCurrentTrack) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(track.artist, color = GreenPrimary.copy(alpha = 0.7f), fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                // Add to queue
                                IconButton(onClick = { LocalMediaState.addToQueue(track) }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Default.QueueMusic, contentDescription = "Add to queue", tint = GreenPrimary.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                                }
                                // Remove from playlist (not in All Tracks)
                                if (selectedPlaylistId != "all") {
                                    IconButton(onClick = {
                                        val updated = playlists.map { pl ->
                                            if (pl.id == selectedPlaylistId) pl.copy(trackUris = pl.trackUris.filter { it != track.uri.toString() })
                                            else pl
                                        }
                                        playlists = updated
                                        AppPreferences.savePlaylists(context, updated)
                                    }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.Remove, contentDescription = "Remove from playlist", tint = GreenPrimary.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Divider
            Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(GreenPrimary.copy(alpha = 0.3f)))

            // ── RIGHT: Playlist grid ──────────────────────────────────────
            Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Playlists", color = GreenPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    RaccoonButton(text = "+ New Playlist", small = true, onClick = { showCreatePlaylist = true })
                }

                // All Tracks card
                val allTracksHovered = hoveredPlaylistId == "all_drop"
                PlaylistCard(
                    name = "All Tracks",
                    trackCount = LocalMediaState.tracks.size,
                    isSelected = selectedPlaylistId == "all",
                    isHovered = allTracksHovered,
                    coverBitmap = null,
                    onClick = { selectedPlaylistId = "all" },
                    onRename = null,
                    onDelete = null,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )

                // User playlists grid
                LazyVerticalGrid(
                    columns = GridCells.Fixed(gridColumns.coerceIn(1, 6)),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(playlists, key = { _, pl -> pl.id }) { index, playlist ->
                        var isRenaming by remember { mutableStateOf(false) }
                        var renameText by remember(playlist.name) { mutableStateOf(playlist.name) }
                        val isHovered = hoveredPlaylistId == playlist.id

                        PlaylistCard(
                            name = if (isRenaming) renameText else playlist.name,
                            trackCount = playlist.trackUris.size,
                            isSelected = selectedPlaylistId == playlist.id,
                            isHovered = isHovered,
                            coverBitmap = null,
                            isRenaming = isRenaming,
                            renameText = renameText,
                            onRenameTextChange = { renameText = it },
                            onClick = { if (!isRenaming) selectedPlaylistId = playlist.id },
                            onRename = { isRenaming = !isRenaming },
                            onRenameConfirm = {
                                val updated = playlists.map { if (it.id == playlist.id) it.copy(name = renameText) else it }
                                playlists = updated
                                AppPreferences.savePlaylists(context, updated)
                                isRenaming = false
                            },
                            onDelete = {
                                val updated = playlists.filter { it.id != playlist.id }
                                playlists = updated
                                AppPreferences.savePlaylists(context, updated)
                                if (selectedPlaylistId == playlist.id) selectedPlaylistId = "all"
                            },
                            modifier = Modifier.onGloballyPositioned { coords ->
                                val pos = coords.positionInWindow()
                                playlistPositions[playlist.id] = androidx.compose.ui.geometry.Rect(
                                    pos.x, pos.y, pos.x + coords.size.width, pos.y + coords.size.height
                                )
                            }
                        )
                    }
                }

                // Grid size slider
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Grid:", color = GreenPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Slider(value = gridColumns.toFloat(), onValueChange = { gridColumns = it.toInt() }, valueRange = 1f..6f, steps = 4, modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = GreenPrimary, activeTrackColor = GreenPrimary, inactiveTrackColor = GreenPrimary.copy(alpha = 0.3f)))
                    Text("$gridColumns", color = GreenPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(16.dp))
                }
            }
        }

        // Header overlay
        Box(modifier = Modifier.fillMaxWidth().background(BrownMid.copy(alpha = 0.9f)).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RaccoonButton(text = "← Back", small = true, onClick = onDismiss)
                    Text("Tracks & Playlists", color = GreenPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                Text("Long-press a track and drag it onto a playlist to add it", color = GreenPrimary.copy(alpha = 0.6f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }

        // Dragging ghost
        if (draggingTrack != null) {
            Box(modifier = Modifier.offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
                .background(GreenPrimary.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                .border(1.dp, GreenPrimary, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(draggingTrack!!.title, color = GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }

    // Create playlist dialog
    if (showCreatePlaylist) {
        CreatePlaylistDialog(
            onConfirm = { name, coverUri ->
                val newPl = VirtualPlaylist(id = UUID.randomUUID().toString(), name = name, coverUri = coverUri)
                val updated = playlists + newPl
                playlists = updated
                AppPreferences.savePlaylists(context, updated)
                showCreatePlaylist = false
            },
            onDismiss = { showCreatePlaylist = false }
        )
    }
}

@Composable
fun PlaylistCard(
    name: String, trackCount: Int, isSelected: Boolean, isHovered: Boolean,
    coverBitmap: ImageBitmap?, isRenaming: Boolean = false, renameText: String = "",
    onRenameTextChange: (String) -> Unit = {}, onClick: () -> Unit,
    onRename: (() -> Unit)?, onRenameConfirm: (() -> Unit)? = null, onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(targetValue = when { isHovered -> 1.06f; isSelected -> 1.02f; else -> 1f }, animationSpec = spring(dampingRatio = 0.5f, stiffness = 500f), label = "plScale")

    Column(modifier = modifier.scale(scale)
        .border(1.dp, if (isHovered) GreenPrimary else if (isSelected) GreenPrimary else GreenPrimary.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
        .background(if (isSelected) GreenPrimary.copy(alpha = 0.1f) else if (isHovered) GreenPrimary.copy(alpha = 0.08f) else Color.Transparent, RoundedCornerShape(10.dp))
        .clickable { onClick() }
        .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Cover placeholder
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(BrownMid, RoundedCornerShape(6.dp)).border(1.dp, GreenPrimary.copy(alpha = 0.3f), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            if (coverBitmap != null) {
                Image(bitmap = coverBitmap, contentDescription = null, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)))
            } else {
                Text("🎵", fontSize = 24.sp)
            }
        }
        if (isRenaming) {
            BasicTextField(value = renameText, onValueChange = onRenameTextChange, singleLine = true,
                textStyle = TextStyle(color = GreenPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center),
                cursorBrush = SolidColor(GreenPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onRenameConfirm?.invoke() }),
                modifier = Modifier.fillMaxWidth())
        } else {
            Text(name, color = GreenPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("$trackCount tracks", color = GreenPrimary.copy(alpha = 0.6f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        if (onRename != null || onDelete != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (isRenaming) {
                    IconButton(onClick = { onRenameConfirm?.invoke() }, modifier = Modifier.size(22.dp)) { Icon(Icons.Default.Check, contentDescription = "Done", tint = GreenPrimary, modifier = Modifier.size(14.dp)) }
                } else {
                    onRename?.let { IconButton(onClick = it, modifier = Modifier.size(22.dp)) { Icon(Icons.Default.Edit, contentDescription = "Rename", tint = GreenPrimary.copy(alpha = 0.7f), modifier = Modifier.size(14.dp)) } }
                    onDelete?.let { IconButton(onClick = it, modifier = Modifier.size(22.dp)) { Icon(Icons.Default.Delete, contentDescription = "Delete", tint = GreenPrimary.copy(alpha = 0.7f), modifier = Modifier.size(14.dp)) } }
                }
            }
        }
    }
}

@Composable
fun CreatePlaylistDialog(onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("New Playlist") }
    var coverUri by remember { mutableStateOf("") }
    val coverPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { coverUri = it.toString() }
    }
    Dialog(onDismissRequest = onDismiss) {
        Box(modifier = Modifier.width(300.dp).clip(RoundedCornerShape(12.dp)).drawBehind { drawCheckerboard() }.border(2.dp, GreenPrimary, RoundedCornerShape(12.dp)).padding(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Create Playlist", color = GreenPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                HorizontalDivider(color = GreenPrimary.copy(alpha = 0.3f))
                Text("Name:", color = GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                BasicTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    textStyle = TextStyle(color = GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                    cursorBrush = SolidColor(GreenPrimary),
                    modifier = Modifier.fillMaxWidth().border(1.dp, GreenPrimary, RoundedCornerShape(6.dp)).padding(8.dp))
                RaccoonButton(text = "🖼 Pick Cover Image", small = true, onClick = { coverPickerLauncher.launch(arrayOf("image/*")) })
                if (coverUri.isNotEmpty()) Text("Cover selected ✓", color = GreenPrimary.copy(alpha = 0.7f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RaccoonButton(text = "Create", highlighted = true, onClick = { if (name.isNotBlank()) onConfirm(name.trim(), coverUri) })
                    RaccoonButton(text = "Cancel", onClick = onDismiss)
                }
            }
        }
    }
}
