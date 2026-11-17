package ua.nichnyk.listen.billing

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import ua.nichnyk.listen.data.SecretStore

/**
 * FOSS-варіант менеджера Pro для збірок F-Droid / IzzyOnDroid (`-Pfoss=true`).
 *
 * Не містить жодних залежностей від пропрієтарної бібліотеки Google Play Billing
 * і не додає дозволу `com.android.vending.BILLING` у маніфест. Усі Pro-функції
 * відкриті одразу.
 */
class ProEntitlementManager(
    @Suppress("UNUSED_PARAMETER") context: Context,
    @Suppress("UNUSED_PARAMETER") secrets: SecretStore,
    @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
) {
    private val _isPro = MutableStateFlow(true)
    val isPro: StateFlow<Boolean> = _isPro.asStateFlow()

    val resolvedIsPro: Flow<Boolean> = flowOf(true)

    private val _formattedPrice = MutableStateFlow<String?>(null)
    val formattedPrice: StateFlow<String?> = _formattedPrice.asStateFlow()

    val events: Flow<BillingEvent> = emptyFlow()

    fun startConnection() = Unit

    fun launchBillingFlow(@Suppress("UNUSED_PARAMETER") activity: Activity): Boolean = false

    fun restorePurchases(callback: (Boolean) -> Unit = {}) {
        callback(true)
    }

    companion object {
        const val SKU_PRO_LIFETIME = "bookvoices_pro_lifetime"
    }
}
