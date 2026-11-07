package me.kavishdevar.librepods.billing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Asserts the fork's behaviour: whatever billing backend the build would normally
 * pick (Play Billing or the FOSS honor-system provider), the app always reports
 * premium, so no advanced feature stays behind a purchase.
 */
@RunWith(AndroidJUnit4::class)
class PremiumGateTest {

    @Test
    fun factoryAlwaysReportsPremium() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = BillingProviderFactory.create(context)
        assertTrue(
            "BillingProviderFactory must report premium without any purchase",
            provider.isPremium.value
        )
    }
}
