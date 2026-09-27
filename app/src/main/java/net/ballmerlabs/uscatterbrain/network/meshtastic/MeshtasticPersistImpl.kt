package net.ballmerlabs.uscatterbrain.network.meshtastic

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeshtasticPersistImpl @Inject constructor(
    val prefs: DataStore<Preferences>
) {
}