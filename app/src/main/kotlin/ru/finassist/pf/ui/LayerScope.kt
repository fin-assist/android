package ru.finassist.pf.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

/** Activity-scoped holder of one ViewModelStore per UI layer; survives configuration changes. */
class LayerStores : ViewModel() {
    private val stores = HashMap<String, ViewModelStore>()

    fun get(key: String): ViewModelStore = stores.getOrPut(key) { ViewModelStore() }

    fun clear(key: String) {
        stores.remove(key)?.clear()
    }

    override fun onCleared() {
        stores.values.forEach { it.clear() }
        stores.clear()
    }
}

/**
 * Gives [content] its own ViewModelStore (and thereby its own NavHost back-stack view models). The store is
 * cleared when the layer leaves composition for good — sign-out, lock state change — but kept across
 * configuration changes. Without this, view models of screens shown outside a NavHost (passcode setup,
 * unlock) and of a removed NavHost would live as long as the activity and carry state into the next session.
 */
@Composable
fun LayerScope(key: String, content: @Composable () -> Unit) {
    val parent = checkNotNull(LocalViewModelStoreOwner.current) { "LayerScope needs a ViewModelStoreOwner" }
    val stores: LayerStores = viewModel(viewModelStoreOwner = parent)
    val activity = LocalContext.current.findActivity()
    val owner = remember(key) { LayerOwner(stores.get(key), parent) }
    DisposableEffect(key) {
        onDispose { if (activity?.isChangingConfigurations != true) stores.clear(key) }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}

/** Delegates view model creation to the parent (Hilt's factory), but stores them in the layer's store. */
private class LayerOwner(
    override val viewModelStore: ViewModelStore,
    private val parent: ViewModelStoreOwner,
) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
    private val parentDefaults = parent as? HasDefaultViewModelProviderFactory

    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = checkNotNull(parentDefaults) { "Parent owner has no default factory" }.defaultViewModelProviderFactory

    override val defaultViewModelCreationExtras: CreationExtras
        get() = MutableCreationExtras(parentDefaults?.defaultViewModelCreationExtras ?: CreationExtras.Empty).apply {
            // SavedStateHandles are kept per store owner; point them at this layer.
            set(VIEW_MODEL_STORE_OWNER_KEY, this@LayerOwner)
        }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
