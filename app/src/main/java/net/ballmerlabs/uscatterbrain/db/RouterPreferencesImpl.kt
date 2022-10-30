package net.ballmerlabs.uscatterbrain.db

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
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
        private val preferences: DataStore<Preferences>
) : RouterPreferences {
    override fun getBoolean(key: String, def: Boolean?): Boolean? {
        val k = booleanPreferencesKey(key)
        return runBlocking {
            val flow: Flow<Boolean?> =  preferences.data.map { pref -> pref[k] ?: def }
            flow.firstOrNull()
        }
    }

    override fun getFloat(key: String, def: Float?): Float? {
        val k = floatPreferencesKey(key)
        return runBlocking {
            val flow: Flow<Float?> =  preferences.data.map { pref -> pref[k] ?: def }
            flow.firstOrNull()
        }
    }

    override fun getString(key: String, def: String?): String? {
        val k = stringPreferencesKey(key)
        return runBlocking {
            val flow: Flow<String?> =  preferences.data.map { pref -> pref[k] ?: def }
            flow.firstOrNull()
        }
    }

    override fun getStringSet(key: String, def: Set<String?>?): Set<String?>? {
        val k = stringPreferencesKey(key)
        return runBlocking {
            val flow: Flow<String?> =  preferences.data.map { pref -> pref[k] }
            flow.toSet()
        }
    }

    override val all: Map<Preferences.Key<*>, Any>?
        get() = runBlocking { preferences.data.map { pref -> pref.asMap() }.firstOrNull() }

    override fun getLong(key: String, def: Long?): Long? {
        val k = longPreferencesKey(key)
        return runBlocking {
            val flow: Flow<Long?> =  preferences.data.map { pref -> pref[k] ?: def }
            flow.firstOrNull()
        }
    }

    override fun getInt(key: String, def: Int?): Int? {
        val k = intPreferencesKey(key)
        return runBlocking {
            val flow: Flow<Int?> =  preferences.data.map { pref -> pref[k] ?: def }
            flow.firstOrNull()
        }
    }

    override fun <T> contains(key: Preferences.Key<T>): Boolean {
        return runBlocking { preferences.data.map { pref -> pref.contains(key) }.first() }
    }

}