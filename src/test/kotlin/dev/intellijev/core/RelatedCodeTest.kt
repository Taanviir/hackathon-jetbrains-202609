package dev.intellijev.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelatedCodeTest {
    @Test
    fun `candidate search follows the selected fix rather than a preset topic`() {
        val expiryFix = RelatedCode.signature("if (checkoutExpiry <= now) rejectExpiredCoupon()")
        val retryFix = RelatedCode.signature("if (retryBudget == 0) stopRetrying()")
        val expiryPath = "validateCheckoutExpiry(coupon)"
        val retryPath = "decrementRetryBudget(request)"

        assertTrue(RelatedCode.sharedTerms(expiryPath, expiryFix).isNotEmpty())
        assertTrue(RelatedCode.sharedTerms(retryPath, retryFix).isNotEmpty())
        assertTrue(RelatedCode.sharedTerms(retryPath, expiryFix).isEmpty())
        assertTrue(RelatedCode.sharedTerms(expiryPath, retryFix).isEmpty())
    }

    @Test
    fun `language keywords alone do not create related code evidence`() {
        val signature = RelatedCode.signature("if (value == null) return false")
        assertTrue(RelatedCode.sharedTerms("return null", signature).isEmpty())
        assertFalse(RelatedCode.sharedTerms("checkValue()", signature).isEmpty())
    }
}
