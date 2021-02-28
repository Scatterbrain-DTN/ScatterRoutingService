package net.ballmerlabs.uscatterbrain.network

import java.io.IOException
import java.io.InputStream

/**
 * Since protobuf's api for reading streams into bytebuffers lacks
 * the ability to read partial streams, this hack should
 * trick it into not devouring the entire inputstream
 */
class CappedInputStream(private val mStream: InputStream, private var mRemaining: Int) : InputStream() {
    @Throws(IOException::class)
    override fun read(b: ByteArray): Int {
        if (mRemaining <= 0) return -1
        val bytesread = mStream.read(b, 0, mRemaining)
        mRemaining -= bytesread
        return bytesread
    }

    @Throws(IOException::class)
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (mRemaining <= 0) return -1
        var l = mRemaining
        if (mRemaining + off > b.size) l = b.size - off
        val bytesread = mStream.read(b, off, l)
        mRemaining -= bytesread
        return bytesread
    }

    @Throws(IOException::class)
    override fun skip(n: Long): Long {
        return mStream.skip(n)
    }

    @Throws(IOException::class)
    override fun available(): Int {
        return Math.min(mStream.available(), mRemaining)
    }

    @Throws(IOException::class)
    override fun close() {
        mStream.close()
    }

    @Synchronized
    override fun mark(readlimit: Int) {
        mStream.mark(readlimit)
    }

    @Synchronized
    @Throws(IOException::class)
    override fun reset() {
        mStream.reset()
    }

    override fun markSupported(): Boolean {
        return mStream.markSupported()
    }

    @Throws(IOException::class)
    override fun read(): Int {
        return if (mRemaining > 0) {
            val `val` = mStream.read()
            mRemaining--
            `val`
        } else {
            -1
        }
    }

}