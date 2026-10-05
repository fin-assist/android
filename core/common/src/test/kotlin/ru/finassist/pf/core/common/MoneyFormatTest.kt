package ru.finassist.pf.core.common

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.money.MoneyFormat
import ru.finassist.pf.core.common.money.SignStyle

class MoneyFormatTest {
    private val nb = " "
    private val minus = "−"

    @Test fun expenseUsesTrueMinusAndNbsp() =
        assertEquals("${minus}84${nb}320${nb}₽", MoneyFormat.rub(Money(8_432_000), SignStyle.Expense))

    @Test fun incomeUsesPlus() =
        assertEquals("+1${nb}250${nb}₽", MoneyFormat.rub(Money(125_000), SignStyle.Income))

    @Test fun plainHidesSignForPositives() =
        assertEquals("20${nb}000${nb}₽", MoneyFormat.rub(Money(2_000_000)))

    @Test fun signedNegativeAnalytics() =
        assertEquals("${minus}12${nb}500${nb}₽", MoneyFormat.signed(Money(-1_250_000)))

    @Test fun kopecksShownOnlyWhenPresent() =
        assertEquals("1${nb}520,50${nb}₽", MoneyFormat.rub(Money(152_050)))

    @Test fun negativeExpenseIsARefund() =
        assertEquals("+1${nb}200${nb}₽", MoneyFormat.rub(Money(-120_000), SignStyle.Expense))

    @Test fun zeroExpenseHasNoSign() =
        assertEquals("0${nb}₽", MoneyFormat.rub(Money.ZERO, SignStyle.Expense))
}
