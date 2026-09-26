/*
 * Adapted from Android's Navigation 3 retain recipe (Apache-2.0).
 * Source revision and full license: repository-root CREDITS.
 * Changes: keep only a private registry and the entry decorator factory.
 */
package dev.soupslurpr.beautyxt.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.retain.retainRetainedValuesStoreRegistry
import androidx.navigation3.runtime.NavEntryDecorator

/** Retains each entry's in-memory session until its exit transition finishes. */
@Composable
internal fun <T : Any> rememberSessionEntryDecorator(): NavEntryDecorator<T> {
    val registry = retainRetainedValuesStoreRegistry()
    return remember(registry) {
        NavEntryDecorator(
            onPop = registry::clearChild,
            decorate = { entry ->
                registry.LocalRetainedValuesStoreProvider(entry.contentKey) {
                    entry.Content()
                }
            }
        )
    }
}
