package org.mega.entropy.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * megaWindowSizeClassForWidth() is a pure function specifically so these
 * breakpoint boundaries can be unit-tested on the JVM without pulling in
 * the Compose runtime or an instrumented device. The Composable behavior
 * built on top of it (MegaResponsiveContent centering/padding,
 * MegaListDetailPane's two-pane split, MegaInfoScaffold's scrollable
 * flag) is verified separately by the instrumented tests in
 * androidTest/.../ResponsiveLayoutInstrumentedTest.kt, and by the
 * on-device screenshots/dumps recorded for the tablet-layout change.
 */
class MegaWindowSizeClassTest {

    @Test
    fun `360dp phone width is Compact`() {
        assertEquals(MegaWindowSizeClass.Compact, megaWindowSizeClassForWidth(360))
    }

    @Test
    fun `just below 600dp is still Compact`() {
        assertEquals(MegaWindowSizeClass.Compact, megaWindowSizeClassForWidth(599))
    }

    @Test
    fun `600dp is Medium, not Compact`() {
        assertEquals(MegaWindowSizeClass.Medium, megaWindowSizeClassForWidth(600))
    }

    @Test
    fun `a large phone width in the Medium band is Medium`() {
        assertEquals(MegaWindowSizeClass.Medium, megaWindowSizeClassForWidth(700))
    }

    @Test
    fun `just below 840dp is still Medium`() {
        assertEquals(MegaWindowSizeClass.Medium, megaWindowSizeClassForWidth(839))
    }

    @Test
    fun `840dp tablet landscape width is Expanded, not Medium`() {
        assertEquals(MegaWindowSizeClass.Expanded, megaWindowSizeClassForWidth(840))
    }

    @Test
    fun `a very wide window stays Expanded`() {
        assertEquals(MegaWindowSizeClass.Expanded, megaWindowSizeClassForWidth(1400))
    }

    @Test
    fun `zero width does not crash and resolves to Compact`() {
        assertEquals(MegaWindowSizeClass.Compact, megaWindowSizeClassForWidth(0))
    }
}
