package com.flow.adhd

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AppReleaseUpdatesTest {
    @Test fun recognizesOnlyTheCanonicalUploadedApkAndBuildsATrustedUrl() {
        val result = NativeAppUpdates.parseLatestRelease(
            releaseJson(
                tag = "v1.7.6.r10",
                assets = """
                    [{"id":42,"name":"Potik-v1.7.6.r10.apk","state":"uploaded",
                      "content_type":"application/vnd.android.package-archive",
                      "browser_download_url":"https://attacker.invalid/fake.apk"}]
                """.trimIndent(),
            ),
            installedVersion = "1.7.6.r9",
        )

        assertEquals("1.7.6.r9", result.installedVersion)
        assertEquals("1.7.6.r10", result.latestVersion)
        assertEquals(UpdateVersionComparison.NEWER, result.comparison)
        assertEquals(
            "https://github.com/Darksenius/Potik-ADHD/releases/download/v1.7.6.r10/Potik-v1.7.6.r10.apk",
            result.apkUrl,
        )
        assertEquals("https://github.com/Darksenius/Potik-ADHD/releases/tag/v1.7.6.r10", result.releaseUrl)
        assertEquals("2026-10-15T10:30:00Z", result.publishedAt)
        assertFalse(result.apkUrl!!.contains("attacker.invalid"))
    }

    @Test fun comparesVersionsNumericallyAndNormalizesNativeDebugSuffix() {
        val equal = parsed("v1.7.6.r10", "1.7.6.r10-native")
        val installedNewer = parsed("v1.7.6.r9", "1.7.6.r10")
        val unknownInstalled = parsed("v1.7.6.r10", "dev-build")

        assertEquals(UpdateVersionComparison.EQUAL, equal.comparison)
        assertEquals(UpdateVersionComparison.INSTALLED_NEWER, installedNewer.comparison)
        assertEquals(UpdateVersionComparison.UNKNOWN, unknownInstalled.comparison)
        assertEquals(UpdateVersionComparison.NEWER, parsed("v1.7.6.r10", "1.7.6.r9").comparison)
    }

    @Test fun missingOrUntrustedApkAssetKeepsTheReleasePageAvailable() {
        val result = NativeAppUpdates.parseLatestRelease(
            releaseJson(
                tag = "v1.7.6.r10",
                assets = """
                    [
                      {"id":0,"name":"Potik-v1.7.6.r10.apk","state":"uploaded","content_type":"application/octet-stream"},
                      {"id":43,"name":"Potik-v1.7.6.r10.apk","state":"new_upload","content_type":"application/vnd.android.package-archive"},
                      {"id":44,"name":"Other.apk","state":"uploaded","content_type":"application/vnd.android.package-archive"},
                      {"id":45,"name":"Potik-v1.7.6.r10.apk","state":"uploaded","content_type":"text/plain"}
                    ]
                """.trimIndent(),
            ),
            installedVersion = "1.7.6.r9",
        )

        assertNull(result.apkUrl)
        assertEquals("https://github.com/Darksenius/Potik-ADHD/releases/tag/v1.7.6.r10", result.releaseUrl)
    }

    @Test fun rejectsDraftPrereleaseMalformedAndNoncanonicalReleasePayloads() {
        listOf(
            releaseJson(draft = true),
            releaseJson(prerelease = true),
            releaseJson(tag = "1.7.6.r10"),
            releaseJson(tag = "v1.7.6"),
            releaseJson(tag = "v1.7.6-r10"),
            releaseJson(tag = "v1.7.6.r010"),
            releaseJson(tag = "v1.7.6.r100"),
            releaseJson(tag = "v1.100.6.r1"),
            "[]",
            "not json",
        ).forEach { body ->
            try {
                NativeAppUpdates.parseLatestRelease(body, "1.7.6.r9")
                fail("Expected invalid release payload to be rejected: $body")
            } catch (_: Exception) {
                // Invalid JSON and unsupported release data must never produce a download URL.
            }
        }
    }

    @Test fun doesNotFetchUntilRequestedAndCoalescesRepeatedChecks() = runBlocking {
        val fetchCount = AtomicInteger()
        val response = CompletableDeferred<String>()
        val updates = NativeAppUpdates("1.7.6.r9") {
            fetchCount.incrementAndGet()
            response.await()
        }
        try {
            assertEquals(AppUpdateState.Idle, updates.state.value)
            assertEquals(0, fetchCount.get())

            val first = updates.check()
            val duplicate = updates.check()
            assertSame(first, duplicate)
            assertEquals(AppUpdateState.Checking, updates.state.value)

            withTimeout(2_000) {
                while (fetchCount.get() == 0) delay(5)
            }
            assertEquals(1, fetchCount.get())
            response.complete(releaseJson(tag = "v1.7.6.r10"))
            first.join()

            val ready = updates.state.value as AppUpdateState.Ready
            assertEquals("1.7.6.r10", ready.result.latestVersion)
            assertEquals(1, fetchCount.get())
        } finally {
            updates.close()
        }
    }

    @Test fun failedCheckCanBeRetriedSuccessfully() = runBlocking {
        val fetchCount = AtomicInteger()
        val updates = NativeAppUpdates("1.7.6.r9") {
            if (fetchCount.incrementAndGet() == 1) throw IllegalStateException("offline")
            releaseJson(tag = "v1.7.6.r10")
        }
        try {
            updates.check().join()
            val failed = updates.state.value as AppUpdateState.Error
            assertTrue(failed.message.contains("Не вдалося перевірити оновлення"))
            assertEquals(1, fetchCount.get())

            updates.check().join()
            val ready = updates.state.value as AppUpdateState.Ready
            assertEquals("1.7.6.r10", ready.result.latestVersion)
            assertEquals(2, fetchCount.get())
        } finally {
            updates.close()
        }
    }

    private fun parsed(tag: String, installed: String) =
        NativeAppUpdates.parseLatestRelease(releaseJson(tag = tag), installed)

    private fun releaseJson(
        tag: String = "v1.7.6.r10",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: String = "[]",
    ) = """
        {
          "tag_name":"$tag",
          "draft":$draft,
          "prerelease":$prerelease,
          "published_at":"2026-10-15T10:30:00Z",
          "assets":$assets
        }
    """.trimIndent()
}
