package ru.finassist.pf.feature.applock.impl.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import ru.finassist.pf.feature.applock.api.AppLock
import ru.finassist.pf.feature.applock.api.BiometricAvailability
import ru.finassist.pf.feature.applock.api.LockState
import ru.finassist.pf.feature.applock.api.VerifyResult
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PasscodeViewModelsTest {
    private class FakeAppLock(
        private val code: String = "1234",
        private val availability: BiometricAvailability = BiometricAvailability.NONE,
    ) : AppLock {
        override val state = MutableStateFlow<LockState>(LockState.Unlocked)
        override val biometricEnabled = MutableStateFlow(false)
        var signedOut = false
        val saved = mutableListOf<String>()
        val biometricSwitches = mutableListOf<Boolean>()
        var biometricUnlocks = 0
        /** Results [verify] returns for a wrong code, in order; then `Wrong(3)`. */
        val wrongResults = ArrayDeque<VerifyResult>()
        /** When set, [verify] and [matches] wait for it: the screen is «busy» meanwhile. */
        var gate: CompletableDeferred<Unit>? = null
        override fun biometricAvailability() = availability
        override suspend fun setPasscode(code: String) { saved += code }
        override suspend fun verify(code: String): VerifyResult {
            gate?.await()
            return if (code == this.code) VerifyResult.Ok else wrongResults.removeFirstOrNull() ?: VerifyResult.Wrong(3)
        }
        override suspend fun matches(code: String): Boolean {
            gate?.await()
            return code == this.code
        }
        override suspend fun setBiometricEnabled(enabled: Boolean) {
            biometricSwitches += enabled
            biometricEnabled.value = enabled
        }
        override fun unlockByBiometric() { biometricUnlocks++ }
        override fun lock() = Unit
        override suspend fun forgetAndSignOut() { signedOut = true }
        override suspend fun reset() = Unit
    }

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun ConfirmPasscodeViewModel.type(code: String) = code.forEach { digit(it.digitToInt()) }
    private fun PasscodeEntryViewModel.type(code: String) = code.forEach { digit(it.digitToInt()) }
    private fun UnlockViewModel.type(code: String) = code.forEach { digit(it.digitToInt()) }

    @Test fun `wrong code messages name the attempts left`() {
        assertEquals("Неверный код. Осталось 4 попытки", wrongCodeMessage(4))
        assertEquals("Неверный код. Осталось 3 попытки", wrongCodeMessage(3))
        assertEquals("Неверный код. Осталось 2 попытки", wrongCodeMessage(2))
        assertEquals(
            "Неверный код. Осталась 1 попытка. После неё войти можно будет только по номеру телефона",
            wrongCodeMessage(1),
        )
    }

    @Test fun `the right code confirms`() {
        val vm = ConfirmPasscodeViewModel(FakeAppLock())
        vm.type("1234")
        assertTrue(vm.state.value.confirmed)
    }

    @Test fun `a wrong code keeps the dots filled until the next digit`() {
        val vm = ConfirmPasscodeViewModel(FakeAppLock())
        vm.type("1111")
        assertEquals("1111", vm.state.value.entered)
        assertEquals(CONFIRM_WRONG_MESSAGE, vm.state.value.error)
        assertFalse(vm.state.value.confirmed)

        vm.digit(1)
        assertEquals("1", vm.state.value.entered)
        assertNull(vm.state.value.error)
        vm.type("234")
        assertTrue(vm.state.value.confirmed)
    }

    @Test fun `delete after a wrong code clears the whole code`() {
        val vm = ConfirmPasscodeViewModel(FakeAppLock())
        vm.type("1111")
        vm.delete()
        assertEquals("", vm.state.value.entered)
        assertNull(vm.state.value.error)
        vm.type("12")
        vm.delete()
        assertEquals("1", vm.state.value.entered)
    }

    @Test fun `forgot code asks first and then signs out`() {
        val lock = FakeAppLock()
        val vm = ConfirmPasscodeViewModel(lock)
        vm.askForgot(true)
        assertTrue(vm.state.value.askForgot)
        vm.askForgot(false)
        assertFalse(vm.state.value.askForgot)
        vm.askForgot(true)
        vm.forgot()
        assertTrue(lock.signedOut)
        assertFalse(vm.state.value.askForgot)
    }

    @Test fun `a mismatch on repeat returns to the first entry with the message`() {
        val vm = PasscodeEntryViewModel(FakeAppLock())
        vm.configure(changeExisting = false)
        "1234".forEach { vm.digit(it.digitToInt()) }
        "4321".forEach { vm.digit(it.digitToInt()) }
        assertEquals(EnterStep.NEW, vm.state.value.step)
        assertEquals("Коды не совпадают — введите ещё раз", vm.state.value.error)
    }

    @Test fun `setup without biometrics saves the repeated code and finishes`() {
        val lock = FakeAppLock()
        val vm = PasscodeEntryViewModel(lock)
        vm.configure(changeExisting = false)
        assertEquals(EnterStep.NEW, vm.state.value.step)
        vm.type("1357")
        assertEquals(EnterStep.REPEAT, vm.state.value.step)
        assertEquals("", vm.state.value.entered)
        vm.type("1357")
        assertEquals(listOf("1357"), lock.saved)
        assertTrue(vm.state.value.done)
        assertFalse(vm.state.value.busy)
    }

    @Test fun `setup on a device with biometrics offers them after the code`() {
        val lock = FakeAppLock(availability = BiometricAvailability.FACE)
        val vm = PasscodeEntryViewModel(lock)
        vm.configure(changeExisting = false)
        assertEquals(BiometricAvailability.FACE, vm.state.value.biometric)
        vm.type("1357")
        vm.type("1357")
        assertEquals(EnterStep.BIOMETRIC, vm.state.value.step)
        assertFalse(vm.state.value.done)

        vm.enableBiometric()
        assertEquals(listOf(true), lock.biometricSwitches)
        assertTrue(vm.state.value.done)
    }

    @Test fun `skipping the biometric offer finishes without enabling them`() {
        val lock = FakeAppLock(availability = BiometricAvailability.FINGERPRINT)
        val vm = PasscodeEntryViewModel(lock)
        vm.configure(changeExisting = false)
        vm.type("1357")
        vm.type("1357")
        vm.skipBiometric()
        assertTrue(vm.state.value.done)
        assertTrue(lock.biometricSwitches.isEmpty())
    }

    @Test fun `changing the code checks the current one first and offers no biometrics`() {
        val lock = FakeAppLock(availability = BiometricAvailability.FINGERPRINT)
        val vm = PasscodeEntryViewModel(lock)
        vm.configure(changeExisting = true)
        assertEquals(EnterStep.OLD, vm.state.value.step)

        vm.type("0000")
        assertEquals(EnterStep.OLD, vm.state.value.step)
        assertEquals(CONFIRM_WRONG_MESSAGE, vm.state.value.error)
        assertEquals("", vm.state.value.entered)

        vm.digit(1)
        assertNull(vm.state.value.error, "typing again clears the error")
        vm.type("234")
        assertEquals(EnterStep.NEW, vm.state.value.step)
        vm.type("5678")
        vm.type("5678")
        assertEquals(listOf("5678"), lock.saved)
        assertTrue(vm.state.value.done, "no biometric step when changing the code")
    }

    @Test fun `configure is ignored once typing started`() {
        val vm = PasscodeEntryViewModel(FakeAppLock())
        vm.configure(changeExisting = false)
        vm.digit(1)
        vm.configure(changeExisting = true) // recomposition after a configuration change
        assertEquals(EnterStep.NEW, vm.state.value.step)
        assertEquals("1", vm.state.value.entered)
    }

    @Test fun `delete drops the last digit and back from repeat restarts the entry`() {
        val vm = PasscodeEntryViewModel(FakeAppLock())
        vm.configure(changeExisting = false)
        assertFalse(vm.backToFirstEntry(), "nothing to go back to on the first entry")
        vm.type("12")
        vm.delete()
        assertEquals("1", vm.state.value.entered)
        vm.type("234")
        assertEquals(EnterStep.REPEAT, vm.state.value.step)
        vm.digit(9)
        assertTrue(vm.backToFirstEntry())
        assertEquals(EnterStep.NEW, vm.state.value.step)
        assertEquals("", vm.state.value.entered)
    }

    @Test fun `digits are ignored while the current code is being checked`() {
        val lock = FakeAppLock().apply { gate = CompletableDeferred() }
        val vm = PasscodeEntryViewModel(lock)
        vm.configure(changeExisting = true)
        vm.type("1234")
        assertTrue(vm.state.value.busy)
        vm.digit(5)
        assertEquals("1234", vm.state.value.entered)
        lock.gate!!.complete(Unit)
        assertEquals(EnterStep.NEW, vm.state.value.step)
        assertFalse(vm.state.value.busy)
    }

    @Test fun `unlock with the right code clears the dots`() {
        val vm = UnlockViewModel(FakeAppLock())
        vm.type("1234")
        assertEquals("", vm.state.value.entered)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.busy)
    }

    @Test fun `a wrong code names the attempts left`() {
        val lock = FakeAppLock().apply { wrongResults += VerifyResult.Wrong(4); wrongResults += VerifyResult.Wrong(1) }
        val vm = UnlockViewModel(lock)
        vm.type("0000")
        assertEquals(wrongCodeMessage(4), vm.state.value.error)
        assertEquals("", vm.state.value.entered)
        vm.digit(1)
        assertNull(vm.state.value.error, "the next digit hides the message")
        vm.type("111")
        assertEquals(wrongCodeMessage(1), vm.state.value.error)
    }

    @Test fun `the last wrong code leaves no error behind`() {
        val lock = FakeAppLock().apply { wrongResults += VerifyResult.LockedOut }
        val vm = UnlockViewModel(lock)
        vm.type("0000")
        assertNull(vm.state.value.error)
        assertEquals("", vm.state.value.entered)
        assertFalse(vm.state.value.busy)
    }

    @Test fun `unlock ignores digits while a code is being verified`() {
        val lock = FakeAppLock().apply { gate = CompletableDeferred() }
        val vm = UnlockViewModel(lock)
        vm.type("1234")
        assertTrue(vm.state.value.busy)
        vm.digit(5)
        assertEquals("1234", vm.state.value.entered)
        lock.gate!!.complete(Unit)
        assertFalse(vm.state.value.busy)
        assertEquals("", vm.state.value.entered)
    }

    @Test fun `a lock state change clears what was typed`() {
        val lock = FakeAppLock()
        val vm = UnlockViewModel(lock)
        vm.type("12")
        vm.delete()
        assertEquals("1", vm.state.value.entered)
        lock.state.value = LockState.Locked
        assertEquals("", vm.state.value.entered)
    }

    @Test fun `unlock follows the biometric setting and passes a biometric success on`() {
        val lock = FakeAppLock(availability = BiometricAvailability.FINGERPRINT)
        val vm = UnlockViewModel(lock)
        assertEquals(BiometricAvailability.FINGERPRINT, vm.state.value.biometric)
        assertFalse(vm.state.value.biometricEnabled)
        lock.biometricEnabled.value = true
        assertTrue(vm.state.value.biometricEnabled)
        vm.onBiometricSuccess()
        assertEquals(1, lock.biometricUnlocks)
    }

    @Test fun `forgot code on the unlock screen signs out`() {
        val lock = FakeAppLock()
        val vm = UnlockViewModel(lock)
        vm.askForgot(true)
        assertTrue(vm.state.value.askForgot)
        vm.forgot()
        assertTrue(lock.signedOut)
        assertFalse(vm.state.value.askForgot)
        assertTrue(vm.state.value.busy, "the overlay stays busy until the app leaves for the phone screen")
    }

    @Test fun `a biometric success confirms a dangerous action`() {
        val lock = FakeAppLock(availability = BiometricAvailability.FACE).apply { biometricEnabled.value = true }
        val vm = ConfirmPasscodeViewModel(lock)
        assertTrue(vm.state.value.biometricEnabled)
        vm.onBiometricSuccess()
        assertTrue(vm.state.value.confirmed)
    }

    @Test fun `the security screen shows and switches the biometric setting`() {
        val lock = FakeAppLock(availability = BiometricAvailability.FINGERPRINT)
        val vm = SecurityViewModel(lock)
        assertEquals(BiometricAvailability.FINGERPRINT, vm.state.value.biometric)
        vm.setBiometric(true)
        assertEquals(listOf(true), lock.biometricSwitches)
        assertTrue(vm.state.value.biometricEnabled)
        vm.setBiometric(false)
        assertFalse(vm.state.value.biometricEnabled)
    }
}
