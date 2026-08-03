package com.ethran.notable.testing

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Whether the Compose UI-test harness works on the current device.
 *
 * On some devices (verified: Redmi Note 9 Pro / LineageOS, `Build.DEVICE == "joyeuse"`) *any* Compose
 * test rule wedges forever: `AndroidComposeTestRule.evaluate()` → `Espresso.onIdle()` parks on a
 * `FutureTask` that never completes because the Compose `IdlingResource` never reports idle — this
 * happens before the test body runs, so an in-body `Assume` can't rescue it. Such tests must instead
 * be gated with [ComposeUiSupportRule], which skips them *before* the Compose rule is applied.
 *
 * Decision order:
 *  1. Explicit override via instrumentation arg `-e composeUi true|false`.
 *  2. Otherwise: supported unless the device is on [DENYLISTED_DEVICES].
 */
object ComposeUiTestSupport {
    /** `Build.DEVICE` values known to wedge the Compose UI-test harness. */
    private val DENYLISTED_DEVICES = setOf("joyeuse") // Redmi Note 9 Pro (LineageOS)

    val isSupported: Boolean
        get() {
            InstrumentationRegistry.getArguments().getString("composeUi")?.let {
                return it.equals("true", ignoreCase = true)
            }
            return Build.DEVICE !in DENYLISTED_DEVICES
        }

    val skipReason: String
        get() = "Compose UI-test harness wedges on this device " +
            "(${Build.MANUFACTURER} ${Build.MODEL} / device=${Build.DEVICE}): Espresso.onIdle never " +
            "settles. Skipped to keep the run from hanging. Override with -e composeUi true."
}

/**
 * Wraps a Compose [TestRule] so that on devices where the Compose harness wedges the test is SKIPPED
 * (JUnit assumption failure → reported as *ignored*, not failed) **before** the Compose rule is
 * applied — never letting its `Espresso.onIdle()` wedge run. On supported devices it delegates to the
 * real rule unchanged.
 *
 * Usage:
 * ```
 * private val composeRule = createComposeRule()
 * @get:Rule val composeGate = ComposeUiSupportRule(composeRule)
 * ```
 */
class ComposeUiSupportRule(private val delegate: TestRule) : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                Assume.assumeTrue(ComposeUiTestSupport.skipReason, ComposeUiTestSupport.isSupported)
                delegate.apply(base, description).evaluate()
            }
        }
}
