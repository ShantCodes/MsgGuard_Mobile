package com.example.msgguard.sms

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.app.role.RoleManager

/** Asks Android to offer MsgGuard as the default SMS app. The system requires that role for sending storage and SMS delivery. */
object DefaultSmsRole {
    fun isDefault(context: Context): Boolean =
        Telephony.Sms.getDefaultSmsPackage(context) == context.packageName

    @Suppress("DEPRECATION")
    fun request(activity: Activity, requestCode: Int): Boolean {
        if (isDefault(activity)) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = activity.getSystemService(RoleManager::class.java) ?: return false
            if (!roleManager.isRoleAvailable(RoleManager.ROLE_SMS) || roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
                return false
            }
            activity.startActivityForResult(
                roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS),
                requestCode
            )
            return true
        }
        activity.startActivityForResult(
            Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, activity.packageName),
            requestCode
        )
        return true
    }
}
