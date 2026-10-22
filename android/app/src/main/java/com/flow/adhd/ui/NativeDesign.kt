package com.flow.adhd.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable internal fun PotikMenuRow(title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A circular visual mark with a square 48dp touch target in every task list. */
@Composable internal fun PotikCompletionTarget(checked: Boolean, enabled: Boolean, label: String,
    modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    val ink = (if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
        .copy(alpha = if (enabled) 1f else .4f)
    Box(modifier.size(48.dp).clip(CircleShape).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
        .semantics { contentDescription = label; stateDescription = if (checked) "Виконано" else "Не виконано" }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(24.dp).border(2.dp, ink, CircleShape), contentAlignment = Alignment.Center) {
            if (checked) Text("✓", color = ink, style = MaterialTheme.typography.labelMedium)
        }
    }
}
