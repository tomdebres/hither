package com.tomdebres.hither

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app starts and shows its first screen: the sign-in form with the wordmark on a device
 * with no account, Discover's bar on one that has one. Needs a device or emulator; CI
 * compiles it only.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun shown(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun showsTheSignInFormOrDiscover() {
        rule.waitUntil(10_000) { shown("wordmark") || shown("bar") }
        rule.onNodeWithTag(if (shown("wordmark")) "wordmark" else "bar").assertIsDisplayed()
    }
}
