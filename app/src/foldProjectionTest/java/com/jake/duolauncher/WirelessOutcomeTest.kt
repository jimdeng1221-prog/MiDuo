package com.jake.duolauncher

import android.app.AlertDialog
import android.widget.TextView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WirelessOutcomeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun verifiedSuccessIsAnExplicitDialogAndEnableNeedsOneClick() {
        var enabled = 0
        compose.runOnUiThread {
            val activity = compose.activity
            val dialog = createWirelessAdbOutcome(activity, true, false, R.string.wireless_authorized, { enabled++ }, {})
            try {
                dialog.show()
                assertTrue(dialog.isShowing)
                assertEquals(activity.getString(R.string.wireless_success_message), dialog.findViewById<TextView>(android.R.id.message).text.toString())
                assertEquals(activity.getString(R.string.navigation_enable), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            } finally { dialog.dismiss() }
        }
        compose.waitForIdle()
        assertEquals(1, enabled)
    }

    @Test fun pairingOrFailedPermissionCannotOfferEnableOrClaimSuccess() {
        compose.runOnUiThread {
            val activity = compose.activity
            for (paired in listOf(true, false)) {
                val message = if (paired) R.string.wireless_paired_need_connect else R.string.wireless_authorized
                val dialog = createWirelessAdbOutcome(activity, false, paired, message, { fail("Must not enable") }, {})
                try {
                    dialog.show()
                    assertEquals(activity.getString(R.string.wireless_return_setup), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
                    assertEquals(activity.getString(if (paired) R.string.wireless_paired_need_connect else R.string.wireless_grant_denied),
                        dialog.findViewById<TextView>(android.R.id.message).text.toString())
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                } finally { dialog.dismiss() }
            }
        }
    }
}
