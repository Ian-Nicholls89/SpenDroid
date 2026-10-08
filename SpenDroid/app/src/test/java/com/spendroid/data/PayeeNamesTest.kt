package com.spendroid.data

import androidx.test.core.app.ApplicationProvider
import com.spendroid.ui.tidyBankName
import com.spendroid.ui.tidyPayee
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PayeeNamesTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before fun load() = PayeeNames.load(context)
    @After fun clear() { PayeeNames.set("VIS 4471 SQ *KTH LDN", null) }

    @Test
    fun `a name shows for the payee however the bank spaces or cases it`() {
        PayeeNames.set("VIS 4471 SQ *KTH LDN", "Coffee by the station")
        assertEquals("Coffee by the station", "VIS 4471  SQ *KTH LDN ".tidyPayee())
        assertEquals("Coffee by the station", "vis 4471 sq *kth ldn".tidyPayee())
        assertEquals("OTHER SHOP", "OTHER   SHOP".tidyPayee())
        // The bank's own name is still there, for search and for showing beneath.
        assertEquals("VIS 4471 SQ *KTH LDN", "VIS 4471  SQ *KTH LDN".tidyBankName())
    }

    @Test
    fun `names last, and a blank or the bank's own name takes one away`() {
        PayeeNames.set("VIS 4471 SQ *KTH LDN", "Coffee")
        PayeeNames.load(context)
        assertEquals("Coffee", "VIS 4471 SQ *KTH LDN".tidyPayee())
        PayeeNames.set("VIS 4471 SQ *KTH LDN", "  ")
        assertEquals("VIS 4471 SQ *KTH LDN", "VIS 4471 SQ *KTH LDN".tidyPayee())
        PayeeNames.set("VIS 4471 SQ *KTH LDN", "vis 4471 sq *kth ldn")
        assertEquals(0, PayeeNames.count)
    }
}
