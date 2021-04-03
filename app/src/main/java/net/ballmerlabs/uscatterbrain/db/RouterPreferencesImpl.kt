package net.ballmerlabs.uscatterbrain.db

import android.content.SharedPreferences
import net.ballmerlabs.uscatterbrain.RouterPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * wrapper class for sharedPreferences to allow dagger2 injection
 *
 * used to control router parameters from frontend
 */
@Singleton
class RouterPreferencesImpl @Inject constructor(
        private val preferences: SharedPreferences
) : RouterPreferences {
    override fun getBoolean(key: String?, def: Boolean): Boolean {
        return java.lang.Boolean.parseBoolean(preferences.getString(key, def.toString()))
    }

    override fun getFloat(key: String?, def: Float): Float {
        return preferences.getString(key, def.toString())!!.toFloat()
    }

    override fun getString(key: String?, def: String?): String? {
        return preferences.getString(key, def)
    }

    override fun getStringSet(key: String?, def: Set<String?>?): Set<String?>? {
        return preferences.getStringSet(key, def)
    }

    override val all: Map<String?, *>?
        get() = preferences.all

    override fun getLong(key: String?, def: Long): Long {
        return preferences.getString(key, def.toString())!!.toLong()
    }

    override fun getInt(key: String?, def: Int): Int {
        return preferences.getString(key, def.toString())!!.toInt()
    }

    override fun contains(key: String?): Boolean {
        return preferences.contains(key)
    }

}