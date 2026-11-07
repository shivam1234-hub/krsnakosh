package app.tagledger.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ParsersTest {
    private val now = LocalDateTime.of(2026, 10, 6, 15, 0)

    @Test fun quickAdd() {
        parseQuick("250 lunch #food #Office", now).run {
            assertEquals(250.0, amount); assertEquals("lunch", title); assertEquals(listOf("food", "office"), tags); assertEquals("expense", type)
        }
        parseQuick("+50000 salary", now).run { assertEquals("income", type); assertEquals(50000.0, amount) }
        parseQuick("1.2k rent @yesterday", now).run { assertEquals(1200.0, amount); assertEquals(LocalDate.of(2026, 10, 5), time.toLocalDate()) }
        parseQuick("coffee", now).run { assertNull(amount) }
        assertEquals(LocalDate.of(2025, 10, 12), parseDateToken("12/10", LocalDate.of(2026, 10, 6)))
    }

    private fun sms(t: String) = parseBankSms(t, 0L)

    @Test fun bankSms() {
        sms("Sent Rs.250.00 From HDFC Bank A/C *1234 To SWIGGY On 06/10/26 Ref 627912345678 Not You? Call 18002586161")!!.run {
            assertEquals(250.0, amount); assertEquals("Swiggy", title); assertEquals("627912345678", ref); assertEquals("expense", type)
        }
        sms("ICICI Bank Acct XX123 debited for Rs 1,860.00 on 06-Oct-26; RAMESH KUMAR credited. UPI:627998765432. Call 18002662")!!.run {
            assertEquals(1860.0, amount); assertEquals("Ramesh Kumar", title); assertEquals("expense", type); assertEquals("627998765432", ref)
        }
        sms("INR 320.00 debited A/c no. XX1234 06-10-26 12:30:11 UPI/P2M/627911112222/UBER INDIA/ Not you? Axis Bank")!!.run {
            assertEquals("Uber India", title); assertEquals("627911112222", ref)
        }
        sms("Dear UPI user A/C X4321 debited by 99.0 on date 06Oct26 trf to BLINKIT Refno 627933334444. If not u? call 1800111109. -SBI")!!.run {
            assertEquals(99.0, amount); assertEquals("Blinkit", title)
        }
        sms("Rs.85000.00 credited to HDFC Bank A/c XX1234 on 01-10-26 by NEFT from ACME PVT LTD Ref 0012345.")!!.run {
            assertEquals("income", type); assertEquals("Acme Pvt Ltd", title)
        }
        sms("Received Rs.2000.00 in your Kotak Bank a/c XX9999 from rahul.v@okicici on 06-10-26. UPI Ref:627977778888")!!.run {
            assertEquals("income", type); assertEquals("Rahul V", title)
        }
        assertNull(sms("Rs 500 will be debited from your a/c on 10-Oct for Netflix autopay"))
        assertNull(sms("Your OTP for txn of Rs 450 is 123456"))
        assertNull(sms("Hi, your order has shipped"))
    }

    /** Text as ML Kit reads a Google Pay "paid" receipt screenshot. */
    @Test fun googlePayReceipt() {
        val text = """
            ₹250
            Paid to
            Chai Point
            chaipoint@ybl
            Completed
            6 Oct 2026, 12:41 pm
            UPI transaction ID
            627955556666
            To: CHAI POINT
            From: SHIVAM (HDFC Bank)
            Google transaction ID
            CICAgOD1234
        """.trimIndent()
        val d = parseReceipt(text, "₹250")
        assertNotNull(d); d!!
        assertEquals(250.0, d.amount); assertEquals("Chai Point", d.title); assertEquals("627955556666", d.ref); assertEquals("expense", d.type)
        assertEquals(LocalDateTime.of(2026, 10, 6, 12, 41), java.time.Instant.ofEpochMilli(d.time).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime())
    }

    @Test fun receiptAsReadOnEmulator() {
        // Real ML Kit output for our sample receipt: the rupee sign became 7 and "Chai" became "Chal".
        val text = "Paid to\nChal Point\nchaipoint@ybl\n7250\nCompleted\n6 Oct 2026, 12:41 pm\nUPI transaction ID\n627955556666\nTo: CHAI POINT\nchaipoint@ybl\nFrom: SHIVAM (HDFC Bank)"
        val d = parseReceipt(text, "7250")!!
        assertEquals(250.0, d.amount); assertEquals("Chai Point", d.title)
        // Capital I read as lowercase l in the "To:" line too; the UPI ID settles it.
        assertEquals("Chai Point", parseReceipt(text.replace("To: CHAI POINT", "To: CHAl POINT"), "7250")!!.title)
        assertEquals("Swiggy", fixWithVpa("Swiggy", "swiggy.in"))  // different text: left alone
    }

    @Test fun receiptAmountMisreadAsZ() {
        // OCR sometimes reads the rupee sign as Z; the largest line is still the amount.
        assertEquals(1499.0, parseReceipt("Z1,499\nPaid to\nAmazon\n3 Oct 2026, 9:05 am", "Z1,499")!!.amount)
    }

    @Test fun googlePayShareText() {
        val d = parseBankSms("You paid ₹1,200.00 to Priya Sharma. UPI transaction ID: 627955556666", 0L)!!
        assertEquals(1200.0, d.amount); assertEquals("Priya Sharma", d.title); assertEquals("627955556666", d.ref)
    }
}
