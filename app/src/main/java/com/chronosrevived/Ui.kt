package com.chronosrevived

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.provider.Settings as SysSettings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import kotlinx.coroutines.launch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.roundToInt

// Blued-steel dial, brass indices, lume for anything live.
private object C {
    val dial = Color(0xFF0D1A2C)
    val dialLight = Color(0xFF1E3554)
    val raised = Color(0xFF16273F)
    val brass = Color(0xFFD3B37A)
    val brassDim = Color(0xFF7D6A4A)
    val lume = Color(0xFFA9F2D2)
    val text = Color(0xFFEAEDF2)
    val muted = Color(0xFF8D9AAD)
    val warn = Color(0xFFEC8F6E)
}

private val Display = FontFamily(Font(R.font.michroma))

@OptIn(ExperimentalTextApi::class)
private fun manrope(w: Int) =
    Font(R.font.manrope, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
private val Body = FontFamily(manrope(400), manrope(500), manrope(600), manrope(700))

private fun body(size: Int, weight: Int = 400, color: Color = C.text) =
    TextStyle(fontFamily = Body, fontSize = size.sp, fontWeight = FontWeight(weight), color = color, lineHeight = (size * 1.45f).sp)

/** LED colours roughly as they look through the disc's diffuser. */
private fun Proto.Color.swatch() = when (this) {
    Proto.Color.RED -> Color(0xFFFF5D50); Proto.Color.YELLOW -> Color(0xFFFFD45C)
    Proto.Color.GREEN -> Color(0xFF5EE48C); Proto.Color.CYAN -> Color(0xFF5EE4E2)
    Proto.Color.BLUE -> Color(0xFF5E8DFF); Proto.Color.MAGENTA -> Color(0xFFE45ED9)
    Proto.Color.WHITE -> Color(0xFFF5F5F2); Proto.Color.DARK -> Color(0xFF3B475A)
}
private fun Proto.Color.label() =
    if (this == Proto.Color.DARK) "No light, vibrate only" else name.lowercase().replaceFirstChar { it.uppercase() } + " light"

/** Snapshot of the connection, rebuilt whenever Chronos reports a change. */
private data class DiscState(
    val address: String?, val connected: Boolean, val battery: Int, val status: Int, val firmware: String,
    val steps: Int?, val scanning: Boolean, val bootloader: Boolean, val bonding: Boolean, val dfu: Int,
    val buzzCount: Int, val lastBuzz: Pair<Proto.Color, Int>,
)

private fun snapshot() = DiscState(
    Chronos.address, Chronos.connected, Chronos.battery, Chronos.status, Chronos.firmware,
    Chronos.steps?.total, Chronos.scanning, Chronos.inBootloader, Chronos.bonding, Chronos.dfuProgress,
    Chronos.buzzCount, Chronos.lastBuzz,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChronosApp(onScan: () -> Unit, onNotificationAccess: () -> Unit, onInstallFirmware: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val l: () -> Unit = { tick++ }
        Chronos.listeners += l
        onDispose { Chronos.listeners -= l }
    }
    val s = remember(tick) { snapshot() }

    // Re-check notification access when coming back from system settings.
    fun accessGranted() = ctx.packageName in NotificationManagerCompat.getEnabledListenerPackages(ctx)
    var hasAccess by remember { mutableStateOf(accessGranted()) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) hasAccess = accessGranted() }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    LaunchedEffect(Unit) { while (true) { delay(60_000); if (Chronos.connected) Chronos.refresh() } }

    val settings = remember { Settings(ctx).also { it.seedDefaults(ctx) } }
    // The dial glows in the LED colour every time the disc is told to buzz, test or real notification.
    val glow = remember { Animatable(0f) }
    LaunchedEffect(s.buzzCount) {
        if (s.buzzCount == 0) return@LaunchedEffect
        repeat(s.lastBuzz.second) {
            glow.animateTo(1f, tween(160, easing = FastOutSlowInEasing))
            glow.animateTo(0f, tween(480, easing = LinearOutSlowInEasing))
        }
    }

    // Pull down to sync: battery, status and steps straight from the disc.
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pull = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            if (Chronos.connected) { Chronos.refresh(); Chronos.fetchHistory() }
            else if (Chronos.address == null) onScan() else Chronos.connect()
            scope.launch { delay(1600); refreshing = false }
        },
        state = pull,
        modifier = Modifier.fillMaxSize().background(C.dial),
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pull, isRefreshing = refreshing, containerColor = C.raised, color = C.brass,
                modifier = Modifier.align(Alignment.TopCenter).systemBarsPadding(),
            )
        },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).systemBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
                Spacer(Modifier.height(16.dp))
                Dial(s, glow.value, s.lastBuzz.first.swatch())
                Spacer(Modifier.height(18.dp))
                StatusLine(s)
                Spacer(Modifier.height(24.dp))

                AnimatedVisibility(s.bonding, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Notice("Your disc wants to pair. Accept the Bluetooth pairing request; if no window shows up, it's waiting in your notifications.")
                }
                when {
                    s.dfu >= 0 -> FirmwareProgress(s.dfu)
                    s.bootloader -> Notice(
                        "This disc has no working firmware, so it can only receive an update. To bring it back, pick the original Chronos app's APK file (com.wearchronos.chronos); the firmware is taken from it on this phone. Keep the disc on the charger and close by; it takes about 30 seconds.",
                        action = if (Firmware.available(ctx)) "Install firmware" else "Choose the Chronos APK",
                        onAction = onInstallFirmware,
                    )
                    s.address == null -> FindDisc(s.scanning, onScan)
                }
                if (s.address != null && !hasAccess) {
                    Notice(
                        "To buzz when something arrives, Chronos needs to see your notifications. Nothing leaves this phone.",
                        action = "Allow notification access", onAction = onNotificationAccess,
                    )
                }

                if (s.address != null) {
                    Section("This week") { WeekChart(s.steps) }
                    Section("Apps that buzz") { AppList(settings, s.connected) }
                    Section("Taps") { Taps(settings) }
                    Section("Battery, last 24 hours") { BatteryChart(s.battery) }
                    Section("Your disc") { DiscDetails(s) }
                }
                Spacer(Modifier.height(40.dp))
            }
        }
        // Content fades out under the status bar instead of colliding with it.
        Box(
            Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(C.dial, C.dial.copy(alpha = 0.85f))))
        )
    }
}

// ---------------------------------------------------------------- dial

@Composable
private fun Dial(s: DiscState, glow: Float, led: Color) {
    val battery by animateFloatAsState(
        if (s.connected && s.battery >= 0) s.battery / 100f else 0f, tween(1400, easing = FastOutSlowInEasing), label = "battery",
    )
    val motion = rememberInfiniteTransition(label = "motion")
    // A sweeping seconds hand, like an automatic movement: one smooth revolution a minute.
    val seconds by motion.animateFloat(0f, 360f, infiniteRepeatable(tween(60_000, easing = LinearEasing)), label = "sec")
    val radar by motion.animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "radar")
    val handAlpha by animateFloatAsState(if (s.connected) 1f else 0f, tween(800), label = "hand")
    val scanAlpha by animateFloatAsState(if (s.scanning) 1f else 0f, tween(500), label = "scan")
    val steps by animateIntAsState(s.steps ?: 0, tween(1200, easing = FastOutSlowInEasing), label = "steps")
    // Launch moment: the dial's markers are laid in clockwise, once.
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(1600, easing = FastOutSlowInEasing)) }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Box(Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f)) {
        Canvas(Modifier.fillMaxSize().semantics {
            contentDescription = if (s.connected) "Battery ${s.battery} percent" else "Disc not connected"
        }) {
            val r = size.minDimension / 2
            drawCase(r)
            drawSunburst(r)
            if (glow > 0f) drawCircle(
                Brush.radialGradient(listOf(led.copy(alpha = 0.6f * glow), led.copy(alpha = 0.15f * glow), Color.Transparent), center, r * 0.93f),
                r * 0.93f,
            )
            drawMinuteTrack(r, battery, reveal.value)
            drawBatons(r, reveal.value)
            if (scanAlpha > 0f) rotate(radar) {
                drawCircle(
                    Brush.sweepGradient(0f to Color.Transparent, 0.8f to Color.Transparent, 1f to C.lume.copy(alpha = 0.32f * scanAlpha), center = center),
                    r * 0.86f,
                )
            }
            if (handAlpha > 0f) rotate(seconds) {
                drawLine(C.brass.copy(alpha = handAlpha), Offset(center.x, center.y + r * 0.2f), Offset(center.x, center.y - r * 0.84f), r * 0.011f, StrokeCap.Round)
                drawCircle(C.lume.copy(alpha = handAlpha), r * 0.03f, Offset(center.x, center.y - r * 0.68f))
            }
            drawCircle(C.brass, r * 0.032f)
            drawCircle(C.dial, r * 0.013f)
        }
        // Dial print, like a maker's name under twelve.
        Text("Chronos", style = TextStyle(fontFamily = Display, fontSize = 12.sp, color = C.brass.copy(alpha = 0.85f)),
            modifier = Modifier.align(BiasAlignment(0f, -0.36f)))
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.align(BiasAlignment(0f, 0.42f))) {
            when {
                s.connected && s.steps != null -> {
                    Text("%,d".format(steps), style = TextStyle(fontFamily = Display, fontSize = 30.sp, color = C.text))
                    Text("steps today", style = body(13, 500, C.muted))
                }
                s.bootloader -> Text("needs firmware", style = body(14, 500, C.warn))
                s.address == null -> Text(if (s.scanning) "searching" else "not paired yet", style = body(14, 500, C.muted))
                else -> Text("asleep", style = body(14, 500, C.muted))
            }
        }
    }
    }
}

private fun DrawScope.drawCase(r: Float) {
    // Polished steel bezel catching light from the upper left.
    drawCircle(
        Brush.sweepGradient(listOf(Color(0xFF59677A), Color(0xFFD5DCE5), Color(0xFF4A5668), Color(0xFFA9B4C2), Color(0xFF59677A)), center), r,
    )
    drawCircle(Brush.radialGradient(listOf(C.dialLight, C.dial), center - Offset(r * 0.2f, r * 0.25f), r * 1.1f), r * 0.94f)
}

private fun DrawScope.drawSunburst(r: Float) {
    for (i in 0 until 180) rotate(i * 2f) {
        drawLine(Color.White.copy(alpha = 0.022f), center, Offset(center.x, center.y - r * 0.94f), r * 0.01f)
    }
}

/** 60 minute ticks; the battery level lights them with lume, clockwise from twelve. */
private fun DrawScope.drawMinuteTrack(r: Float, battery: Float, reveal: Float) {
    val lit = battery * 60f
    for (i in 0 until 60) rotate(i * 6f) {
        val shown = (reveal * 64f - i).coerceIn(0f, 1f)
        if (shown == 0f) return@rotate
        val five = i % 5 == 0
        val fill = (lit - i).coerceIn(0f, 1f)
        val base = if (five) C.brass.copy(alpha = 0.9f) else C.muted.copy(alpha = 0.35f)
        val color = lerp(base, C.lume, fill).let { it.copy(alpha = it.alpha * shown) }
        val len = if (five) 0.07f else 0.04f
        drawLine(color, Offset(center.x, center.y - r * 0.9f), Offset(center.x, center.y - r * (0.9f - len)), r * (if (five) 0.014f else 0.008f), StrokeCap.Round)
    }
}

private fun DrawScope.drawBatons(r: Float, reveal: Float) {
    for (h in 0 until 12) rotate(h * 30f) {
        val shown = (reveal * 13f - h).coerceIn(0f, 1f)
        if (shown == 0f) return@rotate
        val w = r * (if (h == 0) 0.05f else 0.026f)
        drawRoundRect(
            alpha = shown,
            brush = 
Brush.verticalGradient(listOf(Color(0xFFF0DCAE), C.brass, Color(0xFF9C8156)), center.y - r * 0.79f, center.y - r * 0.66f),
            topLeft = Offset(center.x - w / 2, center.y - r * 0.79f),
            size = androidx.compose.ui.geometry.Size(w, r * 0.13f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2),
        )
    }
}

@Composable
private fun StatusLine(s: DiscState) {
    val pulse by rememberInfiniteTransition(label = "dot").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "dotA",
    )
    val (headline, detail) = when {
        s.dfu >= 0 -> "Installing firmware" to "Keep the disc close until this finishes."
        s.connected -> "Connected" to batteryPhrase(s)
        s.bootloader -> "Firmware missing" to "The disc is waiting for an update."
        s.scanning -> "Searching" to "Shake the disc to wake it up."
        s.address != null -> "Disc asleep" to (lastSeenPhrase() ?: "It sleeps to save power. Shake it and it'll reconnect.")
        else -> "No disc yet" to "Find your Chronos to get started."
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (s.connected) C.lume.copy(alpha = pulse) else C.muted.copy(alpha = 0.5f)))
        Spacer(Modifier.width(8.dp))
        Text(headline, style = body(16, 600))
    }
    Text(detail, style = body(14, 400, C.muted), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
}

private fun lastSeenPhrase(): String? {
    val (pct, at) = Chronos.lastBattery ?: return null
    val mins = ((System.currentTimeMillis() - at) / 60_000).toInt()
    val ago = when {
        mins < 1 -> "just now"
        mins < 60 -> "$mins min ago"
        mins < 48 * 60 -> "${mins / 60} h ago"
        else -> "${mins / 1440} days ago"
    }
    return "Battery was $pct% when last seen $ago. Shake it to reconnect."
}

private fun batteryPhrase(s: DiscState): String {
    if (s.battery < 0) return "Reading battery..."
    return when {
        s.status and Proto.CHARGING != 0 -> "Battery ${s.battery}%, charging"
        s.status and Proto.ON_CHARGER != 0 && s.battery >= 95 -> "Battery ${s.battery}%, full and resting on the charger"
        s.status and Proto.ON_CHARGER != 0 -> "Battery ${s.battery}%. It's on the charger but not taking charge; check the contacts."
        s.battery <= 25 -> "Battery ${s.battery}%, time to charge soon"
        else -> "Battery ${s.battery}%"
    }
}

// ---------------------------------------------------------------- setup states

@Composable
private fun FindDisc(scanning: Boolean, onScan: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = onScan, enabled = !scanning,
            colors = ButtonDefaults.buttonColors(containerColor = C.brass, contentColor = C.dial, disabledContainerColor = C.brassDim),
            shape = RoundedCornerShape(28.dp), contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp),
        ) { Text(if (scanning) "Searching..." else "Find my Chronos", style = body(16, 700, C.dial)) }
        Spacer(Modifier.height(10.dp))
        Text(
            "Take the disc off its charger and give it a shake so it starts advertising.",
            style = body(14, 400, C.muted), textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FirmwareProgress(percent: Int) {
    val p by animateFloatAsState(percent / 100f, tween(400), label = "dfu")
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        LinearProgressIndicator(progress = { p }, color = C.lume, trackColor = C.raised, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
        Text("$percent%", style = body(13, 500, C.muted), modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun Notice(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(18.dp)).background(C.raised)
            .border(1.dp, C.brassDim.copy(alpha = 0.5f), RoundedCornerShape(18.dp)).padding(18.dp),
    ) {
        Text(text, style = body(15, 500))
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = C.brass, contentColor = C.dial),
                shape = RoundedCornerShape(24.dp),
            ) { Text(action, style = body(14, 700, C.dial)) }
        }
    }
}

@Composable
private fun Section(title: String, trailing: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 32.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, style = body(19, 700), modifier = Modifier.weight(1f))
            if (trailing != null) Text(trailing, style = body(13, 500, C.muted))
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}

// ---------------------------------------------------------------- steps

@Composable
private fun WeekChart(todaySteps: Int?) {
    val ctx = LocalContext.current
    val days = remember(todaySteps) {
        val logged = StepLog.daily(ctx).toMap()
        val cal = Calendar.getInstance()
        (6 downTo 0).map { back ->
            cal.timeInMillis = System.currentTimeMillis() - back * 86_400_000L
            val key = "%04d-%02d-%02d".format(cal[Calendar.YEAR], cal[Calendar.MONTH] + 1, cal[Calendar.DAY_OF_MONTH])
            val letter = "SMTWTFS"[cal[Calendar.DAY_OF_WEEK] - 1].toString()
            val v = (logged[key] ?: 0).let { if (back == 0) maxOf(it, todaySteps ?: 0) else it }
            letter to v
        }
    }
    val max = maxOf(days.maxOf { it.second }, 1)
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val total = days.sumOf { it.second }

    Text(if (total == 0) "No steps logged yet. Wear the disc and they'll show up here." else "%,d steps in the last 7 days".format(total),
        style = body(14, 400, C.muted))
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        days.forEachIndexed { i, (letter, v) ->
            val today = i == 6
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.weight(1f).width(14.dp)) {
                    val h = size.height * (v / max.toFloat()) * grow.value
                    drawRoundRect(C.raised, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width / 2))
                    if (h > 0f) drawRoundRect(
                        Brush.verticalGradient(if (today) listOf(C.lume, C.lume.copy(alpha = 0.5f)) else listOf(C.brass, C.brassDim)),
                        topLeft = Offset(0f, size.height - h),
                        size = androidx.compose.ui.geometry.Size(size.width, h),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width / 2),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(letter, style = body(12, if (today) 700 else 500, if (today) C.lume else C.muted))
            }
        }
    }
}

// ---------------------------------------------------------------- notification light

@Composable
private fun LightPicker(color: Proto.Color, pattern: Int, onColor: (Proto.Color) -> Unit, onPattern: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Proto.Color.entries.forEach { c ->
            val selected = c == color
            val scale by animateFloatAsState(if (selected) 1.12f else 1f, spring(dampingRatio = 0.5f), label = "sw")
            Box(
                Modifier.size(40.dp).scale(scale).clip(CircleShape)
                    .border(2.dp, if (selected) C.brass else Color.Transparent, CircleShape)
                    .clickable { onColor(c) }
                    .semantics { contentDescription = c.label() },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(26.dp).clip(CircleShape).background(
                        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.85f), c.swatch(), c.swatch().copy(alpha = 0.6f)))
                    )
                )
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(color.label(), style = body(14, 500, C.muted))
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        (1..3).forEach { n ->
            val selected = n == pattern
            Row(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (selected) C.raised else Color.Transparent)
                    .border(1.dp, if (selected) C.brass else C.muted.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                    .clickable { onPattern(n) }
                    .semantics { contentDescription = "$n pulse" + if (n > 1) "s" else "" },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(n) {
                    Box(Modifier.padding(horizontal = 3.dp).size(8.dp).clip(CircleShape).background(if (selected) C.lume else C.muted))
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text("Pulses per notification", style = body(13, 400, C.muted))
}

// ---------------------------------------------------------------- apps

private class AppEntry(val pkg: String, val label: String, val icon: ImageBitmap)

@Composable
private fun rememberApps(): List<AppEntry>? {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.Default) {
            val pm = ctx.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcher, 0)
                .distinctBy { it.activityInfo.packageName }
                .filter { it.activityInfo.packageName != ctx.packageName }
                .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm).toBitmap(96)) }
                .sortedBy { it.label.lowercase() }
        }
    }
    return apps
}

private fun styleText(style: Pair<Proto.Color, Int>) =
    style.first.label() + ", " + style.second + if (style.second == 1) " pulse" else " pulses"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppList(settings: Settings, connected: Boolean) {
    val apps = rememberApps()
    var styles by remember { mutableStateOf(settings.appStyles) }
    var editing by remember { mutableStateOf<AppEntry?>(null) }
    var query by remember { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }
    fun save(m: Map<String, Pair<Proto.Color, Int>>) { styles = m; settings.appStyles = m }

    Text(
        "Only the apps you pick here buzz the disc. Give each one its own light so you know what arrived without looking at your phone.",
        style = body(14, 400, C.muted),
    )
    Spacer(Modifier.height(12.dp))
    if (apps == null) { Text("Loading apps...", style = body(14, 400, C.muted)); return }

    val chosen = apps.filter { it.pkg in styles }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        if (chosen.size < apps.size) TextButton(onClick = {
            save(styles + apps.filter { it.pkg !in styles }.associate { it.pkg to (Proto.Color.BLUE to 2) })
        }) { Text("Select all", style = body(14, 600, C.brass)) }
        if (chosen.isNotEmpty()) TextButton(onClick = { save(emptyMap()) }) {
            Text("Clear all", style = body(14, 600, C.muted))
        }
    }
    Column(Modifier.animateContentSize()) {
        if (chosen.isEmpty()) Text("No apps yet. Add one below.", style = body(15, 500), modifier = Modifier.padding(vertical = 8.dp))
        chosen.forEach { app ->
            val style = styles.getValue(app.pkg)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { editing = app }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(app.icon, null, Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.label, style = body(16, 600))
                    Text(styleText(style), style = body(13, 400, C.muted))
                }
                LedBadge(style)
            }
        }
    }

    Spacer(Modifier.height(18.dp))
    Text("Add an app", style = body(15, 600))
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = query, onValueChange = { query = it }, singleLine = true,
        placeholder = { Text("Search your apps", style = body(15, 400, C.muted)) },
        leadingIcon = { Icon(Icons.Default.Search, null, tint = C.muted) },
        textStyle = body(15, 500),
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = C.brass, unfocusedBorderColor = C.muted.copy(alpha = 0.3f), cursorColor = C.brass,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    val rest = apps.filter { it.pkg !in styles && it.label.contains(query.trim(), ignoreCase = true) }
    val visible = if (showAll || query.isNotBlank()) rest else rest.take(6)
    Column(Modifier.animateContentSize().padding(top = 6.dp)) {
        visible.forEach { app ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable {
                    save(styles + (app.pkg to (Proto.Color.BLUE to 2))); editing = app; query = ""
                }.padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(app.icon, null, Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)))
                Spacer(Modifier.width(14.dp))
                Text(app.label, style = body(15, 500, C.text.copy(alpha = 0.85f)), modifier = Modifier.weight(1f))
                Icon(Icons.Default.Add, "Add ${app.label}", tint = C.brass)
            }
        }
    }
    if (query.isBlank() && rest.size > 6) TextButton(onClick = { showAll = !showAll }) {
        Text(if (showAll) "Show fewer" else "Show all ${rest.size} apps", style = body(14, 600, C.brass))
    }

    editing?.let { app ->
        val style = styles[app.pkg] ?: (Proto.Color.BLUE to 2)
        ModalBottomSheet(onDismissRequest = { editing = null }, containerColor = C.raised, scrimColor = Color.Black.copy(alpha = 0.55f)) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(app.icon, null, Modifier.size(48.dp).clip(RoundedCornerShape(13.dp)))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(app.label, style = body(19, 700))
                        Text("How the disc tells you about it", style = body(14, 400, C.muted))
                    }
                }
                Spacer(Modifier.height(24.dp))
                LightPicker(style.first, style.second,
                    onColor = { save(styles + (app.pkg to (it to style.second))) },
                    onPattern = { save(styles + (app.pkg to (style.first to it))) })
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { Chronos.notify(style.first, style.second) }, enabled = connected,
                        colors = ButtonDefaults.buttonColors(containerColor = C.brass, contentColor = C.dial, disabledContainerColor = C.brassDim),
                        shape = RoundedCornerShape(24.dp),
                    ) { Text(if (connected) "Try it on the disc" else "Disc not connected", style = body(14, 700, C.dial)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { save(styles - app.pkg); editing = null }) {
                        Text("Remove", style = body(14, 600, C.warn))
                    }
                }
            }
        }
    }
}

/** Small LED preview: the colour as a glowing dot with one tick per pulse. */
@Composable
private fun LedBadge(style: Pair<Proto.Color, Int>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(style.second) {
            Box(Modifier.padding(horizontal = 2.dp).size(5.dp).clip(CircleShape).background(C.muted))
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(
                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.85f), style.first.swatch(), style.first.swatch().copy(alpha = 0.15f)))
            )
        )
    }
}

private fun android.graphics.drawable.Drawable.toBitmap(px: Int): ImageBitmap {
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val c = AndroidCanvas(bmp)
    setBounds(0, 0, px, px)
    draw(c)
    return bmp.asImageBitmap()
}

// ---------------------------------------------------------------- taps

@Composable
private fun Taps(settings: Settings) {
    var media by remember { mutableStateOf(settings.mediaTaps) }
    var find by remember { mutableStateOf(settings.findPhone) }
    TapRow("Music controls", "Double tap the crown to play or pause. Triple tap to skip.", media) {
        media = it; settings.mediaTaps = it; Chronos.send("gesture", Proto.gesture(Proto.Gesture.MEDIA, it))
    }
    TapRow("Find my phone", "Tap the watch face over and over and this phone rings, even on silent.", find) {
        find = it; settings.findPhone = it; Chronos.send("gesture", Proto.gesture(Proto.Gesture.FIND_PHONE, it))
    }
}

@Composable
private fun TapRow(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = body(16, 600))
            Text(detail, style = body(14, 400, C.muted))
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = on, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = C.dial, checkedTrackColor = C.brass,
                uncheckedThumbColor = C.muted, uncheckedTrackColor = C.raised, uncheckedBorderColor = C.muted.copy(alpha = 0.4f),
            ),
        )
    }
}

// ---------------------------------------------------------------- details

@Composable
private fun DiscDetails(s: DiscState) {
    var showLog by remember { mutableStateOf(false) }
    Detail("Firmware", s.firmware.ifEmpty { "Unknown until connected" })
    Detail("Bluetooth address", s.address ?: "")
    Row(Modifier.padding(top = 8.dp)) {
        TextButton(onClick = { Chronos.refresh(); Chronos.fetchHistory() }, enabled = s.connected) {
            Text("Sync now", style = body(14, 600, if (s.connected) C.brass else C.brassDim))
        }
        TextButton(onClick = { Chronos.forget() }) { Text("Forget this disc", style = body(14, 600, C.warn)) }
    }
    TextButton(onClick = { showLog = !showLog }) {
        Text(if (showLog) "Hide connection log" else "Show connection log", style = body(14, 600, C.muted))
    }
    AnimatedVisibility(showLog) {
        Text(
            Chronos.log.toList().takeLast(60).reversed().joinToString("\n"),
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = C.muted, lineHeight = 15.sp),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.raised).padding(12.dp),
        )
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = body(15, 500, C.muted), modifier = Modifier.weight(1f))
        Text(value, style = body(15, 600))
    }
}

// ---------------------------------------------------------------- battery history

@Composable
private fun BatteryChart(current: Int) {
    val ctx = LocalContext.current
    val points = remember(current) { BatteryLog.recent(ctx) }
    if (points.size < 2) {
        Text("Readings collect while the disc is connected. Leave it on the charger and check back; if the line climbs, it's charging.",
            style = body(14, 400, C.muted))
        return
    }
    val draw = remember { Animatable(0f) }
    LaunchedEffect(Unit) { draw.animateTo(1f, tween(1200, easing = FastOutSlowInEasing)) }
    val t0 = points.first().first; val span = maxOf(points.last().first - t0, 1L)
    val first = points.first().second; val last = points.last().second
    Text(
        when {
            last > first -> "Up from $first% to $last%. It's taking a charge."
            last < first -> "Down from $first% to $last%."
            else -> "Holding at $last%."
        },
        style = body(14, 500, if (last > first) C.lume else C.muted),
    )
    Spacer(Modifier.height(12.dp))
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        // Grid at 0, 50 and 100 percent.
        listOf(0f, 0.5f, 1f).forEach { f ->
            drawLine(C.muted.copy(alpha = 0.18f), Offset(0f, size.height * (1 - f)), Offset(size.width, size.height * (1 - f)), 1.dp.toPx())
        }
        val path = Path()
        points.forEachIndexed { i, (t, pct, status) ->
            val x = size.width * (t - t0) / span * draw.value
            val y = size.height * (1 - pct / 100f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            // Brass ticks along the bottom mark readings taken on the charger.
            if (status and Proto.ON_CHARGER != 0) drawLine(C.brass.copy(alpha = 0.5f), Offset(x, size.height), Offset(x, size.height - 4.dp.toPx()), 2.dp.toPx())
        }
        drawPath(path, C.lume, style = androidx.compose.ui.graphics.drawscope.Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    Spacer(Modifier.height(6.dp))
    Text("Brass marks show when it sat on the charger.", style = body(12, 400, C.muted))
}
