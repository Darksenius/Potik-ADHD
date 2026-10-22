package com.flow.adhd

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flow.adhd.ui.FlowTheme
import com.flow.adhd.ui.NativeUpdateSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The release section remains read-only until the user explicitly taps an action. */
@RunWith(AndroidJUnit4::class)
class NativeAppUpdatesUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun idleDoesNotCheckUntilTappedAndCheckingDisablesTheCheckButton() {
        var checks = 0
        val harness = mountSection()
        harness.onCheck.value = { checks++ }

        compose.runOnIdle { assertEquals("Composition must not start a network check", 0, checks) }
        compose.onNodeWithTag("native-update-check").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, checks) }

        harness.state.value = AppUpdateState.Checking
        harness.onCheck.value = { checks++ }
        compose.onNodeWithTag("native-update-check").assertIsNotEnabled()
        compose.runOnIdle { assertEquals("A disabled button must not request another check", 1, checks) }
    }

    @Test
    fun newerApkOpensOnlyTheExactAssetUrlAndReleaseLink() {
        val apk = "https://github.com/Darksenius/Potik-ADHD/releases/download/v9.8.7/Potik.apk"
        val release = "https://github.com/Darksenius/Potik-ADHD/releases/tag/v9.8.7"
        val opened = mutableListOf<String>()
        val harness = mountSection()
        harness.onOpenUrl.value = { opened += it }
        harness.state.value = AppUpdateState.Ready(result(
            latest = "9.8.7", comparison = UpdateVersionComparison.NEWER, apk = apk, release = release,
        ))

        compose.onNodeWithText("Завантажити APK").performScrollTo().performClick()
        compose.onNodeWithText("Відкрити реліз на GitHub").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(apk, release), opened) }
        compose.onNodeWithTag("native-update-download").assertTextContains("Завантажити APK")
    }

    @Test
    fun equalAndInstalledNewerVersionsDoNotOfferAnApkDownload() {
        val harness = mountSection()
        var opened = false
        harness.onOpenUrl.value = { opened = true }
        listOf(
            Triple("9.8.6", UpdateVersionComparison.EQUAL, "У тебе актуальна версія."),
            Triple("9.8.5", UpdateVersionComparison.INSTALLED_NEWER, "Твоя версія новіша за останній публічний реліз."),
        ).forEach { (latest, comparison, message) ->
            harness.state.value = AppUpdateState.Ready(result(
                latest = latest, comparison = comparison,
                apk = "https://example.test/should-not-open.apk",
            ))
            compose.onNodeWithText(message).assertExists()
            compose.onNodeWithText("Завантажити APK").assertDoesNotExist()
            compose.runOnIdle { assertFalse("This version state must not auto-open a download", opened) }
        }
    }

    @Test
    fun missingApkAndErrorsKeepTheirMessagesAndAWorkingReleaseLink() {
        val release = "https://github.com/Darksenius/Potik-ADHD/releases/tag/v9.8.7"
        var opened: String? = null
        val harness = mountSection()
        harness.onOpenUrl.value = { opened = it }
        harness.state.value = AppUpdateState.Ready(result(
            latest = "9.8.7", comparison = UpdateVersionComparison.NEWER, apk = null, release = release,
        ))
        compose.onNodeWithText("Реліз є, але APK ще не завантажено. Спробуй перевірити пізніше.").assertExists()
        compose.onNodeWithText("Завантажити APK").assertDoesNotExist()
        compose.onNodeWithText("Відкрити реліз на GitHub").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(release, opened) }

        val error = "Не вдалося перевірити: перевір з’єднання."
        opened = null
        harness.state.value = AppUpdateState.Error(error)
        compose.onNodeWithText(error).assertExists()
        compose.onNodeWithText("Відкрити реліз на GitHub").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("https://github.com/Darksenius/Potik-ADHD/releases/latest", opened)
        }
    }

    private fun mountSection(): Harness {
        val state = mutableStateOf<AppUpdateState>(AppUpdateState.Idle)
        val onCheck = mutableStateOf<() -> Unit>({})
        val onOpenUrl = mutableStateOf<(String) -> Unit>({})
        compose.setContent {
            val systemDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(systemDensity.density, fontScale = 1.3f)) {
                FlowTheme {
                    Column(Modifier.widthIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        NativeUpdateSection(state.value, { onCheck.value() }, { onOpenUrl.value(it) })
                    }
                }
            }
        }
        return Harness(state, onCheck, onOpenUrl)
    }

    private fun result(
        latest: String,
        comparison: UpdateVersionComparison,
        apk: String?,
        release: String = "https://github.com/Darksenius/Potik-ADHD/releases/latest",
    ) = AppUpdateResult(
        installedVersion = "9.8.6",
        latestVersion = latest,
        publishedAt = "2026-10-20T12:00:00Z",
        comparison = comparison,
        apkUrl = apk,
        releaseUrl = release,
    )

    private data class Harness(
        val state: MutableState<AppUpdateState>,
        val onCheck: MutableState<() -> Unit>,
        val onOpenUrl: MutableState<(String) -> Unit>,
    )
}
