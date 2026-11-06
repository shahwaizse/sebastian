package dev.callstream.probe

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.RingVolume
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.callstream.probe.leads.LeadRules

@Composable
fun HomeScreen(state: UiState, actions: UiActions, onOpenLeads: (LeadRules.Filter) -> Unit, onOpenAgent: () -> Unit, onOpenSettings: () -> Unit,
               onGate: (String) -> Unit = {}) {
    val agentName = profileTitle(state.agent.profile).ifEmpty { "your agent" }
    val stage = state.stage
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BrandBar()
        val tap = orbTap(stage, state.stream, state.busy)
        Hero(stage, state.stream, heroText(stage, state.stream, agentName, state.setupEverComplete, state.adb.connectError), tap) {
            if (tap == OrbTap.TOGGLE) actions.setStream(!state.stream.on) else onGate("turn the agent on")
        }
        state.controlError?.let { error ->
            AppCard {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Outlined.ErrorOutline, Tone.DANGER)
                    Text(error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        UpdateCard(state.updates, viaHelper = state.helperBound, enabled = !state.busy, onInstall = actions::installUpdate)
        // Not while connecting: a set-up phone reconnects each time the app opens, and that isn't something to fix.
        AnimatedVisibility(stage.needsSetup, enter = fadeIn(), exit = fadeOut()) {
            SetupPrompt(state.setup, state.setupEverComplete, onOpenSettings)
        }
        if (stage.helperReady) {
            LeadStats(state, onOpenLeads)
            AgentSummary(state.agent, agentName, onOpenAgent)
        }
        if (stage == AppStage.READY) Text("Turn off anytime to take over. During an AI call, turning off disconnects the agent and gives you the mic back; the call stays connected.",
            Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun BrandBar() {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Brand.colors.signal), contentAlignment = Alignment.Center) {
            MiniWave(Color.White, Modifier.size(14.dp))
        }
        Text("Sebastian", style = MaterialTheme.typography.titleLarge)
    }
}

/** Five static bars: the brand mark. */
@Composable
private fun MiniWave(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val heights = floatArrayOf(0.35f, 0.75f, 1f, 0.6f, 0.3f)
        val gap = size.width / 9f
        heights.forEachIndexed { i, h ->
            val x = gap * (i * 2) + gap / 2 // five bars and four gaps fill the nine slots exactly
            val bar = size.height * h
            drawLine(color, androidx.compose.ui.geometry.Offset(x, (size.height - bar) / 2), androidx.compose.ui.geometry.Offset(x, (size.height + bar) / 2),
                strokeWidth = gap, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

@Composable
private fun Hero(stage: AppStage, view: StreamView, text: HeroText, tap: OrbTap, onTap: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        // Small until the helper answers, so a new phone's setup card is in view.
        SignalOrb(orbPhase(stage, view), tap != OrbTap.NOTHING, Modifier.semantics {
            contentDescription = "Sebastian"
            stateDescription = if (stage.helperReady && view.on) "On" else "Off"
        }, compact = !stage.helperReady, dim = tap != OrbTap.TOGGLE) {
            if (tap == OrbTap.TOGGLE) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onTap()
        }
        AnimatedContent(text, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "hero-text") { t ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Text(t.body, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
}

/** The big switch. Calm when off; a turning gradient with pulse rings when live; amber while a call starts. */
@Composable
fun SignalOrb(phase: Phase, enabled: Boolean, modifier: Modifier = Modifier, compact: Boolean = phase == Phase.CONNECTING, dim: Boolean = !enabled,
              onClick: () -> Unit) {
    val brand = Brand.colors
    val live = phase == Phase.READY || phase == Phase.ON_CALL
    val starting = phase == Phase.RINGING || phase == Phase.DIALING
    val ringColor by animateColorAsState(when {
        live -> brand.live; starting -> brand.busy; phase == Phase.ERROR -> brand.danger; else -> Color.Transparent
    }, tween(500), label = "ring")
    val loop = rememberInfiniteTransition(label = "orb")
    val spin by loop.animateFloat(0f, 360f, infiniteRepeatable(tween(if (starting || phase == Phase.CONNECTING) 2400 else 9000, easing = LinearEasing)), label = "spin")
    val pulse by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(if (starting) 1100 else 2600, easing = LinearEasing)), label = "pulse")
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, tween(160, easing = FastOutSlowInEasing), label = "press")

    val orb by animateDpAsState(if (compact) 132.dp else 172.dp, tween(400), label = "orb-size")
    Box(modifier.size(orb * 1.5f), contentAlignment = Alignment.Center) {
        if (live || starting || phase == Phase.ERROR) Canvas(Modifier.fillMaxSize()) {
            val base = size.minDimension * 0.33f
            val rings = if (phase == Phase.ERROR) 1 else 3
            for (i in 0 until rings) {
                val t = if (phase == Phase.ERROR) 0.25f else (pulse + i / 3f) % 1f
                drawCircle(ringColor.copy(alpha = (1f - t) * 0.45f), radius = base * (1f + t * 0.5f), style = Stroke(width = 2.dp.toPx()))
            }
            // Soft glow behind the core.
            drawCircle(Brush.radialGradient(listOf(ringColor.copy(alpha = 0.28f), Color.Transparent), center, base * 1.45f), base * 1.45f)
        }
        val core = Modifier.size(orb).scale(scale).clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Switch, onClick = onClick)
        when {
            live -> Box(core, contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxSize().rotate(spin).background(Brush.sweepGradient(listOf(brand.signalStart, brand.signalEnd, brand.signalStart))))
                if (phase == Phase.ON_CALL) LiveWave(Color.White) else MiniWave(Color.White, Modifier.size(64.dp))
            }
            starting -> Box(core.background(Brush.linearGradient(listOf(brand.busy, brand.busy.copy(alpha = 0.7f)))), contentAlignment = Alignment.Center) {
                Icon(if (phase == Phase.RINGING) Icons.Outlined.RingVolume else Icons.Outlined.Call, null,
                    Modifier.size(56.dp), tint = MaterialTheme.colorScheme.surface)
            }
            phase == Phase.ERROR -> Box(core.background(brand.dangerContainer).border(2.dp, brand.danger, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(56.dp), tint = brand.danger)
            }
            else -> Box(core.background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape), contentAlignment = Alignment.Center) {
                // Connecting: the sync arrows turn.
                Icon(if (phase == Phase.CONNECTING) Icons.Outlined.Sync else Icons.Outlined.PowerSettingsNew, null,
                    Modifier.size(56.dp).let { if (phase == Phase.CONNECTING) it.rotate(spin) else it },
                    tint = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** Moving bars while the agent is on a call. */
@Composable
private fun LiveWave(color: Color) {
    val loop = rememberInfiniteTransition(label = "wave")
    val periods = intArrayOf(520, 680, 450, 610, 560)
    Row(Modifier.height(64.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        periods.forEachIndexed { i, period ->
            val h by loop.animateFloat(0.25f, 1f, infiniteRepeatable(tween(period, easing = FastOutSlowInEasing), RepeatMode.Reverse,
                StartOffset(period * i / 3)), label = "bar$i")
            Box(Modifier.width(9.dp).fillMaxHeight(h).clip(RoundedCornerShape(50)).background(color))
        }
    }
}

/**
 * Home only says that setup needs doing and how far along it is; the steps and their buttons live in Settings, which
 * the one button opens. [everComplete]: this phone was set up before, so something has gone wrong since (the helper
 * stopped, a permission was taken back).
 */
@Composable
private fun SetupPrompt(steps: List<SetupStep>, everComplete: Boolean, onOpenSetup: () -> Unit) {
    if (nextSetupStep(steps) == null) return
    val required = steps.filter { it.required }
    val done = required.count { it.state == StepState.DONE }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(if (everComplete) Icons.Outlined.ErrorOutline else Icons.Outlined.Checklist, if (everComplete) Tone.BUSY else Tone.ACCENT)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (everComplete) "Sebastian needs attention" else "Finish setting up", style = MaterialTheme.typography.titleMedium)
                Text(if (everComplete) "Calls won't be answered until this is fixed." else "$done of ${required.size} done",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!everComplete) LinearProgressIndicator(progress = { done / required.size.toFloat() },
            Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = Brand.colors.live,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, drawStopIndicator = {})
        Button(onClick = onOpenSetup, modifier = Modifier.fillMaxWidth()) { Text(if (everComplete) "See what to fix" else "Continue setup") }
    }
}

@Composable
private fun LeadStats(state: UiState, onOpenLeads: (LeadRules.Filter) -> Unit) {
    val counts = state.leads.counts
    val tiles = listOfNotNull(
        Triple(LeadRules.Filter.NOT_CALLED, "To call", Tone.IDLE),
        Triple(LeadRules.Filter.FOLLOW_UPS, "Callbacks", Tone.BUSY),
        Triple(LeadRules.Filter.INTERESTED, "Interested", Tone.LIVE).takeIf { state.spec.interested != null },
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Leads")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            tiles.forEach { (filter, label, tone) ->
                StatTile(label, counts[filter.name.lowercase()] ?: 0, tone, Modifier.weight(1f)) { onOpenLeads(filter) }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: Int, tone: Tone, modifier: Modifier, onClick: () -> Unit) {
    val (_, fg) = toneColors(tone)
    AppCard(modifier, onClick = onClick, padding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value.toString(), style = MaterialTheme.typography.headlineMedium, color = fg)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AgentSummary(options: AgentOptions, agentName: String, onOpenAgent: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Your agent")
        AppCard(onClick = onOpenAgent, padding = 12.dp) {
            ListItemRow(agentName, if (options.profiles.isEmpty()) "No agent installed yet" else "Voice: ${options.voice}",
                leading = { Avatar(agentName, 48.dp, Brand.colors.signal) },
                trailing = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) })
        }
    }
}
