package ru.finassist.pf.core.common

import org.junit.Test
import ru.finassist.pf.core.common.money.Money
import kotlin.test.assertEquals

class MoneyTest {
    private val nbsp = " "
    private val minus = "−"

    @Test
    fun `formats thousands with narrow spaces and a typographic minus`() {
        assertEquals("${minus}84${nbsp}320${nbsp}₽", Money(-8_432_000).format())
        assertEquals("2${nbsp}340${nbsp}₽", Money(234_000).format())
        assertEquals("1${nbsp}086${nbsp}000${nbsp}₽", Money(108_600_000).format())
    }

    @Test
    fun `shows kopecks only when non-zero`() {
        assertEquals("1${nbsp}520,50${nbsp}₽", Money(152_050).format())
        assertEquals("0,05${nbsp}₽", Money(5).format())
        assertEquals("0${nbsp}₽", Money.ZERO.format())
    }

    @Test
    fun `sign modes`() {
        assertEquals("+1${nbsp}250${nbsp}₽", Money(125_000).format(Money.Sign.ALWAYS))
        assertEquals("20${nbsp}000${nbsp}₽", Money(-2_000_000).format(Money.Sign.NONE))
        assertEquals("0${nbsp}₽", Money.ZERO.format(Money.Sign.ALWAYS))
    }

    @Test
    fun `money in a currency - rubles keep the sign, other currencies get their symbol, an unknown code stays a code`() {
        val nbsp = Money.NBSP
        assertEquals("1${nbsp}250${nbsp}₽", Money.ofRubles(1250).format("RUB"))
        assertEquals("+1${nbsp}250${nbsp}₽", Money.ofRubles(1250).format("RUB", Money.Sign.ALWAYS))
        assertEquals("${Money.MINUS}12,50${nbsp}€", Money(-1250).format("EUR"))
        assertEquals("100${nbsp}$", Money.ofRubles(100).format("USD"))
        assertEquals("100${nbsp}XXX1", Money.ofRubles(100).format("XXX1"))
    }
}
