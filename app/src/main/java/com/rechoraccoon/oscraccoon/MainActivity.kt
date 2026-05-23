package com.rechoraccoon.oscraccoon

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

val GreenPrimary = Color(0xFF00FF07)
val BrownDark    = Color(0xFF5C3317)
val BrownLight   = Color(0xFF7A4A25)
val BrownMid     = Color(0xFF6B3D1E)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val savedUsername = AppPreferences.loadUsername(this)
        if (savedUsername.isNotEmpty()) LastFmService.startPolling(savedUsername)
        setContent { OSCRaccoonApp() }
    }
    override fun onDestroy() {
        super.onDestroy()
        LastFmService.stopPolling()
    }
}

fun DrawScope.drawCheckerboard(colorA: Color = BrownDark, colorB: Color = BrownLight, cellSize: Float = 24f) {
    val cols = (size.width / cellSize).toInt() + 1
    val rows = (size.height / cellSize).toInt() + 1
    for (row in 0..rows) {
        for (col in 0..cols) {
            val color = if ((row + col) % 2 == 0) colorA else colorB
            drawRect(color = color, topLeft = Offset(col * cellSize, row * cellSize), size = Size(cellSize, cellSize))
        }
    }
}

val DEFAULT_CYCLING = listOf("💚🦝💚🦝💚🦝💚🦝", "🦝💚🦝💚🦝💚🦝💚")

@Composable
fun OSCRaccoonApp() {
    val context = LocalContext.current
    var lastFmUsername by remember { mutableStateOf(AppPreferences.loadUsername(context)) }
    var messageTemplate by remember { mutableStateOf(AppPreferences.loadTemplate(context)) }
    var cyclingMessages by remember {
        val saved = AppPreferences.loadCyclingMessages(context)
        mutableStateOf(if (saved.isEmpty()) DEFAULT_CYCLING else saved)
    }
    var cycleInterval by remember { mutableStateOf(AppPreferences.loadInterval(context)) }
    var hiddenMessages by remember { mutableStateOf(AppPreferences.loadHiddenMessages(context)) }
    var isRunning by remember { mutableStateOf(false) }
    var showSetup by remember { mutableStateOf(lastFmUsername.isEmpty()) }
    var nowPlaying by remember { mutableStateOf(NowPlaying()) }
    var previewCycleIndex by remember { mutableStateOf(0) }
    var isRandom by remember { mutableStateOf(false) }
    val random = remember { java.util.Random() }

    // Visible messages for cycling (excludes hidden ones)
    val visibleMessages = remember(cyclingMessages, hiddenMessages) {
        cyclingMessages.filterIndexed { i, _ -> !hiddenMessages.contains(i) }
    }

    LaunchedEffect(Unit) { LastFmService.nowPlaying.collectLatest { nowPlaying = it } }

    LaunchedEffect(visibleMessages, cycleInterval, isRandom) {
        while (true) {
            delay(cycleInterval * 1000L)
            if (visibleMessages.isNotEmpty()) {
                previewCycleIndex = if (isRandom) {
                    if (visibleMessages.size > 1) {
                        var next: Int
                        do { next = random.nextInt(visibleMessages.size) } while (next == previewCycleIndex)
                        next
                    } else 0
                } else {
                    (previewCycleIndex + 1) % visibleMessages.size
                }
            }
        }
    }

    val currentCycling = if (visibleMessages.isNotEmpty()) visibleMessages[previewCycleIndex.coerceIn(0, (visibleMessages.size - 1).coerceAtLeast(0))] else ""
    val livePreview = OscForegroundService.formatTemplate(messageTemplate, nowPlaying, currentCycling)

    LaunchedEffect(messageTemplate, visibleMessages, cycleInterval) {
        if (isRunning) {
            val svc = Intent(context, OscForegroundService::class.java).apply {
                action = OscForegroundService.ACTION_UPDATE
                putExtra(OscForegroundService.EXTRA_MAIN_TEMPLATE, messageTemplate)
                putStringArrayListExtra(OscForegroundService.EXTRA_CYCLING_MESSAGES, ArrayList(visibleMessages))
                putExtra(OscForegroundService.EXTRA_CYCLE_INTERVAL, cycleInterval)
            }
            context.startForegroundService(svc)
        }
    }

    var showIconOverlay by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().drawBehind { drawCheckerboard() }) {
        Column(modifier = Modifier.fillMaxSize()) {
            HeaderBar(
                isRunning = isRunning,
                lastFmUsername = lastFmUsername,
                onSetupClick = { showSetup = true },
                onIconClick = { showIconOverlay = true },
                onStartStop = {
                    if (isRunning) {
                        context.stopService(Intent(context, OscForegroundService::class.java))
                        isRunning = false
                    } else {
                        val svc = Intent(context, OscForegroundService::class.java).apply {
                            action = OscForegroundService.ACTION_START
                            putExtra(OscForegroundService.EXTRA_MAIN_TEMPLATE, messageTemplate)
                            putStringArrayListExtra(OscForegroundService.EXTRA_CYCLING_MESSAGES, ArrayList(visibleMessages))
                            putExtra(OscForegroundService.EXTRA_CYCLE_INTERVAL, cycleInterval)
                        }
                        context.startForegroundService(svc)
                        isRunning = true
                    }
                },
                onClearChatbox = { OscSender.clearChatbox() }
            )
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LeftPanel(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    messageTemplate = messageTemplate,
                    onTemplateChange = { messageTemplate = it; AppPreferences.saveTemplate(context, it) },
                    nowPlaying = nowPlaying,
                    livePreview = livePreview,
                    lastFmUsername = lastFmUsername
                )
                RightPanel(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    cyclingMessages = cyclingMessages,
                    hiddenMessages = hiddenMessages,
                    cycleInterval = cycleInterval,
                    isRandom = isRandom,
                    onRandomChange = { isRandom = it; OscForegroundService.randomCycling = it },
                    onHiddenChange = { hiddenMessages = it; AppPreferences.saveHiddenMessages(context, it) },
                    onMessagesChange = {
                        // Clamp preview index before updating to prevent crashes
                        val newList = it
                        if (newList.isEmpty()) previewCycleIndex = 0
                        else if (previewCycleIndex >= newList.size) previewCycleIndex = newList.size - 1
                        cyclingMessages = newList
                        AppPreferences.saveCyclingMessages(context, newList)
                    },
                    onIntervalChange = { cycleInterval = it; AppPreferences.saveInterval(context, it) }
                )
            }
        }
        if (showSetup) {
            LastFmSetupDialog(
                currentUsername = lastFmUsername,
                onConfirm = { username ->
                    lastFmUsername = username
                    AppPreferences.saveUsername(context, username)
                    LastFmService.startPolling(username)
                    showSetup = false
                },
                onDismiss = { if (lastFmUsername.isNotEmpty()) showSetup = false }
            )
        }
        // Icon overlay — last in Box so it sits on top of everything
        if (showIconOverlay) {
            val context2 = LocalContext.current
            val icon: ImageBitmap? = remember {
                try { BitmapFactory.decodeStream(context2.assets.open("osc_raccoon_icon.png"))?.asImageBitmap() }
                catch (e: Exception) {
                    try { BitmapFactory.decodeStream(context2.assets.open("recho_icon.png"))?.asImageBitmap() }
                    catch (e2: Exception) { null }
                }
            }
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)).clickable { showIconOverlay = false },
                contentAlignment = Alignment.Center
            ) {
                if (icon != null) {
                    Image(bitmap = icon, contentDescription = "OSC Raccoon full", modifier = Modifier.fillMaxHeight(), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                }
            }
        }
    }
}

@Composable
fun HeaderBar(isRunning: Boolean, lastFmUsername: String, onSetupClick: () -> Unit, onIconClick: () -> Unit, onStartStop: () -> Unit, onClearChatbox: () -> Unit) {
    val context = LocalContext.current
    val icon: ImageBitmap? = remember {
        try { BitmapFactory.decodeStream(context.assets.open("osc_raccoon_icon.png"))?.asImageBitmap() }
        catch (e: Exception) {
            try { BitmapFactory.decodeStream(context.assets.open("recho_icon.png"))?.asImageBitmap() }
            catch (e2: Exception) { null }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().background(BrownMid.copy(alpha = 0.85f)).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left: app icon + title
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (icon != null) {
                Image(bitmap = icon, contentDescription = "OSC Raccoon",
                    modifier = Modifier.size(36.dp).clickable { onIconClick() })
            }
            Column {
                Text("OSCRaccoon by Recho Raccoon", color = GreenPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            RaccoonButton(text = "Recho's Socials", small = true, onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://guns.lol/rechoraccoon")))
            })
        }
        // Right: Last.fm + clear + start/stop
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RaccoonButton(
                text = if (lastFmUsername.isEmpty()) "⚠ Connect Last.fm" else "Last.fm: $lastFmUsername",
                onClick = onSetupClick, small = true, highlighted = lastFmUsername.isEmpty()
            )
            RaccoonButton(text = "Clear Chatbox", onClick = onClearChatbox)
            RaccoonButton(text = if (isRunning) "■ Stop" else "▶ Start", onClick = onStartStop, highlighted = !isRunning)
        }
    }
}

@Composable
fun LastFmSetupDialog(currentUsername: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var username by remember { mutableStateOf(currentUsername) }
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Box(modifier = Modifier.width(420.dp).drawBehind { drawCheckerboard() }.border(2.dp, GreenPrimary, RoundedCornerShape(12.dp)).padding(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Connect Last.fm", color = GreenPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                HorizontalDivider(color = GreenPrimary.copy(alpha = 0.3f))
                Text("Last.fm tracks your music automatically from Spotify, YouTube Music, and more — for free.", color = GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 18.sp)
                SetupStep("1", "Create a free account at last.fm/join")
                RaccoonButton(text = "Open last.fm/join", small = true, onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.last.fm/join"))) })
                SetupStep("2", "Connect Spotify (or any music app) at last.fm/settings/applications")
                RaccoonButton(text = "Open Last.fm Settings", small = true, onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.last.fm/settings/applications"))) })
                SetupStep("3", "Enter your Last.fm username below:")
                RaccoonTextField(value = username, onValueChange = { username = it }, placeholder = "Your Last.fm username", modifier = Modifier.fillMaxWidth())
                Text("That's it! OSC Raccoon will automatically show whatever you're listening to.", color = GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)

                HorizontalDivider(color = GreenPrimary.copy(alpha = 0.3f))

                Text("VRChat Setup", color = GreenPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                SetupStep("4", "In VRChat, open the main menu → OSC → enable OSC.")
                SetupStep("5", "Back in this app, press ▶ Start to begin sending to your chatbox.")

                HorizontalDivider(color = GreenPrimary.copy(alpha = 0.3f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RaccoonButton(text = "Connect", onClick = { if (username.isNotBlank()) onConfirm(username.trim()) }, highlighted = true)
                    if (currentUsername.isNotEmpty()) RaccoonButton(text = "Cancel", onClick = onDismiss)
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
fun LeftPanel(modifier: Modifier, messageTemplate: String, onTemplateChange: (String) -> Unit, nowPlaying: NowPlaying, livePreview: String, lastFmUsername: String) {
    // Initialize once only — don't use remember(messageTemplate) which resets cursor on every change
    var fieldValue by remember { mutableStateOf(TextFieldValue(messageTemplate)) }

    // Helper to insert text at current cursor position
    fun insertAtCursor(insert: String) {
        val cursor = fieldValue.selection.end.coerceIn(0, fieldValue.text.length)
        val before = fieldValue.text.substring(0, cursor)
        val after = fieldValue.text.substring(cursor)
        val newText = "$before$insert$after"
        val newCursor = cursor + insert.length
        fieldValue = TextFieldValue(text = newText, selection = androidx.compose.ui.text.TextRange(newCursor))
        onTemplateChange(newText)
    }

    PanelCard(modifier = modifier, title = "Message Template") {
        // Placeholder row — each code is clickable and inserts at cursor
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Tap to insert:", color = GreenPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                listOf("{song}", "{artist}", "{cycling}", "{time}").forEach { placeholder ->
                    Box(
                        modifier = Modifier
                            .border(1.dp, GreenPrimary.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                            .clickable { insertAtCursor(placeholder) }
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(placeholder, color = GreenPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            Box(
                modifier = Modifier
                    .border(1.dp, GreenPrimary, RoundedCornerShape(4.dp))
                    .clickable { insertAtCursor("\n") }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("↵ New Line", color = GreenPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
        RaccoonTextAreaValue(
            value = fieldValue,
            onValueChange = {
                // Update fieldValue directly — preserves selection/cursor
                fieldValue = it
                onTemplateChange(it.text)
            },
            label = "Message template",
            modifier = Modifier.fillMaxWidth().height(100.dp)
        )
        Spacer(Modifier.height(12.dp))
        SectionLabel("Now Playing" + if (lastFmUsername.isNotEmpty()) " (via Last.fm)" else "")
        NowPlayingCard(nowPlaying)
        Spacer(Modifier.height(12.dp))
        SectionLabel("Live Chatbox Preview")
        Box(modifier = Modifier.fillMaxWidth().weight(1f).border(1.dp, GreenPrimary, RoundedCornerShape(6.dp)).padding(10.dp)) {
            Text(livePreview.ifEmpty { "(empty)" }, color = GreenPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace, lineHeight = 18.sp)
        }
    }
}

@Composable
fun RightPanel(modifier: Modifier, cyclingMessages: List<String>, hiddenMessages: Set<Int>, cycleInterval: Int, isRandom: Boolean, onRandomChange: (Boolean) -> Unit, onHiddenChange: (Set<Int>) -> Unit, onMessagesChange: (List<String>) -> Unit, onIntervalChange: (Int) -> Unit) {
    var newMessage by remember { mutableStateOf("") }

    // Panel title row with Order/Random toggles on the right
    Column(modifier = modifier.background(BrownMid.copy(alpha = 0.7f), RoundedCornerShape(10.dp)).border(1.dp, GreenPrimary, RoundedCornerShape(10.dp)).padding(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Cycling Messages {cycling}", color = GreenPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                Box(
                    modifier = Modifier.height(28.dp).background(if (!isRandom) GreenPrimary else Color.Transparent, RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                        .border(1.dp, if (!isRandom) GreenPrimary else GreenPrimary.copy(alpha = 0.4f), RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                        .clickable { onRandomChange(false) }.padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) { Text("In Order", color = if (!isRandom) BrownDark else GreenPrimary.copy(alpha = 0.4f), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) }
                Box(
                    modifier = Modifier.height(28.dp).background(if (isRandom) GreenPrimary else Color.Transparent, RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                        .border(1.dp, if (isRandom) GreenPrimary else GreenPrimary.copy(alpha = 0.4f), RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                        .clickable { onRandomChange(true) }.padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Random", color = if (isRandom) BrownDark else GreenPrimary.copy(alpha = 0.4f), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) }
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
                if (newMessage.isNotBlank()) { onMessagesChange(cyclingMessages + newMessage.trim()); newMessage = "" }
            })
        }
        Spacer(Modifier.height(8.dp))
        if (cyclingMessages.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("No cycling messages yet.\nAdd one above!\n\nUse {cycling} in your\ntemplate to place them.", color = GreenPrimary.copy(alpha = 0.5f), fontSize = 12.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(cyclingMessages, key = { idx, _ -> idx }) { index, message ->
                    val isHidden = hiddenMessages.contains(index)
                    var isEditing by remember { mutableStateOf(false) }
                    var editText by remember(message) { mutableStateOf(message) }

                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .border(1.dp, if (isHidden) GreenPrimary.copy(alpha = 0.25f) else GreenPrimary, RoundedCornerShape(6.dp))
                            .background(if (isEditing) GreenPrimary.copy(alpha = 0.1f) else Color.Transparent, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("${index+1}.", color = if (isHidden) GreenPrimary.copy(alpha = 0.25f) else GreenPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(20.dp))
                        if (isEditing) {
                            BasicTextField(
                                value = editText, onValueChange = { editText = it }, singleLine = true,
                                textStyle = TextStyle(color = GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                                cursorBrush = SolidColor(GreenPrimary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {
                                    if (editText.isNotBlank()) { val l = cyclingMessages.toMutableList(); l[index] = editText.trim(); onMessagesChange(l) }
                                    isEditing = false
                                }),
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = {
                                if (editText.isNotBlank()) { val l = cyclingMessages.toMutableList(); l[index] = editText.trim(); onMessagesChange(l) }
                                isEditing = false
                            }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Check, contentDescription = "Save", tint = GreenPrimary) }
                        } else {
                            Text(message, color = if (isHidden) GreenPrimary.copy(alpha = 0.35f) else GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f).clickable { isEditing = true; editText = message })
                            IconButton(onClick = {
                                val newHidden = hiddenMessages.toMutableSet()
                                if (isHidden) newHidden.remove(index) else newHidden.add(index)
                                onHiddenChange(newHidden)
                            }, modifier = Modifier.size(28.dp)) {
                                Icon(if (isHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (isHidden) "Show" else "Hide",
                                    tint = if (isHidden) GreenPrimary.copy(alpha = 0.35f) else GreenPrimary.copy(alpha = 0.7f))
                            }
                            IconButton(onClick = { if (index > 0) { val l = cyclingMessages.toMutableList(); val t = l[index]; l[index] = l[index-1]; l[index-1] = t; onMessagesChange(l) } }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Up", tint = GreenPrimary.copy(alpha = 0.7f)) }
                            IconButton(onClick = { if (index < cyclingMessages.size - 1) { val l = cyclingMessages.toMutableList(); val t = l[index]; l[index] = l[index+1]; l[index+1] = t; onMessagesChange(l) } }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Down", tint = GreenPrimary.copy(alpha = 0.7f)) }
                            IconButton(onClick = { val nl = cyclingMessages.toMutableList(); if (index < nl.size) { nl.removeAt(index); onMessagesChange(nl) } }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Delete", tint = GreenPrimary.copy(alpha = 0.7f)) }
                        }
                    }
                }
            }
        }
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
    Box(
        modifier = Modifier.height(36.dp).background(bg, RoundedCornerShape(6.dp)).border(1.dp, GreenPrimary, RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = if (small) 10.dp else 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = fg, fontSize = if (small) 11.sp else 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun RaccoonTextField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    BasicTextField(
        value = value, onValueChange = onValueChange, singleLine = true,
        textStyle = TextStyle(color = GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
        cursorBrush = SolidColor(GreenPrimary),
        modifier = modifier.border(1.dp, GreenPrimary, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
        decorationBox = { inner -> if (value.isEmpty()) Text(placeholder, color = GreenPrimary.copy(alpha = 0.4f), fontSize = 12.sp, fontFamily = FontFamily.Monospace); inner() }
    )
}

@Composable
fun RaccoonTextArea(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    BasicTextField(
        value = value, onValueChange = onValueChange,
        textStyle = TextStyle(color = GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 18.sp),
        cursorBrush = SolidColor(GreenPrimary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Default),
        keyboardActions = KeyboardActions(onAny = { onValueChange(value + "\n") }),
        modifier = modifier.border(1.dp, GreenPrimary, RoundedCornerShape(6.dp)).padding(10.dp),
        decorationBox = { inner -> if (value.isEmpty()) Text(label, color = GreenPrimary.copy(alpha = 0.4f), fontSize = 12.sp, fontFamily = FontFamily.Monospace); inner() }
    )
}

@Composable
fun RaccoonTextAreaValue(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, label: String, modifier: Modifier = Modifier) {
    BasicTextField(
        value = value, onValueChange = onValueChange,
        textStyle = TextStyle(color = GreenPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 18.sp),
        cursorBrush = SolidColor(GreenPrimary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Default),
        keyboardActions = KeyboardActions(onAny = {
            val cursor = value.selection.end.coerceIn(0, value.text.length)
            val before = value.text.substring(0, cursor)
            val after = value.text.substring(cursor)
            val newText = "$before\n$after"
            onValueChange(TextFieldValue(text = newText, selection = androidx.compose.ui.text.TextRange(cursor + 1)))
        }),
        modifier = modifier.border(1.dp, GreenPrimary, RoundedCornerShape(6.dp)).padding(10.dp),
        decorationBox = { inner -> if (value.text.isEmpty()) Text(label, color = GreenPrimary.copy(alpha = 0.4f), fontSize = 12.sp, fontFamily = FontFamily.Monospace); inner() }
    )
}
