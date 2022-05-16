package net.ballmerlabs.uscatterbrain.util

import net.ballmerlabs.uscatterbrain.RouterPreferences

class MockRouterPreferences: RouterPreferences {
    private val data = mutableMapOf<String?, Any?>()

    fun <T> putValue(key: String?, value: T) {
        data[key] = value
    }

    override fun getBoolean(key: String?, def: Boolean): Boolean {
        return data[key] as Boolean
    }

    override fun getFloat(key: String?, def: Float): Float {
        return data[key] as Float
    }

    override fun getLong(key: String?, def: Long): Long {
        return data[key] as Long
    }

    override fun getInt(key: String?, def: Int): Int {
        return data[key] as Int
    }

    override fun getString(key: String?, def: String?): String? {
        return data[key] as String?
    }

    // avoid "unchecked cast" warning
    private inline fun <reified T: Set<String?>> getStringSet(key: String?): T? {
        return data[key] as? T
    }

    override fun getStringSet(key: String?, def: Set<String?>?): Set<String?>? {
        return getStringSet(key)?: def
    }

    override val all: Map<String?, *>
        get() = data

    override fun contains(key: String?): Boolean {
        return data.containsKey(key)
    }
}