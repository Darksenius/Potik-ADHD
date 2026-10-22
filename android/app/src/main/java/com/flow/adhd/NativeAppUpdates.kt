package com.flow.adhd

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

enum class UpdateVersionComparison {
    NEWER,
    EQUAL,
    INSTALLED_NEWER,
    UNKNOWN,
}

data class AppUpdateResult(
    val installedVersion: String,
    val latestVersion: String,
    val publishedAt: String?,
    val comparison: UpdateVersionComparison,
    /** Null means the latest release is valid but has no recognized APK asset. */
    val apkUrl: String?,
    val releaseUrl: String,
)

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data class Ready(val result: AppUpdateResult) : AppUpdateState
    data class Error(val message: String) : AppUpdateState
}

/** A user-triggered, read-only check of the public Potik GitHub release. */
class NativeAppUpdates(
    private val installedVersion: String = BuildConfig.VERSION_NAME,
    private val fetch: suspend () -> String = { Companion.fetchLatestReleaseJson() },
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()

    private var activeCheck: Job? = null

    /** Starts one request only when called; repeated taps join the request already in flight. */
    @Synchronized
    fun check(): Job {
        activeCheck?.takeIf { it.isActive }?.let { return it }

        mutableState.value = AppUpdateState.Checking
        val job = scope.launch {
            try {
                mutableState.value = AppUpdateState.Ready(parseLatestRelease(fetch(), installedVersion))
            } catch (cancelled: CancellationException) {
                mutableState.value = AppUpdateState.Idle
                throw cancelled
            } catch (failure: UpdateCheckFailure) {
                mutableState.value = AppUpdateState.Error(failure.userMessage)
            } catch (_: Exception) {
                mutableState.value = AppUpdateState.Error(GENERIC_CHECK_ERROR)
            }
        }
        activeCheck = job
        return job
    }

    /** Cancels the updater's work when its owner, such as the app container or a test, is disposed. */
    override fun close() {
        mutableState.value = AppUpdateState.Idle
        scope.cancel()
    }

    companion object {
        private const val GENERIC_CHECK_ERROR =
            "Не вдалося перевірити оновлення. Перевір з’єднання або відкрий сторінку релізів GitHub."

        private const val RELEASES_API_URL =
            "https://api.github.com/repos/Darksenius/Potik-ADHD/releases/latest"
        private const val REPOSITORY_URL = "https://github.com/Darksenius/Potik-ADHD"
        private const val MAX_RESPONSE_BYTES = 1024 * 1024

        /** Pure parser, visible to local unit tests without Android networking or JSON stubs. */
        internal fun parseLatestRelease(body: String, installedVersion: String): AppUpdateResult {
            val payload = try {
                Json.parseToJsonElement(body) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: throw UpdateCheckFailure("GitHub повернув некоректні дані про реліз.")

            if (payload.booleanField("draft") != false || payload.booleanField("prerelease") != false) {
                throw UpdateCheckFailure("Останній запис GitHub не є опублікованим стабільним релізом.")
            }

            val rawTag = payload.stringField("tag_name")
                ?: throw UpdateCheckFailure("У релізі GitHub немає коректної версії.")
            val parsed = parseVersion(rawTag)
                ?: throw UpdateCheckFailure("Формат версії релізу GitHub не підтримується.")
            val latestVersion = parsed.canonical
            val tag = "v$latestVersion"
            if (rawTag != tag) {
                throw UpdateCheckFailure("Позначка версії релізу GitHub не є канонічною.")
            }

            val apkName = "Potik-$tag.apk"
            val hasUploadedApk = (payload["assets"] as? JsonArray)
                ?.any { asset -> asset.isTrustedApkAsset(apkName) }
                ?: false
            val apkUrl = if (hasUploadedApk) {
                "$REPOSITORY_URL/releases/download/$tag/$apkName"
            } else {
                null
            }
            val releaseUrl = "$REPOSITORY_URL/releases/tag/$tag"
            val date = payload.stringField("published_at")
                ?.takeIf(::isValidInstant)
                ?: payload.stringField("created_at")?.takeIf(::isValidInstant)

            val installedParsed = parseVersion(installedVersion.removeSuffix("-native"))
            val comparison = when {
                installedParsed == null -> UpdateVersionComparison.UNKNOWN
                parsed > installedParsed -> UpdateVersionComparison.NEWER
                parsed < installedParsed -> UpdateVersionComparison.INSTALLED_NEWER
                else -> UpdateVersionComparison.EQUAL
            }

            return AppUpdateResult(
                installedVersion = installedVersion,
                latestVersion = latestVersion,
                publishedAt = date,
                comparison = comparison,
                apkUrl = apkUrl,
                releaseUrl = releaseUrl,
            )
        }

        private suspend fun fetchLatestReleaseJson(): String = runOnIo {
            val connection = (URL(RELEASES_API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Potik-Android-Updater")
            }

            try {
                val status = connection.responseCode
                if (status !in 200..299) throw httpFailure(status)
                connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_RESPONSE_BYTES) {
                            throw UpdateCheckFailure("Відповідь GitHub завелика, перевірку зупинено.")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toString(Charsets.UTF_8.name())
                }
            } catch (failure: UpdateCheckFailure) {
                throw failure
            } catch (failure: IOException) {
                throw UpdateCheckFailure(GENERIC_CHECK_ERROR, failure)
            } finally {
                connection.disconnect()
            }
        }

        private suspend fun <T> runOnIo(block: () -> T): T = withContext(Dispatchers.IO) { block() }

        private fun httpFailure(status: Int): UpdateCheckFailure = when (status) {
            403 -> UpdateCheckFailure("GitHub тимчасово обмежив перевірку. Спробуй пізніше або відкрий сторінку релізів.")
            404 -> UpdateCheckFailure("Публічний реліз GitHub не знайдено.")
            429 -> UpdateCheckFailure("GitHub отримав забагато запитів. Спробуй перевірити пізніше.")
            in 500..599 -> UpdateCheckFailure("GitHub тимчасово недоступний. Спробуй пізніше.")
            else -> UpdateCheckFailure("GitHub не зміг перевірити оновлення (HTTP $status).")
        }

        private fun JsonObject.stringField(name: String): String? =
            (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.booleanField(name: String): Boolean? =
            (this[name] as? JsonPrimitive)?.booleanOrNull

        private fun JsonElement.isTrustedApkAsset(expectedName: String): Boolean {
            val asset = this as? JsonObject ?: return false
            val idPrimitive = asset["id"] as? JsonPrimitive
            val id = idPrimitive?.takeUnless { it.isString }?.longOrNull
            return asset.stringField("name") == expectedName
                && asset.stringField("state") == "uploaded"
                && id != null && id > 0 && id <= MAX_SAFE_INTEGER
                && asset.stringField("content_type") in APK_CONTENT_TYPES
        }

        private fun JsonElement.stringField(name: String): String? =
            (this as? JsonObject)?.stringField(name)

        private fun isValidInstant(value: String): Boolean =
            runCatching { Instant.parse(value) }.isSuccess

        private fun parseVersion(value: String): AppVersion? {
            val match = VERSION_REGEX.matchEntire(value.trim()) ?: return null
            val parts = match.groupValues.drop(1).map { it.toLongOrNull() ?: return null }
            val (major, minor, patch, revision) = parts
            if (minor > 99 || patch > 99 || revision > 99) return null
            if (major > 2_100) return null
            val versionCode = major * 1_000_000L + minor * 10_000L + patch * 100L + revision
            if (versionCode !in 1..2_100_000_000L) return null
            return AppVersion(major, minor, patch, revision)
        }

        private val VERSION_REGEX = Regex("^v?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:\\.r|-r)(0|[1-9]\\d*)$", RegexOption.IGNORE_CASE)
        private val APK_CONTENT_TYPES = setOf("application/vnd.android.package-archive", "application/octet-stream")
        private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    }
}

private data class AppVersion(
    val major: Long,
    val minor: Long,
    val patch: Long,
    val revision: Long,
) : Comparable<AppVersion> {
    val canonical: String get() = "$major.$minor.$patch.r$revision"

    override fun compareTo(other: AppVersion): Int = compareValuesBy(
        this,
        other,
        AppVersion::major,
        AppVersion::minor,
        AppVersion::patch,
        AppVersion::revision,
    )
}

private class UpdateCheckFailure(
    val userMessage: String,
    cause: Throwable? = null,
) : Exception(userMessage, cause)
