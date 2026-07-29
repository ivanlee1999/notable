package com.ethran.notable.editor

import androidx.compose.material.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.testing.ComposeUiSupportRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose harness smoke test. Gated by [ComposeUiSupportRule] so it is skipped (reported as ignored,
 * not hung) on devices whose Compose UI-test harness wedges — e.g. Redmi Note 9 Pro / LineageOS.
 * See docs/plans/instrumented-test-reliability-plan.md.
 */
@RunWith(AndroidJUnit4::class)
class SimpleComposeTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val composeGate = ComposeUiSupportRule(composeRule)

    @Test
    fun simpleTest() {
        android.util.Log.i("SimpleComposeTest", "Starting simpleTest")
        composeRule.setContent {
            Text("Hello World")
        }
        android.util.Log.i("SimpleComposeTest", "Content set")
        composeRule.waitForIdle()
        android.util.Log.i("SimpleComposeTest", "Done")
    }
}
