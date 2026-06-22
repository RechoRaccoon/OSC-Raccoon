package rechoraccoon.trackpanda

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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
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
        val sourceMode = AppPreferences.loadSourceMode(this)
        if (sourceMode == "LASTFM") {
            val saved = AppPreferences.loadUsername(this)
            if (saved.isNotEmpty()) LastFmService.startPolling(saved)
        }
        val folderUri = AppPreferences.loadLocalFolderUri(this)
        if (folderUri.isNotEmpty()) {
            try {
                val overrides = AppPreferences.loadTrackOverrides(this)
                val tracks = loadTracksFromFolder(this, Uri.parse(folderUri), overrides)
                LocalMediaState.loadTracks(tracks)
            } catch (e: Exception) { e.printStackTrace() }
        }
        setContent { TrackPandaApp() }
    }
    override fun onDestroy() { super.onDestroy(); LastFmService.stopPolling(); LocalMediaState.release() }
}

fun DrawScope.drawCheckerboard(colorA: Color = BrownDark, colorB: Color = BrownLight, cellSize: Float = 24f) {
    val cols = (size.width / cellSize).toInt() + 1
    val rows = (size.height / cellSize).toInt() + 1
    for (row in 0..rows) for (col in 0..cols)
        drawRect(if ((row + col) % 2 == 0) colorA else colorB, Offset(col * cellSize, row * cellSize), Size(cellSize, cellSize))
}

fun loadTracksFromFolder(context: android.content.Context, folderUri: Uri, overrides: Map<String, TrackOverride> = emptyMap()): List<LocalTrack> {
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
                    val rawTitle = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: c.getString(2) ?: "Unknown"
                    val rawArtist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unknown"
                    val ov = overrides[fileUri.toString()]
                    tracks.add(LocalTrack(fileUri, ov?.title ?: rawTitle, ov?.artist ?: rawArtist))
                } catch (e: Exception) { } finally { mmr.release() }
            }
        }
    } catch (e: Exception) { e.printStackTrace() }
    return tracks
}

/**
 * After DocumentsContract.renameDocument(), don't trust its return value — some storage
 * providers (notably some SAF implementations on Quest's Android fork) actually rename the
 * file on disk but return null or a stale uri. Re-scan the parent folder and match by the
 * exact display name we just renamed to, which reflects what's truly on disk.
 */
fun findDocumentUriByDisplayName(context: android.content.Context, folderUri: Uri, targetDisplayName: String): Uri? {
    try {
        val docId = DocumentsContract.getTreeDocumentId(folderUri)
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, docId)
        val cursor = context.contentResolver.query(childUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null) ?: return null
        cursor.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (name == targetDisplayName) {
                    val id = c.getString(0) ?: continue
                    return DocumentsContract.buildDocumentUriUsingTree(folderUri, id)
                }
            }
        }
    } catch (e: Exception) { e.printStackTrace() }
    return null
}

@Composable
fun TrackPandaApp() {
    val context = LocalContext.current

    // Ensure a Default preset exists on first run
    val initialPresets = remember {
        val loaded = AppPreferences.loadPresets(context)
        if (loaded.isEmpty()) {
            val default = Preset("default_preset", "Default",
                "🎵 {song} by {artist}\n{cycling}\n{time}", AppPreferences.defaultMessages())
            AppPreferences.savePresets(context, listOf(default))
            listOf(default)
        } else loaded
    }
    var presets by remember { mutableStateOf(initialPresets) }

    val initialPresetId = remember {
        val saved = AppPreferences.loadCurrentPresetId(context)
        if (saved != null && initialPresets.any { it.id == saved }) saved
        else initialPresets.firstOrNull()?.id?.also { AppPreferences.saveCurrentPresetId(context, it) }
    }
    var currentPresetId by remember { mutableStateOf(initialPresetId) }

    var lastFmUsername by remember { mutableStateOf(AppPreferences.loadUsername(context)) }
    var messageTemplate by remember { mutableStateOf(
        currentPresetId?.let { id -> initialPresets.find { it.id == id }?.template }
        ?: AppPreferences.loadTemplate(context)
    ) }
    var cyclingMessages by remember { mutableStateOf(
        currentPresetId?.let { id -> initialPresets.find { it.id == id }?.messages }
        ?: AppPreferences.loadCyclingMessages(context)
    ) }
    var cycleInterval by remember { mutableStateOf(
        currentPresetId?.let { id -> initialPresets.find { it.id == id }?.interval }
        ?: AppPreferences.loadInterval(context)
    ) }
    var sourceMode by remember { mutableStateOf(AppPreferences.loadSourceMode(context)) }
    var presetButtonBottomCenter by remember { mutableStateOf(Offset.Zero) }
    var isRunning by remember { mutableStateOf(false) }
    var showSetup by remember { mutableStateOf(false) }
    var lastFmNowPlaying by remember { mutableStateOf(NowPlaying()) }
    var previewCycleIndex by remember { mutableStateOf(0) }
    var isRandom by remember { mutableStateOf(false) }
    var showIconOverlay by remember { mutableStateOf(false) }
    var showPresetsDropdown by remember { mutableStateOf(false) }
    var appMode by remember { mutableStateOf(AppPreferences.loadAppMode(context)) }
    var showTrackQueue by remember { mutableStateOf(false) }
    val random = remember { java.util.Random() }

    val folderPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            AppPreferences.saveLocalFolderUri(context, it.toString())
            val overrides = AppPreferences.loadTrackOverrides(context)
            val tracks = loadTracksFromFolder(context, it, overrides)
            LocalMediaState.loadTracks(tracks)
            LocalMediaState.playQueue.clear(); LocalMediaState.playQueue.addAll(tracks)
        }
    }

    val nowPlaying = if (sourceMode == "LOCAL")
        LocalMediaState.currentTrack?.let { NowPlaying(it.title, it.artist, LocalMediaState.isPlaying) } ?: NowPlaying()
    else lastFmNowPlaying

    val visibleMessages = remember(cyclingMessages) { cyclingMessages.filter { !it.isHidden }.map { it.text } }

    LaunchedEffect(sourceMode) { if (sourceMode == "LASTFM") LastFmService.nowPlaying.collectLatest { lastFmNowPlaying = it } }
    LaunchedEffect(Unit) { while (true) { LocalMediaState.updatePosition(); delay(500L) } }
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

    LaunchedEffect(messageTemplate, visibleMessages, cycleInterval, sourceMode) {
        if (isRunning) {
            context.startForegroundService(Intent(context, OscForegroundService::class.java).apply {
                action = OscForegroundService.ACTION_UPDATE
                putExtra(OscForegroundService.EXTRA_MAIN_TEMPLATE, messageTemplate)
                putStringArrayListExtra(OscForegroundService.EXTRA_CYCLING_MESSAGES, ArrayList(visibleMessages))
                putExtra(OscForegroundService.EXTRA_CYCLE_INTERVAL, cycleInterval)
                putExtra(OscForegroundService.EXTRA_SOURCE_MODE, sourceMode)
            })
        }
    }

    Box(modifier = Modifier.fillMaxSize().drawBehind { drawCheckerboard() }) {
        Column(modifier = Modifier.fillMaxSize()) {
            SharedHeader(
                appMode = appMode, onAppModeChange = { mode -> appMode = mode; AppPreferences.saveAppMode(context, mode) },
                isRunning = isRunning, lastFmUsername = lastFmUsername, sourceMode = sourceMode,
                presets = presets, currentPresetId = currentPresetId,
                onSourceModeChange = { mode ->
                    if (mode == sourceMode && mode == "LASTFM") { showSetup = true; return@SharedHeader }
                    sourceMode = mode; AppPreferences.saveSourceMode(context, mode)
                    if (mode == "LASTFM") { if (lastFmUsername.isNotEmpty()) LastFmService.startPolling(lastFmUsername) }
                    else { LastFmService.stopPolling(); lastFmNowPlaying = NowPlaying() }
                },
                onPresetsClick = {
                    if (!showPresetsDropdown && currentPresetId != null) {
                        val updated = presets.map { p -> if (p.id == currentPresetId) p.copy(template = messageTemplate, messages = cyclingMessages, interval = cycleInterval) else p }
                        presets = updated; AppPreferences.savePresets(context, updated)
                    }
                    showPresetsDropdown = !showPresetsDropdown
                },
                onPresetButtonLayout = { presetButtonBottomCenter = it },
                showTrackQueue = showTrackQueue, onTrackQueueToggle = { showTrackQueue = !showTrackQueue },
                onPickFolder = { folderPickerLauncher.launch(null) },
                onIconClick = { showIconOverlay = true },
                onStartStop = {
                    if (isRunning) { context.stopService(Intent(context, OscForegroundService::class.java)); isRunning = false }
                    else {
                        context.startForegroundService(Intent(context, OscForegroundService::class.java).apply {
                            action = OscForegroundService.ACTION_START
                            putExtra(OscForegroundService.EXTRA_MAIN_TEMPLATE, messageTemplate)
                            putStringArrayListExtra(OscForegroundService.EXTRA_CYCLING_MESSAGES, ArrayList(visibleMessages))
                            putExtra(OscForegroundService.EXTRA_CYCLE_INTERVAL, cycleInterval)
                            putExtra(OscForegroundService.EXTRA_SOURCE_MODE, sourceMode)
                        }); isRunning = true
                    }
                },
                onClearChatbox = { OscSender.clearChatbox() }
            )
            if (appMode == "chatbox") {
                Row(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LeftPanel(modifier = Modifier.weight(1f).fillMaxHeight(),
                        messageTemplate = messageTemplate,
                        onTemplateChange = { messageTemplate = it; AppPreferences.saveTemplate(context, it) },
                        nowPlaying = nowPlaying, livePreview = livePreview,
                        lastFmUsername = lastFmUsername, sourceMode = sourceMode, onSetupClick = { showSetup = true })
                    RightPanel(modifier = Modifier.weight(1f).fillMaxHeight(),
                        cyclingMessages = cyclingMessages, cycleInterval = cycleInterval, isRandom = isRandom,
                        onRandomChange = { isRandom = it; OscForegroundService.randomCycling = it },
                        onMessagesChange = { cyclingMessages = it; AppPreferences.saveCyclingMessages(context, it) },
                        onIntervalChange = { cycleInterval = it; AppPreferences.saveInterval(context, it) })
                }
            } else {
                TracksContent(showTrackQueue = showTrackQueue)
            }
        }

        // Transparent dismiss layer — no ripple, no dim; just catches outside clicks
        if (showPresetsDropdown) {
            Box(modifier = Modifier.fillMaxSize().zIndex(9f).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null
            ) { showPresetsDropdown = false })
            PresetsDropdown(
                presets = presets, currentPresetId = currentPresetId, position = presetButtonBottomCenter,
                onPresetSelected = { preset ->
                    messageTemplate = preset.template; cyclingMessages = preset.messages; currentPresetId = preset.id
                    cycleInterval = preset.interval.coerceAtLeast(1)
                    AppPreferences.saveTemplate(context, preset.template); AppPreferences.saveCyclingMessages(context, preset.messages)
                    AppPreferences.saveInterval(context, cycleInterval)
                    AppPreferences.saveCurrentPresetId(context, preset.id); showPresetsDropdown = false
                },
                onPresetCreated = {
                    val newId = UUID.randomUUID().toString()
                    val np = Preset(newId, "Preset ${presets.size + 1}", "", emptyList(), interval = 1)
                    val updated = presets + np; presets = updated; currentPresetId = newId
                    messageTemplate = ""; cyclingMessages = emptyList(); cycleInterval = 1
                    AppPreferences.savePresets(context, updated); AppPreferences.saveTemplate(context, "")
                    AppPreferences.saveCyclingMessages(context, emptyList()); AppPreferences.saveInterval(context, 1)
                    AppPreferences.saveCurrentPresetId(context, newId)
                    showPresetsDropdown = false
                },
                onPresetRenamed = { id, newName -> val u = presets.map { if (it.id == id) it.copy(name = newName) else it }; presets = u; AppPreferences.savePresets(context, u) },
                onPresetDeleted = { id ->
                    val u = presets.filter { it.id != id }; presets = u; AppPreferences.savePresets(context, u)
                    if (currentPresetId == id) { currentPresetId = null; AppPreferences.saveCurrentPresetId(context, null) }
                }
            )
        }
        if (showSetup) {
            LastFmSetupDialog(currentUsername = lastFmUsername,
                onConfirm = { u -> lastFmUsername = u; AppPreferences.saveUsername(context, u); LastFmService.startPolling(u); showSetup = false },
                onDismiss = { showSetup = false })
        }
        if (showIconOverlay) {
            val icon: ImageBitmap? = remember { try { BitmapFactory.decodeStream(context.assets.open("osc_raccoon_icon.png"))?.asImageBitmap() } catch (e: Exception) { null } }
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)).clickable { showIconOverlay = false }.zIndex(20f), contentAlignment = Alignment.Center) {
                if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.fillMaxHeight(), contentScale = ContentScale.Fit)
            }
        }
    }
}

// ── Shared Header ─────────────────────────────────────────────────────────────
@Composable
fun SharedHeader(appMode: String, onAppModeChange: (String) -> Unit,
    isRunning: Boolean, lastFmUsername: String, sourceMode: String,
    presets: List<Preset>, currentPresetId: String?,
    onSourceModeChange: (String) -> Unit, onPresetsClick: () -> Unit, onPresetButtonLayout: (Offset) -> Unit,
    showTrackQueue: Boolean, onTrackQueueToggle: () -> Unit, onPickFolder: () -> Unit,
    onIconClick: () -> Unit, onStartStop: () -> Unit, onClearChatbox: () -> Unit) {
    val context = LocalContext.current
    val icon: ImageBitmap? = remember { try { BitmapFactory.decodeStream(context.assets.open("osc_raccoon_icon.png"))?.asImageBitmap() } catch (e: Exception) { null } }
    val currentPreset = presets.find { it.id == currentPresetId }
    Row(modifier = Modifier.fillMaxWidth().background(BrownMid.copy(alpha = 0.85f)).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        // Left: icon + "Track Panda by [Recho Raccoon button]" + mode toggle
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(36.dp).clickable { onIconClick() })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                Text("Track Panda by ", color = GreenPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Box(modifier = Modifier.border(1.dp, GreenPrimary, RoundedCornerShape(4.dp))
                    .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://guns.lol/rechoraccoon"))) }
                    .padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Text("Recho Raccoon", color = GreenPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }
            Row {
                Box(modifier = Modifier.height(32.dp).background(if (appMode == "chatbox") GreenPrimary else Color.Transparent, RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                    .border(1.dp, GreenPrimary, RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                    .clickable { onAppModeChange("chatbox") }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                    Text("Chatbox", color = if (appMode == "chatbox") BrownDark else GreenPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                Box(modifier = Modifier.height(32.dp).background(if (appMode == "tracks") GreenPrimary else Color.Transparent, RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                    .border(1.dp, GreenPrimary, RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                    .clickable { onAppModeChange("tracks") }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                    Text("Tracks", color = if (appMode == "tracks") BrownDark else GreenPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (appMode == "chatbox") {
                Box(modifier = Modifier.onGloballyPositioned { coords ->
                    val pos = coords.positionInRoot()
                    onPresetButtonLayout(Offset(pos.x + coords.size.width / 2f, pos.y + coords.size.height.toFloat()))
                }) { RaccoonButton(text = if (currentPreset != null) "▾ ${currentPreset.name}" else "▾ Presets", small = true, onClick = onPresetsClick) }
                Row {
                    Box(modifier = Modifier.height(32.dp).background(if (sourceMode == "LASTFM") GreenPrimary else Color.Transparent, RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                        .border(1.dp, GreenPrimary, RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                        .clickable { onSourceModeChange("LASTFM") }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                        Text("Spotify", color = if (sourceMode == "LASTFM") BrownDark else GreenPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                    Box(modifier = Modifier.height(32.dp).background(if (sourceMode == "LOCAL") GreenPrimary else Color.Transparent, RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                        .border(1.dp, GreenPrimary, RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                        .clickable { onSourceModeChange("LOCAL") }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                        Text("Local", color = if (sourceMode == "LOCAL") BrownDark else GreenPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                }
                RaccoonButton(text = "Clear", small = true, onClick = onClearChatbox)
                RaccoonButton(text = if (isRunning) "■ Stop" else "▶ Start", onClick = onStartStop, highlighted = !isRunning)
            } else {
                Row {
                    Box(modifier = Modifier.height(32.dp).background(if (!showTrackQueue) GreenPrimary else Color.Transparent, RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                        .border(1.dp, GreenPrimary, RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                        .clickable { if (showTrackQueue) onTrackQueueToggle() }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                        Text("Playlists", color = if (!showTrackQueue) BrownDark else GreenPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                    Box(modifier = Modifier.height(32.dp).background(if (showTrackQueue) GreenPrimary else Color.Transparent, RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                        .border(1.dp, GreenPrimary, RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                        .clickable { if (!showTrackQueue) onTrackQueueToggle() }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                        Text("Queue", color = if (showTrackQueue) BrownDark else GreenPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                }
                RaccoonButton(text = "📁 Folder", small = true, onClick = onPickFolder)
            }
        }
    }
}

// ── Presets Dropdown ──────────────────────────────────────────────────────────
@Composable
fun PresetsDropdown(presets: List<Preset>, currentPresetId: String?, position: Offset,
    onPresetSelected: (Preset) -> Unit, onPresetCreated: () -> Unit,
    onPresetRenamed: (String, String) -> Unit, onPresetDeleted: (String) -> Unit) {
    Box(modifier = Modifier.zIndex(10f)
        .offset { val w = 240.dp.roundToPx(); IntOffset(x = (position.x.toInt() - w / 2).coerceAtLeast(4), y = position.y.toInt() + 4) }
        .width(240.dp).heightIn(max = 400.dp)
        .clip(RoundedCornerShape(8.dp)).drawBehind { drawCheckerboard() }.border(1.dp, GreenPrimary, RoundedCornerShape(8.dp))
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(presets, key = { it.id }) { preset ->
                val isCurrent = preset.id == currentPresetId
                var isRenaming by remember(preset.id) { mutableStateOf(false) }
                var renameText by remember(preset.name) { mutableStateOf(preset.name) }
                Row(modifier = Modifier.fillMaxWidth()
                    .background(if (isCurrent) GreenPrimary.copy(alpha = 0.14f) else Color.Transparent, RoundedCornerShape(6.dp))
                    .border(1.dp, if (isCurrent) GreenPrimary else GreenPrimary.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                    .clickable { if (!isRenaming) onPresetSelected(preset) }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (isCurrent) Text("●", color = GreenPrimary, fontSize = 7.sp, modifier = Modifier.padding(end = 2.dp))
                    if (isRenaming) {
                        BasicTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true,
                            textStyle = TextStyle(color = GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace), cursorBrush = SolidColor(GreenPrimary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { onPresetRenamed(preset.id, renameText.trim()); isRenaming = false }),
                            modifier = Modifier.weight(1f))
                        IconButton(onClick = { onPresetRenamed(preset.id, renameText.trim()); isRenaming = false }, modifier = Modifier.size(22.dp)) {
                            Icon(Icons.Default.Check, null, tint = GreenPrimary, modifier = Modifier.size(14.dp)) }
                    } else {
                        Text(preset.name, color = GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { isRenaming = true }, modifier = Modifier.size(22.dp)) { Icon(Icons.Default.Edit, null, tint = GreenPrimary.copy(alpha = 0.7f), modifier = Modifier.size(12.dp)) }
                        IconButton(onClick = { onPresetDeleted(preset.id) }, modifier = Modifier.size(22.dp)) { Icon(Icons.Default.Close, null, tint = GreenPrimary.copy(alpha = 0.7f), modifier = Modifier.size(12.dp)) }
                    }
                }
            }
            item { HorizontalDivider(color = GreenPrimary.copy(alpha = 0.3f)) }
            item {
                Box(modifier = Modifier.fillMaxWidth().border(1.dp, GreenPrimary, RoundedCornerShape(6.dp)).clickable { onPresetCreated() }.padding(horizontal = 8.dp, vertical = 5.dp)) {
                    Text("+ Create New Preset", color = GreenPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

// ── Left Panel ────────────────────────────────────────────────────────────────
@Composable
fun LeftPanel(modifier: Modifier, messageTemplate: String, onTemplateChange: (String) -> Unit,
    nowPlaying: NowPlaying, livePreview: String, lastFmUsername: String, sourceMode: String, onSetupClick: () -> Unit) {
    // Only reset fieldValue from external template changes (e.g. preset switch),
    // not from our own onTemplateChange calls — fixes cursor jumping to start.
    var fieldValue by remember { mutableStateOf(TextFieldValue(messageTemplate)) }
    var lastPushedText by remember { mutableStateOf(messageTemplate) }
    LaunchedEffect(messageTemplate) {
        if (messageTemplate != lastPushedText) {
            fieldValue = TextFieldValue(messageTemplate)
            lastPushedText = messageTemplate
        }
    }
    fun insertAtCursor(insert: String) {
        val cursor = fieldValue.selection.end.coerceIn(0, fieldValue.text.length)
        val newText = fieldValue.text.substring(0, cursor) + insert + fieldValue.text.substring(cursor)
        fieldValue = TextFieldValue(text = newText, selection = androidx.compose.ui.text.TextRange(cursor + insert.length))
        lastPushedText = newText
        onTemplateChange(newText)
    }
    PanelCard(modifier = modifier, title = "Message Template") {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Tap:", color = GreenPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                listOf("{song}", "{artist}", "{duration}", "{cycling}", "{time}").forEach { p ->
                    Box(modifier = Modifier.border(1.dp, GreenPrimary.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).clickable { insertAtCursor(p) }.padding(horizontal = 5.dp, vertical = 1.dp)) {
                        Text(p, color = GreenPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
                }
            }
            Box(modifier = Modifier.border(1.dp, GreenPrimary, RoundedCornerShape(4.dp)).clickable { insertAtCursor("\n") }.padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text("↵ New Line", color = GreenPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
        }
        RaccoonTextAreaValue(value = fieldValue, onValueChange = { fieldValue = it; lastPushedText = it.text; onTemplateChange(it.text) }, label = "Message template", modifier = Modifier.fillMaxWidth().weight(1f))
        Spacer(Modifier.height(6.dp))
        SectionLabel("Live Chatbox Preview")
        Box(modifier = Modifier.fillMaxWidth().weight(1f).border(1.dp, GreenPrimary, RoundedCornerShape(6.dp)).padding(10.dp)) {
            Text(livePreview.ifEmpty { "(empty)" }, color = GreenPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace, lineHeight = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(6.dp))
        if (sourceMode == "LASTFM") {
            SectionLabel("Now Playing (via Last.fm)")
            NowPlayingCard(nowPlaying)
            Spacer(Modifier.height(6.dp))
            LastFmStatusBar(username = lastFmUsername, onSetupClick = onSetupClick)
        } else {
            CompactLocalPlayer()
        }
    }
}

@Composable
fun LastFmStatusBar(username: String, onSetupClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().border(1.dp, GreenPrimary.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).clickable { onSetupClick() }.padding(horizontal = 10.dp, vertical = 6.dp)) {
        if (username.isEmpty()) Text("Create or Connect a Last.fm account to track Spotify", color = GreenPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Last.fm: $username", color = GreenPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text("Change →", color = GreenPrimary.copy(alpha = 0.6f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

// ── Compact Local Player ──────────────────────────────────────────────────────
@Composable
fun CompactLocalPlayer() {
    val track = LocalMediaState.currentTrack
    Column(modifier = Modifier.fillMaxWidth().border(1.dp, GreenPrimary, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Title / artist
        Text(track?.title ?: "No track", color = GreenPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(track?.artist ?: if (LocalMediaState.tracks.isEmpty()) "Pick a folder in Tracks mode" else "Ready", color = GreenPrimary.copy(alpha = 0.7f), fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // Controls
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = { LocalMediaState.toggleShuffle(!LocalMediaState.isShuffle) }, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Default.Shuffle, "Shuffle", tint = if (LocalMediaState.isShuffle) GreenPrimary else GreenPrimary.copy(alpha = 0.3f), modifier = Modifier.size(16.dp)) }
            IconButton(onClick = { LocalMediaState.prev() }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.SkipPrevious, "Prev", tint = GreenPrimary, modifier = Modifier.size(22.dp)) }
            IconButton(onClick = { LocalMediaState.playPause() }, modifier = Modifier.size(36.dp)) {
                Icon(if (LocalMediaState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play", tint = GreenPrimary, modifier = Modifier.size(28.dp)) }
            IconButton(onClick = { LocalMediaState.next() }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.SkipNext, "Next", tint = GreenPrimary, modifier = Modifier.size(22.dp)) }
            IconButton(onClick = { LocalMediaState.isLoop = !LocalMediaState.isLoop }, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Default.Repeat, "Loop", tint = if (LocalMediaState.isLoop) GreenPrimary else GreenPrimary.copy(alpha = 0.3f), modifier = Modifier.size(16.dp)) }
        }
        // Progress
        if (LocalMediaState.durationMs > 0) {
            val progress = (LocalMediaState.positionMs.toFloat() / LocalMediaState.durationMs).coerceIn(0f, 1f)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                val pm = (LocalMediaState.positionMs / 1000) / 60; val ps = (LocalMediaState.positionMs / 1000) % 60
                val dm = (LocalMediaState.durationMs / 1000) / 60; val ds = (LocalMediaState.durationMs / 1000) % 60
                Text("%d:%02d".format(pm, ps), color = GreenPrimary.copy(alpha = 0.7f), fontSize = 9.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(28.dp))
                RaccoonSlider(value = progress, onValueChange = { LocalMediaState.seek((it * LocalMediaState.durationMs).toLong()) }, modifier = Modifier.weight(1f))
                Text("%d:%02d".format(dm, ds), color = GreenPrimary.copy(alpha = 0.7f), fontSize = 9.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(28.dp))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.width(28.dp))
                RaccoonSlider(value = 0f, onValueChange = {}, modifier = Modifier.weight(1f), enabled = false)
                Spacer(modifier = Modifier.width(28.dp))
            }
        }
        // Volume — labels match the duration row's 28dp width on both sides so the
        // slider track itself lines up exactly with the progress slider above.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Vol", color = GreenPrimary, fontSize = 9.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(28.dp))
            RaccoonSlider(value = LocalMediaState.volume, onValueChange = { LocalMediaState.changeVolume(it) }, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(28.dp))
        }
    }
}

// ── Custom Slider (fixes drag offset — uses absolute pointer position) ────────
@Composable
fun RaccoonSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f, enabled: Boolean = true, height: Dp = 24.dp) {
    val density = LocalDensity.current
    var trackWidthPx by remember { mutableStateOf(0f) }
    val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
    fun calcVal(x: Float) = (valueRange.start + (x / trackWidthPx.coerceAtLeast(1f)).coerceIn(0f, 1f) * (valueRange.endInclusive - valueRange.start))

    Box(modifier = modifier.height(height).onGloballyPositioned { trackWidthPx = it.size.width.toFloat() }
        .then(if (enabled) Modifier
            .pointerInput(valueRange) {
                detectTapGestures { offset -> onValueChange(calcVal(offset.x)) }
            }
            .pointerInput(valueRange) {
                detectDragGestures(
                    onDragStart = { offset -> onValueChange(calcVal(offset.x)) },
                    onDrag = { change, _ -> change.consume(); onValueChange(calcVal(change.position.x)) }
                )
            } else Modifier),
        contentAlignment = Alignment.Center) {
        // Track
        Box(Modifier.fillMaxWidth().height(3.dp).background(GreenPrimary.copy(alpha = if (enabled) 0.22f else 0.1f), RoundedCornerShape(2.dp)))
        if (fraction > 0f && enabled)
            Box(Modifier.fillMaxWidth(fraction).height(3.dp).align(Alignment.CenterStart).background(GreenPrimary, RoundedCornerShape(2.dp)))
        // Thumb
        val thumbOffDp = with(density) { ((trackWidthPx * fraction) - 9.dp.toPx()).coerceAtLeast(0f).toDp() }
        Box(Modifier.size(18.dp).offset(x = thumbOffDp).align(Alignment.CenterStart)
            .background(if (enabled) GreenPrimary else GreenPrimary.copy(alpha = 0.25f), CircleShape))
    }
}

// ── Right Panel ───────────────────────────────────────────────────────────────
@Composable
fun RightPanel(modifier: Modifier, cyclingMessages: List<CyclingMessage>, cycleInterval: Int,
    isRandom: Boolean, onRandomChange: (Boolean) -> Unit,
    onMessagesChange: (List<CyclingMessage>) -> Unit, onIntervalChange: (Int) -> Unit) {
    var newMessage by remember { mutableStateOf("") }
    Column(modifier = modifier.background(BrownMid.copy(alpha = 0.7f), RoundedCornerShape(10.dp)).border(1.dp, GreenPrimary, RoundedCornerShape(10.dp)).padding(12.dp)) {
        // Header row — compact
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Cycling Messages {cycling}", color = GreenPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Row {
                Box(modifier = Modifier.height(26.dp).background(if (!isRandom) GreenPrimary else Color.Transparent, RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                    .border(1.dp, if (!isRandom) GreenPrimary else GreenPrimary.copy(alpha = 0.4f), RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                    .clickable { onRandomChange(false) }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                    Text("In Order", color = if (!isRandom) BrownDark else GreenPrimary.copy(alpha = 0.4f), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) }
                Box(modifier = Modifier.height(26.dp).background(if (isRandom) GreenPrimary else Color.Transparent, RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                    .border(1.dp, if (isRandom) GreenPrimary else GreenPrimary.copy(alpha = 0.4f), RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                    .clickable { onRandomChange(true) }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                    Text("Random", color = if (isRandom) BrownDark else GreenPrimary.copy(alpha = 0.4f), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) }
            }
        }
        // Add message
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            RaccoonTextField(value = newMessage, onValueChange = { newMessage = it }, placeholder = "New cycling message...", modifier = Modifier.weight(1f))
            RaccoonButton(text = "+ Add", small = true, onClick = { if (newMessage.isNotBlank()) { onMessagesChange(cyclingMessages + CyclingMessage(newMessage.trim())); newMessage = "" } })
        }
        // Messages list
        if (cyclingMessages.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("No cycling messages yet.\nAdd one above!\n\nUse {cycling} in template.", color = GreenPrimary.copy(alpha = 0.5f), fontSize = 12.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center) }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                itemsIndexed(cyclingMessages, key = { index, _ -> index }) { index, msg ->
                    var isEditing by remember { mutableStateOf(false) }
                    var editText by remember(msg.text) { mutableStateOf(msg.text) }
                    Row(modifier = Modifier.fillMaxWidth()
                        .border(1.dp, if (msg.isHidden) GreenPrimary.copy(alpha = 0.25f) else GreenPrimary, RoundedCornerShape(6.dp))
                        .background(if (isEditing) GreenPrimary.copy(alpha = 0.1f) else Color.Transparent, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${index+1}.", color = if (msg.isHidden) GreenPrimary.copy(alpha = 0.25f) else GreenPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(20.dp))
                        if (isEditing) {
                            BasicTextField(value = editText, onValueChange = { editText = it }, singleLine = true,
                                textStyle = TextStyle(color = GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace), cursorBrush = SolidColor(GreenPrimary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { onMessagesChange(cyclingMessages.toMutableList().also { it[index] = msg.copy(text = editText.trim()) }); isEditing = false }),
                                modifier = Modifier.weight(1f))
                            IconButton(onClick = { onMessagesChange(cyclingMessages.toMutableList().also { it[index] = msg.copy(text = editText.trim()) }); isEditing = false }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Check, "Save", tint = GreenPrimary) }
                        } else {
                            Text(msg.text, color = if (msg.isHidden) GreenPrimary.copy(alpha = 0.35f) else GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f).clickable { isEditing = true; editText = msg.text })
                            IconButton(onClick = { onMessagesChange(cyclingMessages.toMutableList().also { it[index] = msg.copy(isHidden = !msg.isHidden) }) }, modifier = Modifier.size(26.dp)) {
                                Icon(if (msg.isHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = if (msg.isHidden) GreenPrimary.copy(alpha = 0.3f) else GreenPrimary.copy(alpha = 0.7f)) }
                            IconButton(onClick = { if (index > 0) { val l = cyclingMessages.toMutableList(); val t = l[index]; l[index] = l[index-1]; l[index-1] = t; onMessagesChange(l) } }, modifier = Modifier.size(26.dp)) {
                                Icon(Icons.Default.KeyboardArrowUp, null, tint = GreenPrimary.copy(alpha = 0.7f)) }
                            IconButton(onClick = { if (index < cyclingMessages.size - 1) { val l = cyclingMessages.toMutableList(); val t = l[index]; l[index] = l[index+1]; l[index+1] = t; onMessagesChange(l) } }, modifier = Modifier.size(26.dp)) {
                                Icon(Icons.Default.KeyboardArrowDown, null, tint = GreenPrimary.copy(alpha = 0.7f)) }
                            IconButton(onClick = { val l = cyclingMessages.toMutableList(); if (index < l.size) { l.removeAt(index); onMessagesChange(l) } }, modifier = Modifier.size(26.dp)) {
                                Icon(Icons.Default.Close, null, tint = GreenPrimary.copy(alpha = 0.7f)) }
                        }
                    }
                }
            }
        }
        // Cycle Every slider at bottom (compact, like grid size slider)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Cycle Every:", color = GreenPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            RaccoonSlider(value = cycleInterval.toFloat(), onValueChange = { onIntervalChange(it.toInt()) }, valueRange = 1f..30f, modifier = Modifier.weight(1f))
            Text("${cycleInterval}s", color = GreenPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(28.dp))
        }
    }
}

// ── Reusable UI ───────────────────────────────────────────────────────────────
@Composable
fun LastFmSetupDialog(currentUsername: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var username by remember { mutableStateOf(currentUsername) }
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Box(modifier = Modifier.width(400.dp).clip(RoundedCornerShape(12.dp)).drawBehind { drawCheckerboard() }.border(2.dp, GreenPrimary, RoundedCornerShape(12.dp)).padding(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Connect Last.fm", color = GreenPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                HorizontalDivider(color = GreenPrimary.copy(alpha = 0.3f))
                Text("Last.fm tracks what you're listening to on Spotify.", color = GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                SetupStep("1", "Create a free account at last.fm/join")
                RaccoonButton(text = "Open last.fm/join", small = true, onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.last.fm/join"))) })
                SetupStep("2", "Connect Spotify at last.fm/settings/applications")
                RaccoonButton(text = "Open Last.fm Settings", small = true, onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.last.fm/settings/applications"))) })
                SetupStep("3", "Enter your Last.fm username:")
                RaccoonTextField(value = username, onValueChange = { username = it }, placeholder = "Your Last.fm username", modifier = Modifier.fillMaxWidth())
                HorizontalDivider(color = GreenPrimary.copy(alpha = 0.3f))
                SetupStep("4", "Enable OSC in VRChat action menu or settings.")
                SetupStep("5", "Press ▶ Start to begin sending to your chatbox.")
                HorizontalDivider(color = GreenPrimary.copy(alpha = 0.3f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RaccoonButton(text = "Connect", onClick = { if (username.isNotBlank()) onConfirm(username.trim()) }, highlighted = true)
                    RaccoonButton(text = "Cancel", onClick = onDismiss)
                }
            }
        }
    }
}

@Composable fun SetupStep(number: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$number.", color = GreenPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(text, color = GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 18.sp)
    }
}

@Composable
fun NowPlayingCard(nowPlaying: NowPlaying) {
    val isEmpty = nowPlaying.title.isEmpty()
    val dim = if (isEmpty) 0.4f else 1f
    Box(modifier = Modifier.fillMaxWidth().border(1.dp, GreenPrimary.copy(alpha = dim), RoundedCornerShape(6.dp)).padding(8.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (nowPlaying.isPlaying) Text("▶", color = GreenPrimary.copy(alpha = dim), fontSize = 10.sp)
                Text(if (!isEmpty) nowPlaying.title else "Nothing Playing", color = GreenPrimary.copy(alpha = dim), fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            if (nowPlaying.artist.isNotEmpty()) Text(nowPlaying.artist, color = GreenPrimary.copy(alpha = dim), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun PanelCard(modifier: Modifier, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = modifier.background(BrownMid.copy(alpha = 0.7f), RoundedCornerShape(10.dp)).border(1.dp, GreenPrimary, RoundedCornerShape(10.dp)).padding(12.dp)) {
        Text(title, color = GreenPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 8.dp))
        content()
    }
}

@Composable fun SectionLabel(text: String) { Text(text, color = GreenPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 3.dp)) }

@Composable
fun RaccoonButton(text: String, onClick: () -> Unit, small: Boolean = false, highlighted: Boolean = false) {
    val bg = if (highlighted) GreenPrimary else Color.Transparent
    val fg = if (highlighted) BrownDark else GreenPrimary
    Box(modifier = Modifier.height(32.dp).background(bg, RoundedCornerShape(6.dp)).border(1.dp, GreenPrimary, RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = if (small) 10.dp else 14.dp), contentAlignment = Alignment.Center) {
        Text(text, color = fg, fontSize = if (small) 11.sp else 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun RaccoonTextField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    BasicTextField(value = value, onValueChange = onValueChange, singleLine = true,
        textStyle = TextStyle(color = GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace), cursorBrush = SolidColor(GreenPrimary),
        modifier = modifier.border(1.dp, GreenPrimary, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 7.dp),
        decorationBox = { inner -> if (value.isEmpty()) Text(placeholder, color = GreenPrimary.copy(alpha = 0.4f), fontSize = 12.sp, fontFamily = FontFamily.Monospace); inner() })
}

@Composable
fun RaccoonTextAreaValue(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, label: String, modifier: Modifier = Modifier) {
    BasicTextField(value = value, onValueChange = onValueChange,
        textStyle = TextStyle(color = GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 18.sp), cursorBrush = SolidColor(GreenPrimary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Default),
        keyboardActions = KeyboardActions(onAny = {
            val cursor = value.selection.end.coerceIn(0, value.text.length)
            val newText = value.text.substring(0, cursor) + "\n" + value.text.substring(cursor)
            onValueChange(TextFieldValue(text = newText, selection = androidx.compose.ui.text.TextRange(cursor + 1)))
        }),
        modifier = modifier.border(1.dp, GreenPrimary, RoundedCornerShape(6.dp)).padding(10.dp),
        decorationBox = { inner -> if (value.text.isEmpty()) Text(label, color = GreenPrimary.copy(alpha = 0.4f), fontSize = 12.sp, fontFamily = FontFamily.Monospace); inner() })
}
