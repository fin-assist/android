package ru.finassist.pf.feature.profile.impl.ui

import ru.finassist.pf.core.api.model.Theme
import ru.finassist.pf.core.designsystem.icons.PfIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class ThemeIconTest {
    @Test fun `each theme option has its icon`() {
        assertEquals(PfIcons.SMARTPHONE, themeIcon(Theme.SYSTEM))
        assertEquals(PfIcons.SUN, themeIcon(Theme.LIGHT))
        assertEquals(PfIcons.MOON, themeIcon(Theme.DARK))
    }
}
