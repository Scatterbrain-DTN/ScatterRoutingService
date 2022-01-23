package net.ballmerlabs.uscatterbrain.util

import android.util.Log
import kotlin.reflect.KClass
import kotlin.reflect.full.companionObject

fun <T: Any> getCompanionClass(c: Class<T>): Class<*> {
    return c.enclosingClass?.takeIf { c.enclosingClass.kotlin.companionObject?.java == c } ?: c
}

fun <T: Any> getCompanionClass(c: KClass<T>): KClass<*> {
    return getCompanionClass(c.java).kotlin
}

fun <T: Any> T.scatterLog(): Lazy<Logger> {
    return lazy { Logger(this.javaClass) }
}

class Logger(c: Class<*>) {
    private val name: String = getCompanionClass(c).name

    enum class LogLevel(val str: String) {
        DEBUG("DEBUG"),
        WARN("WARN"),
        VERBOSE("VERBOSE"),
        INFO("INFO"),
        ERROR("ERROR"),
        CRY("CRYY")
    }

    private fun fmt(text: String, level: LogLevel): String {
        return "[${level.str}]: $text"
    }

    fun d(text: String) {
        Log.d(name, fmt(text, LogLevel.DEBUG))
    }

    fun w(text: String) {
        Log.w(name, fmt(text, LogLevel.WARN))
    }

    fun v(text: String) {
        Log.v(name, fmt(text, LogLevel.VERBOSE))
    }

    fun e(text: String) {
        Log.e(name, fmt(text, LogLevel.ERROR))
    }

    fun i(text: String) {
        Log.i(name, fmt(text, LogLevel.INFO))
    }

    fun cry(text: String) {
        Log.wtf(name, fmt(text, LogLevel.CRY))
    }
}


