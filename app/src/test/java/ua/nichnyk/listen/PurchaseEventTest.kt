package ua.nichnyk.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ua.nichnyk.listen.billing.BillingEvent
import ua.nichnyk.listen.billing.purchaseEvent

/** Що показуємо після відповіді Play: «Дякуємо» лише за підтверджену оплату. */
class PurchaseEventTest {

    @Test
    fun paidPurchaseIsSuccess() {
        assertEquals(BillingEvent.PurchaseSuccess, purchaseEvent(paid = true, pending = false))
    }

    @Test
    fun pendingPurchaseIsNotSuccess() {
        assertEquals(BillingEvent.PurchasePending, purchaseEvent(paid = false, pending = true))
    }

    @Test
    fun paidWinsOverPending() {
        assertEquals(BillingEvent.PurchaseSuccess, purchaseEvent(paid = true, pending = true))
    }

    @Test
    fun otherProductsOrStatesSayNothing() {
        assertNull(purchaseEvent(paid = false, pending = false))
    }
}
