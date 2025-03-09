package net.ballmerlabs.uscatterbrain.db.entities

data class MerkleInsertCond(
    val parent: Long,
    val childOne: Boolean  = false,
    val childTwo: Boolean = false,
    val pos: Long
) {
    fun complete(hash: ByteArray): Boolean {
        return (pos / Byte.SIZE_BITS) >= hash.size
    }
}