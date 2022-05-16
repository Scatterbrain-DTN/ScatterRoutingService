package net.ballmerlabs.uscatterbrain.util

import android.util.Log

class LoggerImpl(c: Class<*>): Logger(c) {

    override fun d(text: String) {
        Log.d(name, fmt(text, LogLevel.DEBUG))
    }

    override fun w(text: String) {
        Log.w(name, fmt(text, LogLevel.WARN))
    }

    override fun v(text: String) {
        Log.v(name, fmt(text, LogLevel.VERBOSE))
    }

    override fun e(text: String) {
        Log.e(name, fmt(text, LogLevel.ERROR))
    }

    override fun i(text: String) {
        Log.i(name, fmt(text, LogLevel.INFO))
    }

    override fun cry(text: String) {
        Log.wtf(name, fmt(text, LogLevel.CRY))
    }
}

