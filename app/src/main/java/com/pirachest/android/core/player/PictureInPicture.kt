package com.pirachest.android.core.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer

fun enterPictureInPicture(activity: Activity?, width: Int = 16, height: Int = 9): Boolean {
    if (activity == null) return false
    return runCatching {
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(width, height))
            .build()
        activity.enterPictureInPictureMode(params)
    }.getOrDefault(false)
}

@Composable
fun rememberIsInPictureInPicture(): Boolean {
    val activity = LocalContext.current.findActivity() as? ComponentActivity
    var inPip by remember { mutableStateOf(activity?.isInPictureInPictureMode ?: false) }
    DisposableEffect(activity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { info -> inPip = info.isInPictureInPictureMode }
        activity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity?.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return inPip
}
