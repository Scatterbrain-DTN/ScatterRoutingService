package net.ballmerlabs.uscatterbrain

interface RouterPreferences {
    fun getBoolean(key: String?, def: Boolean): Boolean
    fun getFloat(key: String?, def: Float): Float
    fun GetLong(key: String?, def: Long): Long
    fun getInt(key: String?, def: Int): Int
    fun getString(key: String?, def: String?): String?
    fun getStringSet(key: String?, def: Set<String?>?): Set<String?>?
    val all: Map<String?, *>?
    operator fun contains(key: String?): Boolean
}