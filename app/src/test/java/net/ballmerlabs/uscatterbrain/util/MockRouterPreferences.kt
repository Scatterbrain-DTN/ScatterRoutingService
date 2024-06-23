package net.ballmerlabs.uscatterbrain.util

import androidx.datastore.preferences.core.Preferences
import io.reactivex.Maybe
import net.ballmerlabs.uscatterbrain.RouterPreferences

class MockRouterPreferences: RouterPreferences {
    private val data = mutableMapOf<String, Any?>()

    fun <T> putValue(key: String, value: T) {
        data[key] = value
    }

    override fun getBoolean(key: String, def: Boolean?): Maybe<Boolean> {
        return Maybe.just(data[key] as? Boolean?: def)
    }

    override fun getFloat(key: String, def: Float?): Maybe<Float> {
        return Maybe.just(data[key] as? Float?: def)
    }

    override fun getLong(key: String, def: Long?): Maybe<Long> {
        return Maybe.just(data[key] as? Long?: def)
    }

    override fun getInt(key: String, def: Int?): Maybe<Int> {
        return Maybe.just(data[key] as? Int?)
    }

    override fun getString(key: String, def: String?): Maybe<String> {
        return Maybe.just(data[key] as? String?)
    }

    // avoid "unchecked cast" warning
    private inline fun <reified T: Set<String?>> getStringSet(key: String?): T? {
        return data[key] as? T
    }

    override fun getStringSet(key: String, def: Set<String?>?): Maybe<Set<String?>> {
        return Maybe.just(getStringSet(key)?: def)
    }

    override val all: Maybe<Map<Preferences.Key<*>, Any>>
        get() = Maybe.just(HashMap())

    override fun <T> contains(key: Preferences.Key<T>): Boolean {
        return data.containsKey(key.name)
    }
}