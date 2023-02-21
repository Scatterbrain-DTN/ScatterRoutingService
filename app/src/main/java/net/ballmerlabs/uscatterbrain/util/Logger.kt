package net.ballmerlabs.uscatterbrain.util

import android.content.Context
import io.reactivex.plugins.RxJavaPlugins
import net.ballmerlabs.uscatterbrain.ScatterbrainThreadFactory
import net.ballmerlabs.uscatterbrain.util.LoggerImpl.Companion.LOGS_DIR
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.full.companionObject

val loggerScheduler = lazy { RxJavaPlugins.createIoScheduler(ScatterbrainThreadFactory("logging")) }
var logger: (c: Class<*>) -> Logger = { c -> LoggerImpl(c) }
private var cacheFileDir: File? = null
var logsDir: File? = null


fun <T: Any> getCompanionClass(c: Class<T>): Class<*> {
    return c.enclosingClass?.takeIf { c.enclosingClass.kotlin.companionObject?.java == c } ?: c
}

fun <T: Any> getCompanionClass(c: KClass<T>): KClass<*> {
    return getCompanionClass(c.java).kotlin
}

fun <T: Context> T.initDiskLogging() {
    val log by scatterLog()
    log.v("init disk logging")
    cacheFileDir = this.applicationContext!!.cacheDir
    logsDir = File(cacheFileDir, LOGS_DIR)
}

fun <T: Any> T.scatterLog(): Lazy<Logger> {
    return lazy { logger(this.javaClass) }
}



enum class LogLevel(val str: String) {
    DEBUG("DEBUG"),
    WARN("WARN"),
    VERBOSE("VERBOSE"),
    INFO("INFO"),
    ERROR("ERROR"),
    CRY("CRYY")
}

abstract class Logger(c: Class<*>) {
    protected val name: String = getCompanionClass(c).name.replace("net.ballmerlabs", "")

    protected fun fmt(text: String, level: LogLevel): String {
        return "[${level.str}]: $text"
    }

    abstract fun getCurrentLog(): File?

    abstract fun d(text: String)

    abstract fun w(text: String)

    abstract fun v(text: String)

    abstract fun e(text: String)

    abstract fun i(text: String)

    abstract fun cry(text: String)
}


