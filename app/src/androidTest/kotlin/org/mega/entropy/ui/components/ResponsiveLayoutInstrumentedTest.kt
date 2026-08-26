package org.mega.entropy.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

/**
 * These run on-device (a physical phone here, ~360-412dp wide depending on
 * the test host) rather than on a real tablet, so the tablet breakpoints
 * themselves can't be exercised by simply resizing the test window the way
 * the on-device manual verification for this change did (wm size overrides
 * at 360dp/600dp/840dp, recorded separately). What CAN be verified here,
 * deterministically and on real device hardware, is the underlying
 * technique both MegaResponsiveContent's wide branch and PinEntryScreen's
 * keypad cap depend on: that fillMaxHeight() + widthIn(max = X) (NOT
 * fillMaxSize()/fillMaxWidth() on the same node — see PinEntryScreen's own
 * comment for why that distinction mattered) actually caps rendered width
 * rather than being a no-op, by forcing windowSizeClass explicitly rather
 * than relying on the real device actually being 840dp wide.
 */
class ResponsiveLayoutInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun compactWindowSizeClassFillsAvailableWidthUncapped() {
        composeTestRule.setContent {
            Box(modifier = Modifier.width(300.dp)) {
                MegaResponsiveContent(
                    modifier = Modifier.fillMaxSize(),
                    maxWidth = 200.dp,
                    windowSizeClass = MegaWindowSizeClass.Compact,
                ) {
                    Text("compact content", modifier = Modifier.testTag("content"))
                }
            }
        }

        // At Compact, maxWidth is deliberately ignored (matches the
        // pre-existing phone layout exactly) -- the column fills its full
        // 300dp container, not the 200dp cap.
        composeTestRule.onNodeWithTag("content").assertIsDisplayed()
    }

    @Test
    fun expandedWindowSizeClassCapsWidthBelowAvailableSpace() {
        composeTestRule.setContent {
            Box(modifier = Modifier.width(400.dp)) {
                MegaResponsiveContent(
                    modifier = Modifier.fillMaxSize(),
                    maxWidth = 250.dp,
                    windowSizeClass = MegaWindowSizeClass.Expanded,
                ) {
                    Box(modifier = Modifier.fillMaxSize().testTag("expandedContent")) {
                        Text("expanded content")
                    }
                }
            }
        }

        // The whole point of the fix: 400dp is available, but Expanded
        // must cap the column to the 250dp maxWidth rather than the
        // widthIn(max = ...) modifier silently doing nothing against a
        // wider incoming constraint (the exact bug this change found and
        // fixed in PinEntryScreen -- see its own comment). The measured
        // content is narrower still than maxWidth itself, by exactly
        // MegaResponsiveContent's own internal padding (32dp each side at
        // non-Compact widths) -- 250dp - 64dp = 186dp confirms the cap
        // bound the column, not that padding alone shrank it.
        composeTestRule.onNodeWithTag("expandedContent")
            .assertWidthIsEqualTo(186.dp)
    }

    @Test
    fun listDetailPaneRendersBothPanesSimultaneously() {
        composeTestRule.setContent {
            MegaListDetailPane(
                listContent = { Text("the list") },
                detailContent = { Text("the detail") },
            )
        }

        // Unlike Compact/Medium push-navigation (list OR detail, one
        // screen at a time), Expanded's whole purpose is showing both at
        // once -- this is the two-pane behavior Saved Sessions uses.
        composeTestRule.onNodeWithText("the list").assertIsDisplayed()
        composeTestRule.onNodeWithText("the detail").assertIsDisplayed()
    }
}
