package net.ballmerlabs.uscatterbrain

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

fun isActive(context: Context): Boolean {
    val key = stringPreferencesKey(context.getString(R.string.pref_powersave))
    val res = runBlocking { context.dataStore.data.map { pref -> pref[key] }.firstOrNull() }
    return res == context.getString(R.string.powersave_active)

}

fun isPassive(context: Context): Boolean {
    val key = stringPreferencesKey(context.getString(R.string.pref_powersave))
    val res = runBlocking { context.dataStore.data.map { pref -> pref[key] }.firstOrNull() }
    return res == context.getString(R.string.powersave_passive)

}

fun setActiveBlocking(context: Context) {
    runBlocking { setActive(context) }
}

fun setPassiveBlocking(context: Context) {
    runBlocking { setPassive(context) }
}


suspend fun setActive(context: Context) {
    val key = stringPreferencesKey(context.getString(R.string.pref_powersave))
    val active = context.getString(R.string.powersave_active)
    context.dataStore.edit { pref -> pref[key] = active }

}

suspend fun setPassive(context: Context) {
    val key = stringPreferencesKey(context.getString(R.string.pref_powersave))
    val passive = context.getString(R.string.powersave_passive)
    context.dataStore.edit { pref -> pref[key] = passive }
}


/**
 * dagger2 interface for RouterPreferences
 */
interface RouterPreferences {
    fun getBoolean(key: String, def: Boolean?): Boolean?
    fun getFloat(key: String, def: Float?): Float?
    fun getLong(key: String, def: Long?): Long?
    fun getInt(key: String, def: Int?): Int?
    fun getString(key: String, def: String?): String?
    fun getStringSet(key: String, def: Set<String?>?): Set<String?>?
    val all: Map<Preferences.Key<*>, Any>?
    operator fun <T> contains(key: Preferences.Key<T>): Boolean

    companion object {
        const val PREF_NAME = "RouterPrefs"
    }
}