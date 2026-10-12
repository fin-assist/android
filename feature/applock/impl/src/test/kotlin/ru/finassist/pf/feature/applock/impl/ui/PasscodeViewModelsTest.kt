package ru.finassist.pf.feature.applock.impl.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    private class FakeAppLock(private val code: String = "1234") : AppLock {
        override val state: StateFlow<LockState> = MutableStateFlow(LockState.Unlocked)
        override val biometricEnabled: StateFlow<Boolean> = MutableStateFlow(false)
        var signedOut = false
        override fun biometricAvailability() = BiometricAvailability.NONE
        override suspend fun setPasscode(code: String) = Unit
        override suspend fun verify(code: String): VerifyResult = if (code == this.code) VerifyResult.Ok else VerifyResult.Wrong(3)
        override suspend fun matches(code: String) = code == this.code
        override suspend fun setBiometricEnabled(enabled: Boolean) = Unit
        override fun unlockByBiometric() = Unit
        override fun lock() = Unit
        override suspend fun forgetAndSignOut() { signedOut = true }
        override suspend fun reset() = Unit
    }

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun ConfirmPasscodeViewModel.type(code: String) = code.forEach { digit(it.digitToInt()) }

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
}
