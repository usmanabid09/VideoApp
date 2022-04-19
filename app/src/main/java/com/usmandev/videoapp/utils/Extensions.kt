package com.usmandev.videoapp.utils

import android.app.Activity
import android.util.Log
import android.widget.Toast
import java.lang.Exception

fun Activity.showShortToast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

fun logError(message: String, TAG: String, exception: Exception? = null) {
    Log.e(TAG, message, exception)
}

fun logInfo(message: String, TAG: String) {
    Log.i(TAG, message)
}

fun logDebug(message: String, TAG: String) {
    Log.d(TAG, message)
}

