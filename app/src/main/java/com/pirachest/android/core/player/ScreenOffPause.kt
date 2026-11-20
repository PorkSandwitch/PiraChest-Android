package com.pirachest.android.core.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.media3.common.Player

fun registerScreenOffPause(context: Context, player: Player): () -> Unit {
    val appContext = context.applicationContext
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                player.playWhenReady = false
                player.pause()
            }
        }
    }
    val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
    if (Build.VERSION.SDK_INT >= 33) {
        appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        @Suppress("DEPRECATION")
        appContext.registerReceiver(receiver, filter)
    }
    return { runCatching { appContext.unregisterReceiver(receiver) } }
}
