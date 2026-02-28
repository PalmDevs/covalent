package me.palmdevs.covalent.api

import android.app.AndroidAppHelper
import android.content.Intent
import kotlin.system.exitProcess

fun reloadApp() {
    val application = AndroidAppHelper.currentApplication()
    val intent = application.packageManager.getLaunchIntentForPackage(application.packageName)
    application.startActivity(Intent.makeRestartActivityTask(intent!!.component))
    exitProcess(0)
}

class Log(val namespace: String? = null) {
    companion object {
        private const val TAG = "Covalent"

        fun namespace(namespace: String): Log = Log(namespace)
    }

    private fun format(message: String): String =
        if (namespace != null) "$namespace - $message" else message

    fun i(message: String, throwable: Throwable? = null) {
        android.util.Log.i(TAG, format(message), throwable)
    }

    fun w(message: String, throwable: Throwable? = null) {
        android.util.Log.w(TAG, format(message), throwable)
    }

    fun e(message: String, throwable: Throwable? = null) {
        android.util.Log.e(TAG, format(message), throwable)
    }

    fun d(message: String, throwable: Throwable? = null) {
        android.util.Log.d(TAG, format(message), throwable)
    }

    fun v(message: String, throwable: Throwable? = null) {
        android.util.Log.v(TAG, format(message), throwable)
    }
}