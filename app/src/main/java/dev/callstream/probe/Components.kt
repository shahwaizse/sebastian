package dev.callstream.probe

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A message shown once, as a snackbar ([error]: in the error style), optionally with one action such as Undo. */
data class Notice(val text: String, val error: Boolean = false, val actionLabel: String? = null, val onAction: (() -> Unit)? = null,
                  val id: Long = System.nanoTime())

/** The standard card: a quiet surface with a hairline border. */
@Composable
fun AppCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: Dp = 20.dp,
            content: @Composable ColumnScope.() -> Unit) {
    val shape = MaterialTheme.shapes.large
    val border = BorderStroke(1.dp, Brand.colors.cardBorder)
    val inner: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(padding), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
    if (onClick != null) Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, color = Brand.colors.card, border = border, content = inner)
    else Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = Brand.colors.card, border = border, content = inner)
}

/** Large page title with an optional line under it and actions on the right. */
@Composable
fun ScreenHeader(title: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.headlineLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier.padding(start = 4.dp, top = 8.dp), style = SectionLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

enum class Tone { LIVE, BUSY, DANGER, IDLE, ACCENT }

@Composable
fun toneColors(tone: Tone): Pair<Color, Color> = with(Brand.colors) {
    when (tone) {
        Tone.LIVE -> liveContainer to live
        Tone.BUSY -> busyContainer to busy
        Tone.DANGER -> dangerContainer to danger
        Tone.IDLE -> idleContainer to idle
        Tone.ACCENT -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
}

/** A small rounded label; [dot] adds a status dot in front. */
@Composable
fun Pill(text: String, tone: Tone, dot: Boolean = false) {
    val (bg, fg) = toneColors(tone)
    Row(Modifier.clip(CircleShape).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (dot) Box(Modifier.size(6.dp).clip(CircleShape).background(fg))
        Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Initials in a circle; the colour is stable per name, from the brand's soft palette. */
@Composable
fun Avatar(name: String, size: Dp = 44.dp, brush: Brush? = null) {
    val initials = name.split(' ', '-', '_').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
        .ifEmpty { "#" }
    val palette = listOf(Tone.LIVE, Tone.ACCENT, Tone.BUSY, Tone.IDLE)
    val (bg, fg) = toneColors(palette[Math.floorMod(name.hashCode(), palette.size)])
    Box(Modifier.size(size).clip(CircleShape).let { if (brush != null) it.background(brush) else it.background(bg) }, contentAlignment = Alignment.Center) {
        Text(initials, color = if (brush != null) Color.White else fg, fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.36f).sp, maxLines = 1)
    }
}

/** An icon in a soft rounded square, for list rows and cards. */
@Composable
fun IconBadge(icon: ImageVector, tone: Tone, size: Dp = 40.dp) {
    val (bg, fg) = toneColors(tone)
    Box(Modifier.size(size).clip(MaterialTheme.shapes.small).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * 0.5f), tint = fg)
    }
}

/** A tappable row: leading slot, title and subtitle, and a trailing slot (a chevron when tappable and nothing else). */
@Composable
fun ListItemRow(title: String, subtitle: String? = null, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null,
                leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .heightIn(min = 56.dp).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        when {
            trailing != null -> trailing()
            onClick != null -> Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A friendly empty state: icon, headline, one line, and an optional action. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        IconBadge(icon, Tone.ACCENT, 64.dp)
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) Box(Modifier.padding(top = 8.dp)) { action() }
    }
}
