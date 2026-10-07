package ru.finassist.pf.feature.auth.impl

import ru.finassist.pf.feature.auth.impl.domain.PhoneFormat
import kotlin.test.Test
import kotlin.test.assertEquals

class PhoneFormatTest {
    @Test
    fun cursorStaysAfterTypedDigitWhenMaskAddsCharacters() {
        // FIN-25: first digit gets the `+7 ` prefix, the 4th digit gets a space.
        assertEquals("+7 9", PhoneFormat.display("9"))
        assertEquals(4, PhoneFormat.digitToDisplay("9", 1))
        assertEquals("+7 904 5", PhoneFormat.display("9045"))
        assertEquals(8, PhoneFormat.digitToDisplay("9045", 4))
        assertEquals("+7 904 567-89-01", PhoneFormat.display("9045678901"))
        assertEquals(12, PhoneFormat.digitToDisplay("9045678901", 7))
    }

    @Test
    fun cursorInsideMaskMapsBackToDigits() {
        val d = "9045678901"
        assertEquals(0, PhoneFormat.displayToDigit(d, 0))
        assertEquals(0, PhoneFormat.displayToDigit(d, 3))
        assertEquals(3, PhoneFormat.displayToDigit(d, 7))
        assertEquals(10, PhoneFormat.displayToDigit(d, 16))
        for (o in 0..d.length) assertEquals(o, PhoneFormat.displayToDigit(d, PhoneFormat.digitToDisplay(d, o)))
    }

    @Test
    fun editDropsCountryPrefixAndKeepsCursor() {
        assertEquals(PhoneFormat.Edit("9161234567", 10, 10), PhoneFormat.edit("+7 (916) 123-45-67", 18, 18))
        assertEquals(PhoneFormat.Edit("916", 3, 3), PhoneFormat.edit("8916", 4, 4))
        assertEquals(PhoneFormat.Edit("9045", 2, 2), PhoneFormat.edit("9045", 2, 2))
        assertEquals(PhoneFormat.Edit("9045678901", 10, 10), PhoneFormat.edit("90456789012", 11, 11))
        assertEquals(PhoneFormat.Edit("", 0, 0), PhoneFormat.edit("", 0, 0))
    }

    @Test
    fun selectionRangeSurvives() {
        // «Select all» over the stored digits stays a range (review of PR #18).
        assertEquals(PhoneFormat.Edit("9045678901", 0, 10), PhoneFormat.edit("9045678901", 0, 10))
        assertEquals(PhoneFormat.Edit("9045678901", 3, 6), PhoneFormat.edit("9045678901", 3, 6))
        // A reversed selection (dragging handles backwards) keeps its direction.
        assertEquals(PhoneFormat.Edit("9045678901", 6, 3), PhoneFormat.edit("9045678901", 6, 3))
        // A pasted number with the country code: the range shifts with the dropped prefix.
        assertEquals(PhoneFormat.Edit("9161234567", 0, 10), PhoneFormat.edit("79161234567", 0, 11))
    }
}
