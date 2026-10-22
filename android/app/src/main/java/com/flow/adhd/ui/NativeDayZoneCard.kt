package com.flow.adhd.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flow.adhd.domain.FlowSnapshot
import com.flow.adhd.domain.QuestPolicy
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** The original HTML zone strip: one accent rail, current context and a clock. */
@Composable
fun NativeDayZoneCard(snapshot: FlowSnapshot, context: QuestPolicy.Context, onOpenPlan: (() -> Unit)? = null, compact: Boolean = false) {
    val local = Instant.ofEpochMilli(context.now).atZone(context.zone)
    val clock = DateTimeFormatter.ofPattern("HH:mm")
    val current = context.timeline.firstOrNull { it.startAt <= context.now && context.now < it.endAt }
    val zone = snapshot.zones.firstOrNull { it.zoneId() == current?.zoneId }
    val accent = runCatching { Color(android.graphics.Color.parseColor(zone?.zoneText("color"))) }
        .getOrDefault(MaterialTheme.colorScheme.secondary)
    val surface = MaterialTheme.colorScheme.surface
    // Keep user-chosen colors readable in either theme without changing the stored accent.
    var nameInk = accent
    repeat(10) {
        val foreground = nameInk.luminance()
        val background = surface.luminance()
        if ((maxOf(foreground, background) + .05f) / (minOf(foreground, background) + .05f) < 4.5f)
            nameInk = lerp(nameInk, MaterialTheme.colorScheme.onSurface, .2f)
    }
    val name = zone?.zoneText("nm").orEmpty().ifBlank { "Вільний час" }
    val description = zone?.zoneText("desc").orEmpty()
    val range = current?.takeIf { it.zoneId != 0L }?.let {
        val start = Instant.ofEpochMilli(it.startAt).atZone(context.zone).format(clock)
        val end = Instant.ofEpochMilli(it.endAt).atZone(context.zone)
        start + "–" + if (end.toLocalDate() > local.toLocalDate() && end.toLocalTime() == java.time.LocalTime.MIDNIGHT) "24:00" else end.format(clock)
    }
    Card(Modifier.fillMaxWidth().testTag("native-current-zone"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = nameInk, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("native-current-zone-name"))
                Text(if (description.isNotBlank()) description else range ?: "Без активної зони",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(vertical = 10.dp)) {
                Text(local.format(clock), style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag("native-zone-clock"))
                if (!compact && description.isNotBlank() && range != null) Text(range, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onOpenPlan != null) IconButton(onClick = onOpenPlan,
                modifier = Modifier.size(48.dp).testTag("native-zone-open-plan").semantics { contentDescription = "План дня" }) {
                Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else Spacer(Modifier.width(12.dp))
        }
    }
}

private fun JsonObject.zoneText(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
private fun JsonObject.zoneId() = (this["id"] as? JsonPrimitive)?.longOrNull
