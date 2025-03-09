package net.ballmerlabs.uscatterbrain.db

import net.ballmerlabs.uscatterbrain.db.entities.MerkleBundle

class MerkleHelper(
    val hash: ByteArray,
    private var pos: Long = 0,
    private val bundles: ArrayList<MerkleBundle> = arrayListOf(),
) {
    private fun isChildOne(): Boolean? {
        return if (hash.size * Byte.SIZE_BITS > pos) {
            hash[(pos/8).toInt()].toLong() shr (pos % Byte.SIZE_BITS.toLong()).toInt() and  1.toLong() == 0.toLong()
        } else {
            null
        }
    }


    fun getBundles(): List<MerkleBundle> {
        while (pos < hash.size * Byte.SIZE_BITS) {

        }

        return bundles
    }


}