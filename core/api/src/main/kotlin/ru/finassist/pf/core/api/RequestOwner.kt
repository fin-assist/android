package ru.finassist.pf.core.api

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Local request binding only; never a wire header, log value or replacement for bearer authentication. */
class RequestOwner(val userId: String, val authSessionEpoch: String) : AbstractCoroutineContextElement(Key) {
    init { require(userId.isNotBlank() && authSessionEpoch.isNotBlank()) }
    fun matches(session: TokenStore.Session?): Boolean =
        session?.userId == userId && session.authSessionEpoch == authSessionEpoch
    override fun toString(): String = "RequestOwner"
    companion object Key : CoroutineContext.Key<RequestOwner>
}
