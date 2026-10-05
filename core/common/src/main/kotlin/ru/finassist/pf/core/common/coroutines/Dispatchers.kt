package ru.finassist.pf.core.common.coroutines

import kotlinx.coroutines.CoroutineDispatcher

/** Injected instead of `Dispatchers.*` so tests can swap them. */
interface AppDispatchers {
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val main: CoroutineDispatcher
}
