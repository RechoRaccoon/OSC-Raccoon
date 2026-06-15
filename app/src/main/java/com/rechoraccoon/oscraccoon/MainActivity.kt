package com.rechoraccoon.oscraccoon

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.util.UUID

val GreenPrimary = Color(0xFF00FF07)
val BrownDark    = Color(0xFF5C3317)
val BrownLight   = Color(0xFF7A4A25)
val BrownMid     = Color(0xFF6B3D1E)

data class LocalTrack(val uri: Uri, val title: String, val artist: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocalMediaState.init(this)
        val savedUsername = AppPreferences.loadUsername(this)
        if (savedUsername.isNotEmpty()) LastFmService.startPolling(savedUsername)
        // Restore local folder
        val folderUri = AppPreferences.loadLocalFolderUri(this)
        if (folderUri.isNotEmpty()) {
            try {
                val tracks = loadTracksFromFolder(this, Uri.parse(folderUri))
                LocalMediaState.loadTracks(tracks)
            } catch (e: Exception) { e.printStackTrace() }
        }
        setContent { OSCRaccoonApp() }
    }
    override fun onDestroy() {
        super.onDestroy()
        LastFmService.stopPolling()
        LocalMediaState.release()
    }
}

fun DrawScope.drawCheckerboard(colorA: Color = BrownDark, colorB: Color = BrownLight, cellSize: Float = 24f) {
    val cols = (size.width / cellSize).toInt() + 1
    val rows = (size.height / cellSize).toInt() + 1
    for (row in 0..rows) for (col in 0..cols)
        drawRect(if ((row + col) % 2 == 0) colorA else colorB, Offset(col * cellSize, row * cellSize), Size(cellSize, cellSize))
}

fun loadTracksFromFolder(context: android.content.Context, folderUri: Uri): List<LocalTrack> {
    val tracks = mutableListOf<LocalTrack>()
    try {
        val docId = DocumentsContract.getTreeDocumentId(folderUri)
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, docId)
        val cursor = context.contentResolver.query(childUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null) ?: return tracks
        cursor.use { c ->
            while (c.moveToNext()) {
                val mime = c.getString(1) ?: continue
                if (!mime.startsWith("audio/")) continue
                val id = c.getString(0) ?: continue
                val fileUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, id)
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(context, fileUri)
                    val title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: c.getString(2) ?: "Unknown"
                    val artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unknown"
                    tracks.add(LocalTrack(fileUri, title, artist))
                } catch (e: Exception) { } finally { mmr.release() }
            }
        }
    } catch (e: Exception) { e.printStackTrace() }
    return tracks
}

@Composable
fun OSCRaccoonApp() {
    val context = LocalContext.current
    var lastFmUsername by remember { mutableStateOf(AppPreferences.loadUsername(context)) }
    var messageTemplate by remember { mutableStateOf(AppPreferences.loadTemplate(context)) }
    var cyclingMessages by remember { mutableStateOf(AppPreferences.loadCyclingMessages(context)) }
    var cycleInterval by remember { mutableStateOf(AppPreferences.loadInterval(context)) }
    var sourceMode by remember { mutableStateOf(AppPreferences.loadSourceMode(context)) }
    var presets by remember { mutableStateOf(AppPreferences.loadPresets(context)) }
    var isRunning by remember { mutableStateOf(false) }
    var showSetup by remember { mutableStateOf(false) }
    var nowPlaying by remember { mutableStateOf(NowPlaying()) }
    var previewCycleIndex by remember { mutableStateOf(0) }
    var isRandom by remember { mutableStateOf(false) }
    var showIconOverlay by remember { mutableStateOf(false) }
    var showPresetsDropdown by remember { mutableStateOf(false) }
    var showTracksPage by remember { mutableStateOf(false) }
    val random = remember { java.util.Random() }

    val folderPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            AppPreferences.saveLocalFolderUri(context, it.toString())
            val tracks = loadTracksFromFolder(context, it)
            LocalMediaState.loadTracks(tracks)
            LocalMediaState.playQueue.clear()
            LocalMediaState.playQueue.addAll(tracks)
        }
    }

    val visibleMessages = remember(cyclingMessages) { cyclingMessages.filter { !it.isHidden }.map { it.text } }

    LaunchedEffect(Unit) { LastFmService.nowPlaying.collectLatest { nowPlaying = it } }

    // Position polling for local player
    LaunchedEffect(Unit) {
        while (true) { LocalMediaState.updatePosition(); delay(500L) }
    }

    LaunchedEffect(visibleMessages, cycleInterval, isRandom) {
        while (true) {
            delay(cycleInterval * 1000L)
            if (visibleMessages.isNotEmpty()) {
                previewCycleIndex = if (isRandom && visibleMessages.size > 1) {
                    var next: Int; do { next = random.nextInt(visibleMessages.size) } while (next == previewCycleIndex); next
                } else (previewCycleIndex + 1) % visibleMessages.size
            }
        }
    }

    val currentCycling = if (visibleMessages.isNotEmpty()) visibleMessages[previewCycleIndex.coerceIn(0, (visibleMessages.size - 1).coerceAtLeast(0))] else ""
    val livePreview = OscForegroundService.formatTemplate(messageTemplate, nowPlaying, currentCycling)

    LaunchedEffect(messageTemplate, visibleMessages, cycleInterval) {
        if (isRunning) {
            context.startForegroundService(Intent(context, OscForegroundService::class.java).apply {
                action = OscForegroundService.ACTION_UPDATE
                putExtra(OscForegroundService.EXTRA_MAIN_TEMPLATE, messageTemplate)
                putStringArrayListExtra(OscForegroundService.EXTRA_CYCLING_MESSAGES, ArrayList(visibleMessages))
                putExtra(OscForegroundService.EXTRA_CYCLE_INTERVAL, cycleInterval)
            })
        }
    }

    Box(modifier = Modifier.fillMaxSize().drawBehind { drawCheckerboard() }) {
        if (showTracksPage) {
            TracksPage(onDismiss = { showTracksPage = false })
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                HeaderBar(
                    isRunning = isRunning, lastFmUsername = lastFmUsername, sourceMode = sourceMode,
                    onSourceModeChange = { mode ->
                        if (mode == sourceMode && mode == "LASTFM") { showSetup = true; return@HeaderBar }
                        sourceMode = mode; AppPreferences.saveSourceMode(context, mode)
                        if (mode == "LASTFM" && lastFmUsername.isNotEmpty()) LastFmService.startPolling(lastFmUsername)
                        else if (mode == "LOCAL") LastFmService.stopPolling()
                    },
                    onPresetsClick = { showPresetsDropdown = !showPresetsDropdown },
                    onIconClick = { showIconOverlay = true },
                    onStartStop = {
                        if (isRunning) { context.stopService(Intent(context, OscForegroundService::class.java)); isRunning = false }
                        else {
                            context.startForegroundService(Intent(context, OscForegroundService::class.java).apply {
                                action = OscForegroundService.ACTION_START
                                putExtra(OscForegroundService.EXTRA_MAIN_TEMPLATE, messageTemplate)
                                putStringArrayListExtra(OscForegroundService.EXTRA_CYCLING_MESSAGES, ArrayList(visibleMessages))
                                putExtra(OscForegroundService.EXTRA_CYCLE_INTERVAL, cycleInterval)
                            }); isRunning = true
                        }
                    },
                    onClearChatbox = { OscSender.clearChatbox() }
                )

                // Presets dropdown
                if (showPresetsDropdown) {
                    PresetsDropdown(
                        presets = presets,
                        onSelect = { preset ->
                            messageTemplate = preset.template
                            cyclingMessages = preset.messages
                            AppPreferences.saveTemplate(context, preset.template)
                            AppPreferences.saveCyclingMessages(context, preset.messages)
                            showPresetsDropdown = false
                        },
                        onCreateNew = {
                            val newPreset = Preset(UUID.randomUUID().toString(), "Preset ${presets.size + 1}", messageTemplate, cyclingMessages)
                            val updated = presets + newPreset
                            presets = updated
                            AppPreferences.savePresets(context, updated)
                        },
                        onRename = { preset, newName ->
                            val updated = presets.map { if (it.id == preset.id) it.copy(name = newName) else it }
                            presets = updated; AppPreferences.savePresets(context, updated)
                        },
                        onDelete = { preset ->
                            val updated = presets.filter { it.id != preset.id }
                            presets = updated; AppPreferences.savePresets(context, updated)
                        },
                        onDismiss = { showPresetsDropdown = false }
                    )
                }

                Row(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LeftPanel(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        messageTemplate = messageTemplate,
                        onTemplateChange = { messageTemplate = it; AppPreferences.saveTemplate(context, it) },
                        nowPlaying = nowPlaying, livePreview = livePreview,
                        lastFmUsername = lastFmUsername, sourceMode = sourceMode,
                        onSetupClick = { showSetup = true },
                        onPickFolder = { folderPickerLauncher.launch(null) },
                        onShowTracks = { showTracksPage = true }
                    )
                    RightPanel(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        cyclingMessages = cyclingMessages, cycleInterval = cycleInterval,
                        isRandom = isRandom,
                        onRandomChange = { isRandom = it; OscForegroundService.randomCycling = it },
                        onMessagesChange = { cyclingMessages = it; AppPreferences.saveCyclingMessages(context, it) },
                        onIntervalChange = { cycleInterval = it; AppPreferences.saveInterval(context, it) }
                    )
                }
            }

            if (showSetup) {
                LastFmSetupDialog(
                    currentUsername = lastFmUsername,
                    onConfirm = { u -> lastFmUsername = u; AppPreferences.saveUsername(context, u); LastFmService.startPolling(u); showSetup = false },
                    onDismiss = { showSetup = false }
                )
            }

            if (showIconOverlay) {
                val icon: ImageBitmap? = remember {
                    try { BitmapFactory.decodeStream(context.assets.open("osc_raccoon_icon.png"))?.asImageBitmap() } catch (e: Exception) { null }
                }
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)).clickable { showIconOverlay = false }, contentAlignment = Alignment.Center) {
                    if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.fillMaxHeight(), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                }
            }
        }
    }
}

// ── Header ────────────────────────────────────────────────────────────────────
@Composable
fun HeaderBar(isRunning: Boolean, lastFmUsername: String, sourceMode: String,
    onSourceModeChange: (String) -> Unit, onPresetsClick: () -> Unit,
    onIconClick: () -> Unit, onStartStop: () -> Unit, onClearChatbox: () -> Unit) {
    val context = LocalContext.current
    val icon: ImageBitmap? = remember {
        try { BitmapFactory.decodeStream(context.assets.open("osc_raccoon_icon.png"))?.asImageBitmap() } catch (e: Exception) { null }
    }
    Row(modifier = Modifier.fillMaxWidth().background(BrownMid.copy(alpha = 0.85f)).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(36.dp).clickable { onIconClick() })
            Text("OSCRaccoon by Recho Raccoon", color = GreenPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            RaccoonButton(text = "Recho's Socials", small = true, onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://guns.lol/rechoraccoon"))) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RaccoonButton(text = "▾ Presets", small = true, onClick = onPresetsClick)
            // Source toggle — tapping active LastFM button opens setup
            Row {
                Box(modifier = Modifier.height(36.dp)
                    .background(if (sourceMode == "LASTFM") GreenPrimary else Color.Transparent, RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                    .border(1.dp, GreenPrimary, RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                    .clickable { onSourceModeChange("LASTFM") }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text("Last.fm", color = if (sourceMode == "LASTFM") BrownDark else GreenPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                Box(modifier = Modifier.height(36.dp)
                    .background(if (sourceMode == "LOCAL") GreenPrimary else Color.Transparent, RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                    .border(1.dp, GreenPrimary, RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                    .clickable { onSourceModeChange("LOCAL") }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text("Local", color = if (sourceMode == "LOCAL") BrownDark else GreenPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }
            RaccoonButton(text = "Clear Chatbox", onClick = onClearChatbox)
            RaccoonButton(text = if (isRunning) "■ Stop" else "▶ Start", onClick = onStartStop, highlighted = !isRunning)
        }
    }
}

// ── Presets Dropdown ──────────────────────────────────────────────────────────
@Composable
fun PresetsDropdown(presets: List<Preset>, onSelect: (Preset) -> Unit, onCreateNew: () -> Unit,
    onRename: (Preset, String) -> Unit, onDelete: (Preset) -> Unit, onDismiss: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.align(Alignment.TopEnd).padding(end = 16.dp).width(220.dp)
            .clip(RoundedCornerShape(8.dp)).drawBehind { drawCheckerboard() }
            .border(1.dp, GreenPrimary, RoundedCornerShape(8.dp)).padding(8.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (presets.isEmpty()) {
                    Text("No presets saved.", color = GreenPrimary.copy(alpha = 0.6f), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(4.dp))
                } else {
                    presets.forEach { preset ->
                        var isRenaming by remember { mutableStateOf(false) }
                        var renameText by remember(preset.name) { mutableStateOf(preset.name) }
                        Row(modifier = Modifier.fillMaxWidth()
                            .border(1.dp, GreenPrimary.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                            .clickable { if (!isRenaming) onSelect(preset) }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (isRenaming) {
                                BasicTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true,
                                    textStyle = TextStyle(color = GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                                    cursorBrush = SolidColor(GreenPrimary),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { onRename(preset, renameText); isRenaming = false }),
                                    modifier = Modifier.weight(1f))
                                IconButton(onClick = { onRename(preset, renameText); isRenaming = false }, modifier = Modifier.size(22.dp)) {
                                    Icon(Icons.Default.Check, contentDescription = "Save", tint = GreenPrimary, modifier = Modifier.size(14.dp))
                                }
                            } else {
                                Text(preset.name, color = GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                IconButton(onClick = { isRenaming = true }, modifier = Modifier.size(22.dp)) { Icon(Icons.Default.Edit, contentDescription = "Rename", tint = GreenPrimary.copy(alpha = 0.7f), modifier = Modifier.size(12.dp)) }
                                IconButton(onClick = { onDelete(preset) }, modifier = Modifier.size(22.dp)) { Icon(Icons.Default.Close, contentDescription = "Delete", tint = GreenPrimary.copy(alpha = 0.7f), modifier = Modifier.size(12.dp)) }
                            }
                        }
                    }
                }
                HorizontalDivider(color = GreenPrimary.copy(alpha = 0.3f))
                Box(modifier = Modifier.fillMaxWidth().border(1.dp, GreenPrimary, RoundedCornerShape(6.dp))
                    .clickable { onCreateNew() }.padding(horizontal = 8.dp, vertical = 5.dp)) {
                    Text("+ Create New Preset", color = GreenPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        // Dismiss when clicking outside
        Box(modifier = Modifier.fillMaxSize().clickable { onDismiss() })
    }
}

// ── Left Panel ────────────────────────────────────────────────────────────────
@Composable
fun LeftPanel(modifier: Modifier, messageTemplate: String, onTemplateChange: (String) -> Unit,
    nowPlaying: NowPlaying, livePreview: String, lastFmUsername: String, sourceMode: String,
    onSetupClick: () -> Unit, onPickFolder: () -> Unit, onShowTracks: () -> Unit) {
    var fieldValue by remember { mutableStateOf(TextFieldValue(messageTemplate)) }
    fun insertAtCursor(insert: String) {
        val cursor = fieldValue.selection.end.coerceIn(0, fieldValue.text.length)
        val newText = fieldValue.text.substring(0, cursor) + insert + fieldValue.text.substring(cursor)
        fieldValue = TextFieldValue(text = newText, selection = androidx.compose.ui.text.TextRange(cursor + insert.length))
        onTemplateChange(newText)
    }
    PanelCard(modifier = modifier, title = "Message Template") {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Tap:", color = GreenPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                listOf("{song}", "{artist}", "{duration}", "{cycling}", "{time}").forEach { p ->
                    Box(modifier = Modifier.border(1.dp, GreenPrimary.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).clickable { insertAtCursor(p) }.padding(horizontal = 5.dp, vertical = 1.dp)) {
                        Text(p, color = GreenPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            Box(modifier = Modifier.border(1.dp, GreenPrimary, RoundedCornerShape(4.dp)).clickable { insertAtCursor("\n") }.padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text("↵ New Line", color = GreenPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
        RaccoonTextAreaValue(value = fieldValue, onValueChange = { fieldValue = it; onTemplateChange(it.text) }, label = "Message template", modifier = Modifier.fillMaxWidth().height(90.dp))
        Spacer(Modifier.height(8.dp))
        SectionLabel("Now Playing" + if (sourceMode == "LASTFM") " (via Last.fm)" else " (Local)")
        NowPlayingCard(nowPlaying)
        Spacer(Modifier.height(8.dp))
        SectionLabel("Live Chatbox Preview")
        Box(modifier = Modifier.fillMaxWidth().weight(1f).border(1.dp, GreenPrimary, RoundedCornerShape(6.dp)).padding(10.dp)) {
            Text(livePreview.ifEmpty { "(empty)" }, color = GreenPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace, lineHeight = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(6.dp))
        // Bottom status bar: Last.fm status or Local player
        if (sourceMode == "LASTFM") {
            LastFmStatusBar(username = lastFmUsername, onSetupClick = onSetupClick)
        } else {
            CompactLocalPlayer(onPickFolder = onPickFolder, onShowTracks = onShowTracks)
        }
    }
}

// ── Last.fm Status Bar ────────────────────────────────────────────────────────
@Composable
fun LastFmStatusBar(username: String, onSetupClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().border(1.dp, GreenPrimary.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
        .clickable { onSetupClick() }.padding(horizontal = 10.dp, vertical = 6.dp)) {
        if (username.isEmpty()) {
            Text("Connect Last.fm account to sync Spotify →", color = GreenPrimary.copy(alpha = 0.7f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Last.fm connected: $username", color = GreenPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text("Change →", color = GreenPrimary.copy(alpha = 0.6f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// ── Compact Local Player ──────────────────────────────────────────────────────
@Composable
fun CompactLocalPlayer(onPickFolder: () -> Unit, onShowTracks: () -> Unit) {
    val track = LocalMediaState.currentTrack
    Column(modifier = Modifier.fillMaxWidth().border(1.dp, GreenPrimary, RoundedCornerShape(6.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Top row: track info + buttons
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(track?.title ?: "No track", color = GreenPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track?.artist ?: if (LocalMediaState.tracks.isEmpty()) "Pick a folder to load music" else "Ready", color = GreenPrimary.copy(alpha = 0.7f), fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            RaccoonButton(text = "Tracks", small = true, onClick = onShowTracks)
            RaccoonButton(text = "📁", small = true, onClick = onPickFolder)
        }

        // Seek bar + timestamp on same row
        if (LocalMediaState.durationMs > 0) {
            val progress = (LocalMediaState.positionMs.toFloat() / LocalMediaState.durationMs).coerceIn(0f, 1f)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val posMin = (LocalMediaState.positionMs / 1000) / 60; val posSec = (LocalMediaState.positionMs / 1000) % 60
                val durMin = (LocalMediaState.durationMs / 1000) / 60; val durSec = (LocalMediaState.durationMs / 1000) % 60
                Text("%d:%02d".format(posMin, posSec), color = GreenPrimary.copy(alpha = 0.7f), fontSize = 9.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(30.dp))
                Slider(value = progress, onValueChange = { LocalMediaState.seek((it * LocalMediaState.durationMs).toLong()) },
                    modifier = Modifier.weight(1f).height(20.dp),
                    colors = SliderDefaults.colors(thumbColor = GreenPrimary, activeTrackColor = GreenPrimary, inactiveTrackColor = GreenPrimary.copy(alpha = 0.3f)))
                Text("%d:%02d".format(durMin, durSec), color = GreenPrimary.copy(alpha = 0.7f), fontSize = 9.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(30.dp))
            }
        } else {
            Slider(value = 0f, onValueChange = {}, modifier = Modifier.fillMaxWidth().height(20.dp),
                colors = SliderDefaults.colors(thumbColor = GreenPrimary.copy(alpha = 0.3f), activeTrackColor = GreenPrimary.copy(alpha = 0.2f), inactiveTrackColor = GreenPrimary.copy(alpha = 0.1f)),
                enabled = false)
        }

        // Controls + shuffle/loop + volume all on one row
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            // Shuffle
            IconButton(onClick = { LocalMediaState.setShuffle(!LocalMediaState.isShuffle) }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Shuffle, contentDescription = "Shuffle", tint = if (LocalMediaState.isShuffle) GreenPrimary else GreenPrimary.copy(alpha = 0.35f), modifier = Modifier.size(18.dp))
            }
            // Prev
            IconButton(onClick = { LocalMediaState.prev() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Prev", tint = GreenPrimary, modifier = Modifier.size(24.dp))
            }
            // Play/Pause - always shown
            IconButton(onClick = { LocalMediaState.playPause() }, modifier = Modifier.size(40.dp)) {
                Icon(if (LocalMediaState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play/Pause", tint = GreenPrimary, modifier = Modifier.size(32.dp))
            }
            // Next
            IconButton(onClick = { LocalMediaState.next() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = GreenPrimary, modifier = Modifier.size(24.dp))
            }
            // Loop
            IconButton(onClick = { LocalMediaState.isLoop = !LocalMediaState.isLoop }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Repeat, contentDescription = "Loop", tint = if (LocalMediaState.isLoop) GreenPrimary else GreenPrimary.copy(alpha = 0.35f), modifier = Modifier.size(18.dp))
            }
        }

        // Volume row
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Volume", color = GreenPrimary, fontSize = 9.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(40.dp))
            Slider(value = LocalMediaState.volume, onValueChange = { LocalMediaState.setVolume(it) },
                modifier = Modifier.weight(1f).height(20.dp),
                colors = SliderDefaults.colors(thumbColor = GreenPrimary, activeTrackColor = GreenPrimary, inactiveTrackColor = GreenPrimary.copy(alpha = 0.3f)))
        }
    }
}

// ── Right Panel ───────────────────────────────────────────────────────────────
@Composable
fun RightPanel(modifier: Modifier, cyclingMessages: List<CyclingMessage>, cycleInterval: Int,
    isRandom: Boolean, onRandomChange: (Boolean) -> Unit,
    onMessagesChange: (List<CyclingMessage>) -> Unit, onIntervalChange: (Int) -> Unit) {
    var newMessage by remember { mutableStateOf("") }
    Column(modifier = modifier.background(BrownMid.copy(alpha = 0.7f), RoundedCornerShape(10.dp)).border(1.dp, GreenPrimary, RoundedCornerShape(10.dp)).padding(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Cycling Messages {cycling}", color = GreenPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Row {
                Box(modifier = Modifier.height(28.dp).background(if (!isRandom) GreenPrimary else Color.Transparent, RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                    .border(1.dp, if (!isRandom) GreenPrimary else GreenPrimary.copy(alpha = 0.4f), RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                    .clickable { onRandomChange(false) }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                    Text("In Order", color = if (!isRandom) BrownDark else GreenPrimary.copy(alpha = 0.4f), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                Box(modifier = Modifier.height(28.dp).background(if (isRandom) GreenPrimary else Color.Transparent, RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                    .border(1.dp, if (isRandom) GreenPrimary else GreenPrimary.copy(alpha = 0.4f), RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                    .clickable { onRandomChange(true) }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                    Text("Random", color = if (isRandom) BrownDark else GreenPrimary.copy(alpha = 0.4f), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Cycle every:", color = GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Slider(value = cycleInterval.toFloat(), onValueChange = { onIntervalChange(it.toInt()) }, valueRange = 1f..30f, steps = 28, modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = GreenPrimary, activeTrackColor = GreenPrimary, inactiveTrackColor = GreenPrimary.copy(alpha = 0.3f)))
            Text("${cycleInterval}s", color = GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(32.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            RaccoonTextField(value = newMessage, onValueChange = { newMessage = it }, placeholder = "New cycling message...", modifier = Modifier.weight(1f))
            RaccoonButton(text = "+ Add", small = true, onClick = {
                if (newMessage.isNotBlank()) { onMessagesChange(cyclingMessages + CyclingMessage(newMessage.trim())); newMessage = "" }
            })
        }
        Spacer(Modifier.height(8.dp))
        if (cyclingMessages.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("No cycling messages yet.\nAdd one above!\n\nUse {cycling} in your template.", color = GreenPrimary.copy(alpha = 0.5f), fontSize = 12.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(cyclingMessages) { index, msg ->
                    var isEditing by remember { mutableStateOf(false) }
                    var editText by remember(msg.text) { mutableStateOf(msg.text) }
                    Row(modifier = Modifier.fillMaxWidth()
                        .border(1.dp, if (msg.isHidden) GreenPrimary.copy(alpha = 0.25f) else GreenPrimary, RoundedCornerShape(6.dp))
                        .background(if (isEditing) GreenPrimary.copy(alpha = 0.1f) else Color.Transparent, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${index+1}.", color = if (msg.isHidden) GreenPrimary.copy(alpha = 0.25f) else GreenPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(20.dp))
                        if (isEditing) {
                            BasicTextField(value = editText, onValueChange = { editText = it }, singleLine = true,
                                textStyle = TextStyle(color = GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                                cursorBrush = SolidColor(GreenPrimary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {
                                    onMessagesChange(cyclingMessages.toMutableList().also { it[index] = msg.copy(text = editText.trim()) })
                                    isEditing = false
                                }), modifier = Modifier.weight(1f))
                            IconButton(onClick = { onMessagesChange(cyclingMessages.toMutableList().also { it[index] = msg.copy(text = editText.trim()) }); isEditing = false }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Check, contentDescription = "Save", tint = GreenPrimary) }
                        } else {
                            Text(msg.text, color = if (msg.isHidden) GreenPrimary.copy(alpha = 0.35f) else GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f).clickable { isEditing = true; editText = msg.text })
                            IconButton(onClick = { onMessagesChange(cyclingMessages.toMutableList().also { it[index] = msg.copy(isHidden = !msg.isHidden) }) }, modifier = Modifier.size(28.dp)) {
                                Icon(if (msg.isHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null, tint = if (msg.isHidden) GreenPrimary.copy(alpha = 0.35f) else GreenPrimary.copy(alpha = 0.7f)) }
                            IconButton(onClick = { if (index > 0) { val l = cyclingMessages.toMutableList(); val t = l[index]; l[index] = l[index-1]; l[index-1] = t; onMessagesChange(l) } }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Up", tint = GreenPrimary.copy(alpha = 0.7f)) }
                            IconButton(onClick = { if (index < cyclingMessages.size - 1) { val l = cyclingMessages.toMutableList(); val t = l[index]; l[index] = l[index+1]; l[index+1] = t; onMessagesChange(l) } }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Down", tint = GreenPrimary.copy(alpha = 0.7f)) }
                            IconButton(onClick = { val l = cyclingMessages.toMutableList(); if (index < l.size) { l.removeAt(index); onMessagesChange(l) } }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Delete", tint = GreenPrimary.copy(alpha = 0.7f)) }
                        }
                    }
                }
            }
        }
    }
}

// ── Setup Dialog ──────────────────────────────────────────────────────────────
@Composable
fun LastFmSetupDialog(currentUsername: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var username by remember { mutableStateOf(currentUsername) }
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Box(modifier = Modifier.width(420.dp).clip(RoundedCornerShape(12.dp)).drawBehind { drawCheckerboard() }.border(2.dp, GreenPrimary, RoundedCornerShape(12.dp)).padding(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Connect Last.fm", color = GreenPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                HorizontalDivider(color = GreenPrimary.copy(alpha = 0.3f))
                Text("Last.fm can track what you're listening to on Spotify and send that info to OSCRaccoon, which sends that info to your VRChat chatbox through OSC.", color = GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 18.sp)
                SetupStep("1", "Create a free account at last.fm/join")
                RaccoonButton(text = "Open last.fm/join", small = true, onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.last.fm/join"))) })
                SetupStep("2", "Connect Spotify at last.fm/settings/applications")
                RaccoonButton(text = "Open Last.fm Settings", small = true, onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.last.fm/settings/applications"))) })
                SetupStep("3", "Enter your Last.fm username below:")
                RaccoonTextField(value = username, onValueChange = { username = it }, placeholder = "Your Last.fm username", modifier = Modifier.fillMaxWidth())
                Text("That's it! OSCRaccoon will automatically show whatever you're listening to.", color = GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
                HorizontalDivider(color = GreenPrimary.copy(alpha = 0.3f))
                Text("VRChat Setup", color = GreenPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                SetupStep("4", "In VRChat, enable OSC in your action menu or in settings.")
                SetupStep("5", "Back in this app, press ▶ Start to begin sending to your chatbox.")
                HorizontalDivider(color = GreenPrimary.copy(alpha = 0.3f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RaccoonButton(text = "Connect", onClick = { if (username.isNotBlank()) onConfirm(username.trim()) }, highlighted = true)
                    RaccoonButton(text = "Cancel", onClick = onDismiss)
                }
            }
        }
    }
}

@Composable
fun SetupStep(number: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$number.", color = GreenPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(text, color = GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 18.sp)
    }
}

@Composable
fun NowPlayingCard(nowPlaying: NowPlaying) {
    Box(modifier = Modifier.fillMaxWidth().border(1.dp, GreenPrimary, RoundedCornerShape(6.dp)).padding(10.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (nowPlaying.isPlaying) Text("▶", color = GreenPrimary, fontSize = 10.sp)
                Text(if (nowPlaying.title.isNotEmpty()) nowPlaying.title else "Nothing Playing", color = GreenPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            if (nowPlaying.artist.isNotEmpty()) Text(nowPlaying.artist, color = GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun PanelCard(modifier: Modifier, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = modifier.background(BrownMid.copy(alpha = 0.7f), RoundedCornerShape(10.dp)).border(1.dp, GreenPrimary, RoundedCornerShape(10.dp)).padding(12.dp)) {
        Text(title, color = GreenPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 10.dp))
        content()
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text, color = GreenPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
fun RaccoonButton(text: String, onClick: () -> Unit, small: Boolean = false, highlighted: Boolean = false) {
    val bg = if (highlighted) GreenPrimary else Color.Transparent
    val fg = if (highlighted) BrownDark else GreenPrimary
    Box(modifier = Modifier.height(36.dp).background(bg, RoundedCornerShape(6.dp)).border(1.dp, GreenPrimary, RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = if (small) 10.dp else 14.dp), contentAlignment = Alignment.Center) {
        Text(text, color = fg, fontSize = if (small) 11.sp else 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun RaccoonTextField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    BasicTextField(value = value, onValueChange = onValueChange, singleLine = true,
        textStyle = TextStyle(color = GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
        cursorBrush = SolidColor(GreenPrimary),
        modifier = modifier.border(1.dp, GreenPrimary, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
        decorationBox = { inner -> if (value.isEmpty()) Text(placeholder, color = GreenPrimary.copy(alpha = 0.4f), fontSize = 12.sp, fontFamily = FontFamily.Monospace); inner() })
}

@Composable
fun RaccoonTextAreaValue(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, label: String, modifier: Modifier = Modifier) {
    BasicTextField(value = value, onValueChange = onValueChange,
        textStyle = TextStyle(color = GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 18.sp),
        cursorBrush = SolidColor(GreenPrimary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Default),
        keyboardActions = KeyboardActions(onAny = {
            val cursor = value.selection.end.coerceIn(0, value.text.length)
            val newText = value.text.substring(0, cursor) + "\n" + value.text.substring(cursor)
            onValueChange(TextFieldValue(text = newText, selection = androidx.compose.ui.text.TextRange(cursor + 1)))
        }),
        modifier = modifier.border(1.dp, GreenPrimary, RoundedCornerShape(6.dp)).padding(10.dp),
        decorationBox = { inner -> if (value.text.isEmpty()) Text(label, color = GreenPrimary.copy(alpha = 0.4f), fontSize = 12.sp, fontFamily = FontFamily.Monospace); inner() })
}
