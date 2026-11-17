package ua.nichnyk.listen.billing

/**
 * Що сказати користувачу після `onPurchasesUpdated` з непорожнім списком.
 *
 * Відкладена оплата (готівкою, з підтвердженням) — це ще не покупка: Pro вмикається
 * лише зі станом PURCHASED, тож «Дякуємо» тут було б неправдою.
 */
internal fun purchaseEvent(paid: Boolean, pending: Boolean): BillingEvent? = when {
    paid -> BillingEvent.PurchaseSuccess
    pending -> BillingEvent.PurchasePending
    else -> null
}

sealed interface BillingEvent {
    data object PurchaseSuccess : BillingEvent

    /** Оплата ще не підтверджена: Pro увімкнеться, коли Play пришле PURCHASED. */
    data object PurchasePending : BillingEvent
    data object UserCanceled : BillingEvent

    /** Play не віддав деталей товару — купувати нема що. */
    data object Unavailable : BillingEvent
    data class Error(val message: String) : BillingEvent
}
