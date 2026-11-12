package ru.finassist.pf.core.blocks

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Test
import kotlin.test.assertEquals

class HelpPopupPlacementTest {
    @Test fun `place below when the top edge leaves no room above`() {
        assertEquals(
            HelpPopupPlacement(IntOffset(12, 48), aboveAnchor = false, arrowX = 38),
            helpPopupPlacement(IntRect(40, 28, 60, 48), IntSize(320, 600), LayoutDirection.Ltr, IntSize(240, 100), 12),
        )
    }

    @Test fun `place above and point at the anchor near the right edge`() {
        assertEquals(
            HelpPopupPlacement(IntOffset(68, 440), aboveAnchor = true, arrowX = 222),
            helpPopupPlacement(IntRect(280, 540, 300, 560), IntSize(320, 600), LayoutDirection.Ltr, IntSize(240, 100), 12),
        )
    }

    @Test fun `align the leading edge in rtl and choose the side with more room`() {
        assertEquals(
            HelpPopupPlacement(IntOffset(28, 68), aboveAnchor = false, arrowX = 22),
            helpPopupPlacement(IntRect(40, 80, 60, 100), IntSize(320, 220), LayoutDirection.Rtl, IntSize(240, 140), 12),
        )
    }
}
