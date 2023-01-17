package net.ballmerlabs.uscatterbrain.util

import androidx.datastore.preferences.core.Preferences
import net.ballmerlabs.uscatterbrain.RouterPreferences

class MockRouterPreferences: RouterPreferences {
    private val data = mutableMapOf<String, Any?>()

    fun <T> putValue(key: String, value: T) {
        data[key] = value
    }

    override fun getBoolean(key: String, def: Boolean?): Boolean? {
        return data[key] as? Boolean?: def
    }

    override fun getFloat(key: String, def: Float?): Float? {
        return data[key] as? Float?: def
    }

    override fun getLong(key: String, def: Long?): Long? {
        return data[key] as? Long?: def
    }

    override fun getInt(key: String, def: Int?): Int? {
        return data[key] as? Int?
    }

    override fun getString(key: String, def: String?): String? {
        return data[key] as? String?
    }

    // avoid "unchecked cast" warning
    private inline fun <reified T: Set<String?>> getStringSet(key: String?): T? {
        return data[key] as? T
    }

    override fun getStringSet(key: String, def: Set<String?>?): Set<String?>? {
        return getStringSet(key)?: def
    }

    override val all: Map<Preferences.Key<*>, Any>
        get() = HashMap()

    override fun <T> contains(key: Preferences.Key<T>): Boolean {
        return data.containsKey(key.name)
    }
}