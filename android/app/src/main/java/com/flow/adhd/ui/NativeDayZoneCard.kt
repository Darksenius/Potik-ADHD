package com.flow.adhd.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flow.adhd.domain.FlowSnapshot
import com.flow.adhd.domain.QuestPolicy
import com.flow.adhd.domain.ZoneCalendar
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** The automatic day context stays above the full task list, as in the original Potik. */
@Composable
fun NativeDayZoneCard(snapshot: FlowSnapshot, context: QuestPolicy.Context, onOpenPlan: (() -> Unit)? = null, compact: Boolean = false) {
    val local = Instant.ofEpochMilli(context.now).atZone(context.zone)
    val day = local.toLocalDate()
    val dayStart = day.atStartOfDay(context.zone).toInstant().toEpochMilli()
    val dayEnd = day.plusDays(1).atStartOfDay(context.zone).toInstant().toEpochMilli()
    val clock = DateTimeFormatter.ofPattern("HH:mm")
    fun clockAt(at: Long) = Instant.ofEpochMilli(at).atZone(context.zone).format(clock)
    val zones = snapshot.zones.associateBy { it.zoneId() }
    val current = context.timeline.firstOrNull { it.startAt <= context.now && context.now < it.endAt }
    val zone = current?.zoneId?.let(zones::get)
    val fallback = MaterialTheme.colorScheme.secondary
    fun color(item: JsonObject?) = runCatching { Color(android.graphics.Color.parseColor(item?.zoneText("color"))) }.getOrDefault(fallback)
    val accent = color(zone)
    val name = zone?.zoneText("nm").orEmpty().ifBlank { "Вільний час" }
    val description = zone?.zoneText("desc").orEmpty()
    val focus = snapshot.data.zoneText("currentFocus").takeIf { it.isNotBlank() && it != name && it != description }
    val range = current?.takeIf { it.zoneId != 0L }?.let {
        clockAt(maxOf(dayStart, it.startAt)) + "–" + if (it.endAt >= dayEnd) "24:00" else clockAt(it.endAt)
    }
    val minute = local.hour * 60 + local.minute
    val others = ZoneCalendar.dayRanges(snapshot.data, day.toString())
        .filter { minute >= it.start && minute < it.end && it.zoneId != current?.zoneId }
        .sortedByDescending { it.priority }.distinctBy { it.zoneId }.take(2)
        .mapNotNull { zones[it.zoneId]?.zoneText("nm")?.takeIf(String::isNotBlank) }
    val next = context.timeline.firstOrNull { it.zoneId != 0L && it.startAt > context.now }
    val nextText = next?.let {
        val date = Instant.ofEpochMilli(it.startAt).atZone(context.zone).toLocalDate()
        val whenText = when (date) {
            day -> clockAt(it.startAt)
            day.plusDays(1) -> "завтра о ${clockAt(it.startAt)}"
            else -> "${date.format(DateTimeFormatter.ofPattern("dd.MM"))} о ${clockAt(it.startAt)}"
        }
        "Далі: ${zones[it.zoneId]?.zoneText("nm").orEmpty().ifBlank { "Зона" }} · $whenText"
    } ?: if (snapshot.zones.isEmpty()) "Зони ще не налаштовані." else "Далі — вільний час."
    val ink = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    val track = MaterialTheme.colorScheme.outlineVariant

    Card(Modifier.fillMaxWidth().testTag("native-current-zone"),
        colors = CardDefaults.cardColors(containerColor = surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 2.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("native-current-zone-name"))
                        if (!compact && range != null) Text(range, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(local.format(clock), style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.testTag("native-zone-clock"))
                }
                if (!compact && description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!compact && others.isNotEmpty()) Text("Також: ${others.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!compact && focus != null) Text("Фокус: $focus", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!compact) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Час дня", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Canvas(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(track)
                        .semantics { contentDescription = "Час дня: ${local.format(clock)}" }.testTag("native-day-time-track")) {
                        val duration = (dayEnd - dayStart).toDouble()
                        fun x(at: Long) = ((at - dayStart) / duration).toFloat().coerceIn(0f, 1f) * size.width
                        context.timeline.filter { it.startAt < dayEnd && it.endAt > dayStart && it.zoneId != 0L }.forEach {
                            val left = x(it.startAt); val right = x(it.endAt)
                            drawRect(color(zones[it.zoneId]).copy(alpha = .6f), Offset(left, 0f), Size(right - left, size.height))
                        }
                        val marker = Offset(x(context.now).coerceIn(4.dp.toPx(), (size.width - 4.dp.toPx()).coerceAtLeast(4.dp.toPx())), size.height / 2)
                        drawCircle(surface, 4.dp.toPx(), marker)
                        drawCircle(ink, 3.dp.toPx(), marker, style = Stroke(1.5.dp.toPx()))
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (compact) range ?: "Без активної зони" else nextText, Modifier.weight(1f).testTag("native-next-zone"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (onOpenPlan != null) TextButton(onClick = onOpenPlan,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("native-zone-open-plan")) { Text(if (compact) "План" else "План дня") }
                    else Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

private fun JsonObject.zoneText(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
private fun JsonObject.zoneId() = (this["id"] as? JsonPrimitive)?.longOrNull
