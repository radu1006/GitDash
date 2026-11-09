package com.agentroost.gitdashboard.testing

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** Fails with the runtime type of [actual] when it is not a [T]. */
inline fun <reified T> assertInstanceOf(actual: Any?, message: String? = null): T {
    if (actual is T) return actual
    val actualType = actual?.let { it::class.java.simpleName } ?: "null"
    throw AssertionError(message ?: "Expected ${T::class.java.simpleName} but was $actualType: $actual")
}

/**
 * Waits for the first state matching [predicate].
 *
 * The ViewModels talk to `Dispatchers.IO`/`Dispatchers.Default` for real, so state changes are not
 * driven by the test scheduler alone; waiting on the flow avoids arbitrary `advanceUntilIdle` +
 * sleep combinations.
 */
suspend fun <T> StateFlow<T>.awaitState(predicate: (T) -> Boolean): T = first(predicate)
