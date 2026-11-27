package com.example.msgguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Declares MMS delivery support required for Android's SMS role.
 * MMS decoding and carrier MMSC transport are intentionally kept separate from the SMS pipeline.
 */
class MmsDeliveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // SMS delivery is handled by SmsReceiver. MMS needs carrier-specific PDU processing.
    }
}
