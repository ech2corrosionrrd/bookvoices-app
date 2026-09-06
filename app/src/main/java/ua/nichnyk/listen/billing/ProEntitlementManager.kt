package ua.nichnyk.listen.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import ua.nichnyk.listen.AppLog
import ua.nichnyk.listen.data.SecretStore

class ProEntitlementManager(
    private val context: Context,
    private val secrets: SecretStore,
    private val scope: CoroutineScope,
) : PurchasesUpdatedListener {

    /**
     * null — ще не визначено (кеш ліцензії читається з диска, Play не відповів).
     *
     * Саме nullable, а не `false` на старті: [resolvedIsPro] годує межу Freemium
     * у UserPrefs, і початкове «немає Pro» на мить погасило б платні
     * налаштування власнику ліцензії — рівно в момент відкриття налаштувань.
     */
    private val _isPro = MutableStateFlow<Boolean?>(null)

    /** Для інтерфейсу: «поки не знаємо» показуємо як «немає». */
    val isPro: StateFlow<Boolean> = _isPro
        .map { it == true }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** Для запобіжника: лише визначений стан, без проміжного null. */
    val resolvedIsPro: Flow<Boolean> = _isPro.filterNotNull()

    private val _productDetails = MutableStateFlow<ProductDetails?>(null)
    val productDetails: StateFlow<ProductDetails?> = _productDetails.asStateFlow()

    private val _formattedPrice = MutableStateFlow<String?>(null)
    val formattedPrice: StateFlow<String?> = _formattedPrice.asStateFlow()

    private val _events = Channel<BillingEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var reconnectAttempt = 0

    private val pendingPurchasesParams = PendingPurchasesParams.newBuilder()
        .enableOneTimeProducts()
        .build()

    private val billingClient: BillingClient by lazy {
        BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases(pendingPurchasesParams)
            .build()
    }

    init {
        scope.launch {
            val cached = secrets.isProCached()
            _isPro.value = cached
            startConnection()
        }
    }

    fun startConnection() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    reconnectAttempt = 0
                    queryProductDetails()
                    queryActivePurchases()
                } else {
                    AppLog.w("BillingClient: setup failed with responseCode=${result.responseCode}, msg=${result.debugMessage}")
                }
            }

            /**
             * Play може обірвати звʼязок у будь-який момент (оновлення самого
             * сервісу, брак памʼяті). Раніше тут був лише лог: після розриву
             * `productDetails` лишалися старими, а `launchBillingFlow` мовчки
             * повертав помилку — кнопка «купити» просто перестала б працювати
             * до перезапуску застосунку.
             */
            override fun onBillingServiceDisconnected() {
                AppLog.w("BillingClient: disconnected")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            AppLog.w("BillingClient: reconnect gave up after $reconnectAttempt attempts")
            return
        }
        val attempt = ++reconnectAttempt
        scope.launch {
            delay(RECONNECT_BASE_DELAY_MS * attempt)
            if (!billingClient.isReady) startConnection()
        }
    }

    private fun queryProductDetails() {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(SKU_PRO_LIFETIME)
                .setProductType(BillingClient.ProductType.INAPP)
                .build(),
        )
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { result, detailsResult ->
            val detailsList = detailsResult.productDetailsList
            if (result.responseCode == BillingClient.BillingResponseCode.OK && detailsList.isNotEmpty()) {
                val details = detailsList.firstOrNull { it.productId == SKU_PRO_LIFETIME }
                _productDetails.value = details
                _formattedPrice.value = details?.baseOfferPrice()
            }
        }
    }

    /**
     * Ціна базової пропозиції.
     *
     * У Billing 8 одноразовий товар несе список пропозицій, а не одну: крім
     * базової там можуть з'явитися промо-знижки. `first()` брав би те, що
     * трапилося першим, а `launchBillingFlow` без `setOfferToken` веде саме за
     * базовою — тобто ціна на paywall розійшлася б з ціною в кошику рівно тоді,
     * коли в Console з'явиться перша знижка. Базову впізнаємо за порожнім
     * `offerId`. Поки знижок немає, список має один елемент і це та сама ціна.
     *
     * Якщо колись знижки таки робити — міняти разом із [launchBillingFlow]:
     * туди має піти `offerToken` тієї ж пропозиції, а не лише її ціна сюди.
     */
    private fun ProductDetails.baseOfferPrice(): String? {
        val offers = oneTimePurchaseOfferDetailsList ?: return null
        return (offers.firstOrNull { it.offerId == null } ?: offers.firstOrNull())
            ?.formattedPrice
    }

    private fun queryActivePurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        billingClient.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                processPurchases(purchases, authoritative = true)
            }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (!purchases.isNullOrEmpty()) {
                    // Не authoritative: у колбеку приходить лише те, що змінилося,
                    // тож відсутність Pro тут не означає, що його немає.
                    processPurchases(purchases, authoritative = false)
                    _events.trySend(BillingEvent.PurchaseSuccess)
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                _events.trySend(BillingEvent.UserCanceled)
            }
            else -> {
                AppLog.w("BillingClient: purchase updated with error code=${result.responseCode}")
                _events.trySend(BillingEvent.Error(result.debugMessage))
            }
        }
    }

    /**
     * @param authoritative список — повна інвентаризація з `queryPurchasesAsync`,
     * а не дельта з `onPurchasesUpdated`. Лише вона має право **знімати** Pro:
     * раніше `_isPro` ніде не ставився у false і кеш не чистився, тож повернення
     * коштів чи відкликання покупки не діяли — ліцензія лишалася назавжди.
     */
    private fun processPurchases(purchases: List<Purchase>, authoritative: Boolean) {
        val proPurchase = purchases.firstOrNull { purchase ->
            purchase.products.contains(SKU_PRO_LIFETIME) &&
                purchase.purchaseState == Purchase.PurchaseState.PURCHASED
        }

        if (proPurchase == null) {
            if (authoritative && _isPro.value != false) {
                _isPro.value = false
                scope.launch { secrets.clearProCached() }
            }
            return
        }

        _isPro.value = true
        scope.launch { secrets.setProCached(proPurchase.purchaseToken) }
        if (!proPurchase.isAcknowledged) {
            val ackParams = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(proPurchase.purchaseToken)
                .build()
            billingClient.acknowledgePurchase(ackParams) { ackResult ->
                if (ackResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    AppLog.w("BillingClient: failed to acknowledge purchase: ${ackResult.debugMessage}")
                }
            }
        }
    }

    /**
     * @return false — потік не відкрився. Раніше це значення ніхто не читав, і
     * невідомий SKU чи обірваний звʼязок з Play виглядали як мертва кнопка:
     * нічого не відкривалося й нічого не повідомлялося. Тепер причина йде
     * в [events] і доходить до користувача.
     */
    fun launchBillingFlow(activity: Activity): Boolean {
        val details = _productDetails.value
        if (details == null) {
            _events.trySend(BillingEvent.Unavailable)
            if (!billingClient.isReady) startConnection() else queryProductDetails()
            return false
        }
        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .build()
        val result = billingClient.launchBillingFlow(activity, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            AppLog.w("BillingClient: launchBillingFlow failed code=${result.responseCode}")
            _events.trySend(BillingEvent.Unavailable)
            return false
        }
        return true
    }

    fun restorePurchases(onResult: (Boolean) -> Unit = {}) {
        if (!billingClient.isReady) {
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        queryPurchasesAndNotify(onResult)
                    } else {
                        onResult(_isPro.value == true)
                    }
                }

                override fun onBillingServiceDisconnected() {
                    onResult(_isPro.value == true)
                }
            })
        } else {
            queryPurchasesAndNotify(onResult)
        }
    }

    private fun queryPurchasesAndNotify(onResult: (Boolean) -> Unit) {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        billingClient.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                processPurchases(purchases, authoritative = true)
                val foundPro = purchases.any { it.products.contains(SKU_PRO_LIFETIME) && it.purchaseState == Purchase.PurchaseState.PURCHASED }
                onResult(foundPro)
            } else {
                onResult(_isPro.value == true)
            }
        }
    }

    companion object {
        const val SKU_PRO_LIFETIME = "bookvoices_pro_lifetime"
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_BASE_DELAY_MS = 1_000L
    }
}

sealed interface BillingEvent {
    data object PurchaseSuccess : BillingEvent
    data object UserCanceled : BillingEvent

    /** Play не віддав деталей товару — купувати нема що. */
    data object Unavailable : BillingEvent
    data class Error(val message: String) : BillingEvent
}