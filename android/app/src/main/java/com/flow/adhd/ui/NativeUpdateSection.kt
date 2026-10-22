package com.flow.adhd.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.flow.adhd.AppUpdateState
import com.flow.adhd.BuildConfig
import com.flow.adhd.UpdateVersionComparison
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Displays a read-only check result. Opening Settings never starts a network request. */
@Composable
fun NativeUpdateSection(state: AppUpdateState, onCheck: () -> Unit, onOpenUrl: (String) -> Unit) {
    Text("Встановлено: ${BuildConfig.VERSION_NAME}")
    when (state) {
        AppUpdateState.Idle -> Text("Перевір, чи є свіжіша версія на GitHub.")
        AppUpdateState.Checking -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            Text("Перевіряю GitHub…")
        }
        is AppUpdateState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error)
        is AppUpdateState.Ready -> {
            val result = state.result
            Text("На GitHub: ${result.latestVersion}")
            result.publishedAt?.let { date ->
                val formatted = runCatching { DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.forLanguageTag("uk-UA"))
                    .withZone(ZoneId.systemDefault()).format(Instant.parse(date)) }.getOrNull()
                if (formatted != null) Text("Опубліковано: $formatted", style = MaterialTheme.typography.bodyMedium)
            }
            Text(when (result.comparison) {
                UpdateVersionComparison.NEWER -> "Доступне оновлення Потоку."
                UpdateVersionComparison.EQUAL -> "У тебе актуальна версія."
                UpdateVersionComparison.INSTALLED_NEWER -> "Твоя версія новіша за останній публічний реліз."
                UpdateVersionComparison.UNKNOWN -> "Не вдалося порівняти версії. Можна переглянути реліз на GitHub."
            })
            if (result.comparison == UpdateVersionComparison.NEWER) {
                if (result.apkUrl != null) {
                    Button(onClick = { onOpenUrl(result.apkUrl) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("native-update-download")) {
                        Text("Завантажити APK")
                    }
                    Text("Браузер завантажить файл, потім відкрий його. Android попросить підтвердити встановлення. Дані Потоку збережуться при оновленні поверх поточної версії.", style = MaterialTheme.typography.bodyMedium)
                } else Text("Реліз є, але APK ще не завантажено. Спробуй перевірити пізніше.")
            }
        }
    }
    Button(onClick = onCheck, enabled = state != AppUpdateState.Checking,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("native-update-check")) {
        Text(if (state == AppUpdateState.Checking) "Перевіряю…" else "Перевірити оновлення")
    }
    val releaseUrl = (state as? AppUpdateState.Ready)?.result?.releaseUrl
        ?: "https://github.com/Darksenius/Potik-ADHD/releases/latest"
    OutlinedButton(onClick = { onOpenUrl(releaseUrl) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text("Відкрити реліз на GitHub")
    }
}
