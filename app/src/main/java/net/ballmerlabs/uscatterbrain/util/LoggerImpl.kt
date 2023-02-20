package net.ballmerlabs.uscatterbrain.util

import android.util.Log
import io.reactivex.Completable
import io.reactivex.Scheduler
import io.reactivex.disposables.Disposable
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.log

class LoggerImpl(c: Class<*>, private val bufSize: Int = 4096): Logger(c) {
    private val scheduler: Lazy<Scheduler> = loggerScheduler
    private val buffer = ConcurrentLinkedQueue<Disposable>()


    private fun getFileName(number: Int = 0): String {
        val num = if(number == 0) {
            ""
        } else {
            ".$number"
        }
        val date = LocalDate.now()
        val text = date.format(DateTimeFormatter.ISO_DATE)
        return "$text.log$num"
    }
    override fun getCurrentLog(): File? {
        val file = logsDir
        return if (file != null) {
            var num = 1

            if (!file.exists()) {
                file.mkdir()
            }
            var it = File(file, getFileName(num))
            while(it.exists() && it.length() < LOGS_SIZE) {
                num++
                it = File(file, getFileName(num))
            }
            val f = File(file, getFileName(num-1))
            f.createNewFile()
            return f
        } else {
            Log.e("debug","log file null")
            null
        }
    }

    private fun asyncWrite(text: String) {
        val disp = Completable.fromAction{
            val t = "$name: $text\n"
            val f = getCurrentLog()
            f?.appendBytes(t.encodeToByteArray())
            Log.e("debug", "file size ${f?.length()}")

        }
            .doOnError { e -> Log.e("loggermeta", "failed to log: $e") }
            .subscribeOn(scheduler.value)
            .subscribe()
        if(buffer.size > bufSize) {
            buffer.remove()?.dispose()
        }
        buffer.add(disp)
    }

    override fun d(text: String) {
        asyncWrite(text)
        Log.d(name, fmt(text, LogLevel.DEBUG))
    }

    override fun w(text: String) {
        asyncWrite(text)
        Log.w(name, fmt(text, LogLevel.WARN))
    }

    override fun v(text: String) {
        asyncWrite(text)
        Log.v(name, fmt(text, LogLevel.VERBOSE))
    }

    override fun e(text: String) {
        asyncWrite(text)
        Log.e(name, fmt(text, LogLevel.ERROR))
    }

    override fun i(text: String) {
        asyncWrite(text)
        Log.i(name, fmt(text, LogLevel.INFO))
    }

    override fun cry(text: String) {
        asyncWrite(text)
        Log.wtf(name, fmt(text, LogLevel.CRY))
    }

    companion object {
        const val LOGS_SIZE: Long = 1 * 1024 * 1024
        const val LOGS_DIR = "logs"
    }
}

