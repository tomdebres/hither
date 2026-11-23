package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.model.User
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

/** The TV's admin account (DECISIONS 91, 92): who may be it, when its session is renewed, the PIN's setting. */
class AdminTest {
    private val family = User(id = 8, displayName = "User 8", permissions = Permission.REQUEST or Permission.AUTO_APPROVE)
    private fun user(id: Int, permissions: Int?) = User(id = id, displayName = "User $id", permissions = permissions)

    @Test
    fun anAccountWithManageRequestsOnlyIsAnApprover() {
        assertEquals(AdminVerdict.APPROVER, adminVerdict(user(9, Permission.MANAGE_REQUESTS), family))
        assertEquals(AdminVerdict.APPROVER, adminVerdict(user(9, Permission.MANAGE_REQUESTS or Permission.REQUEST_VIEW), family))
    }

    @Test
    fun anAdministratorWorksAndIsSaidOnce() {
        assertEquals(AdminVerdict.ADMINISTRATOR, adminVerdict(user(1, Permission.ADMIN), family))
        assertEquals(AdminVerdict.ADMINISTRATOR, adminVerdict(user(1, Permission.ADMIN or Permission.MANAGE_REQUESTS), family))
    }

    @Test
    fun anAccountThatCannotApproveIsRefused() {
        assertEquals(AdminVerdict.NO_RIGHT, adminVerdict(user(9, Permission.REQUEST or Permission.REQUEST_VIEW), family))
        assertEquals(AdminVerdict.NO_RIGHT, adminVerdict(user(9, 0), family))
        assertEquals(AdminVerdict.NO_RIGHT, adminVerdict(user(9, null), family), "no permissions read: none held")
    }

    @Test
    fun theFamilysOwnAccountIsRefusedEvenWhenItCouldApprove() {
        assertEquals(AdminVerdict.FAMILY, adminVerdict(user(8, Permission.MANAGE_REQUESTS), family))
        assertEquals(AdminVerdict.FAMILY, adminVerdict(user(8, Permission.ADMIN), family))
    }

    @Test
    fun theRightToDecideIsReadEveryTime() {
        assertTrue(mayDecide(user(9, Permission.MANAGE_REQUESTS)))
        assertTrue(mayDecide(user(1, Permission.ADMIN)))
        assertFalse(mayDecide(user(9, Permission.REQUEST)))
        assertFalse(mayDecide(null))
    }

    @Test
    fun theSessionIsRenewedBeforeSeerrsThirtyDays() {
        val day = 24L * 60 * 60 * 1000
        val signedIn = 1_000_000_000_000L
        assertFalse(adminSessionDue(signedIn, signedIn + 24 * day))
        assertTrue(adminSessionDue(signedIn, signedIn + 25 * day))
        assertTrue(adminSessionDue(signedIn, signedIn + 31 * day), "past Seerr's 30 days")
        assertTrue(adminSessionDue(null, signedIn), "never signed in")
        assertTrue(adminSessionDue(signedIn, signedIn - 1), "a clock gone back past the sign-in")
        assertTrue(ADMIN_RENEW_AFTER_MS < SEERR_SESSION_MS)
    }

    @Test
    fun aPinIsFourDigits() {
        assertTrue(isPin("0000"))
        assertTrue(isPin("4821"))
        assertFalse(isPin("482"))
        assertFalse(isPin("48210"))
        assertFalse(isPin("48a1"))
        assertFalse(isPin("４８２１"))
    }

    @Test
    fun anEntryTakesFourDigitsAndDeletesTheLast() {
        var entry = PinEntry()
        for (d in listOf(4, 8, 2, 1, 7)) entry = entry.type(d)
        assertEquals("4821", entry.digits)
        assertTrue(entry.full)
        assertEquals("482", entry.delete().digits)
        assertEquals("", PinEntry().delete().digits)
        assertEquals("", PinEntry().type(10).digits)
        assertFalse(entry.toString().contains("4821"), "never the digits")
    }

    @Test
    fun thePinIsSetWhenItIsEnteredTwiceTheSame() {
        val confirm = pinSetting(PinSetting.Choose(), "4821")
        assertTrue(confirm is PinSetting.Confirm)
        val set = pinSetting(confirm, "4821")
        assertTrue(set is PinSetting.Set)
        assertEquals("4821", (set as PinSetting.Set).pin)
        assertFalse(set.toString().contains("4821") || confirm.toString().contains("4821"), "never the digits")
    }

    @Test
    fun twoEntriesThatDifferStartAgain() {
        val again = pinSetting(pinSetting(PinSetting.Choose(), "4821"), "4812")
        assertEquals(PinSetting.Choose(mismatched = true), again)
        assertTrue(pinSetting(again, "1234") is PinSetting.Confirm)
    }
}
