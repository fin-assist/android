package ru.finassist.pf.feature.assistant.impl.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AssistantAvailabilityPolicyTest {
    @Test fun `a retained layer keeps admitted availability when its owner is recreated under a disabled flag`() {
        val store = ViewModelStore()
        var flag = true
        var creations = 0
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                creations++
                return AssistantAvailabilityPolicy(flag) as T
            }
        }
        fun owner() = object : ViewModelStoreOwner { override val viewModelStore = store }
        val first = ViewModelProvider(owner(), factory)[AssistantAvailabilityPolicy::class.java]
        flag = false
        val recreated = ViewModelProvider(owner(), factory)[AssistantAvailabilityPolicy::class.java]
        assertSame(first, recreated)
        assertTrue(recreated.enter(flag))
        assertEquals(1, creations)
    }

    @Test fun `an explicitly entered late enable stays available through later consent and recreation`() {
        val policy = AssistantAvailabilityPolicy(false)
        assertFalse(policy.enabled.value)
        assertFalse(policy.enter(false))
        assertTrue(policy.enter(true))
        assertTrue(policy.enabled.value)
        assertTrue(policy.enter(false))
        assertFalse(AssistantAvailabilityPolicy(false).enter(false), "a new process or authenticated layer starts denied")
    }
}
