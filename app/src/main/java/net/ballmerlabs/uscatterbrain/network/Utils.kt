package net.ballmerlabs.uscatterbrain.network

object Utils {
    fun splitChunks(source: ByteArray): Array<ByteArray?> {
        val CHUNK_SIZE = 10
        val ret = arrayOfNulls<ByteArray>(Math.ceil(source.size / CHUNK_SIZE.toDouble()).toInt())
        var start = 0
        for (i in ret.indices) {
            if (start + CHUNK_SIZE > source.size) {
                ret[i] = ByteArray(source.size - start)
                System.arraycopy(source, start, ret[i], 0, source.size - start)
            } else {
                ret[i] = ByteArray(CHUNK_SIZE)
                System.arraycopy(source, start, ret[i], 0, CHUNK_SIZE)
            }
            start += CHUNK_SIZE
        }
        return ret
    }
}