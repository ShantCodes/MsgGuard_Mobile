package com.example.msgguard.ui.activities

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * Requests the dangerous SMS permissions required before the receiver can inspect messages.
 */
class MainActivityActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val permissions = arrayOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )
        if (permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(permissions, REQUEST_SMS_PERMISSIONS)
        }
    }

    private companion object {
        const val REQUEST_SMS_PERMISSIONS = 100
    }
}
