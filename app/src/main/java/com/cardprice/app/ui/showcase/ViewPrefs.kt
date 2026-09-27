package com.cardprice.app.ui.showcase

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Whether a screen shows the card gallery or the plain list, remembered per screen ([key]) across
 * app restarts. Collections open as a gallery; inventory as a list.
 */
@Composable
fun rememberGalleryMode(key: String, default: Boolean): MutableState<Boolean> {
    val prefs = LocalContext.current.getSharedPreferences("view_prefs", Context.MODE_PRIVATE)
    val state = remember(key) { mutableStateOf(prefs.getBoolean("gallery_$key", default)) }
    return remember(key, state) {
        object : MutableState<Boolean> by state {
            override var value: Boolean
                get() = state.value
                set(v) {
                    state.value = v
                    prefs.edit().putBoolean("gallery_$key", v).apply()
                }
        }
    }
}
