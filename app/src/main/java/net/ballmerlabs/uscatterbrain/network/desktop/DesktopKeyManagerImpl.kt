package net.ballmerlabs.uscatterbrain.network.desktop

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.reactivex.Completable
import io.reactivex.Single
import kotlinx.coroutines.rx2.rxCompletable
import kotlinx.coroutines.rx2.rxSingle
import net.ballmerlabs.uscatterbrain.dataStore
import net.ballmerlabs.uscatterbrain.network.b64
import javax.inject.Inject

val KEY_PUB =  stringPreferencesKey("desktop-pub")
val KEY_PRIV =  stringPreferencesKey("desktop-priv")

@DesktopApiScope
class DesktopKeyManagerImpl @Inject constructor(
    val context: Context
) : DesktopKeyManager {

    override fun getKeypair(): Single<PublicKeyPair> {
        return rxSingle {
            val p = context.dataStore.edit { p ->
                val pub = p[KEY_PUB]
                val priv = p[KEY_PRIV]
                if (pub == null || priv == null) {
                   val key = PublicKeyPair.create()
                    p[KEY_PUB] = key.pubkey.b64()
                    p[KEY_PRIV] = key.privkey.b64()
                }
            }

           PublicKeyPair(
                privkey = p[KEY_PRIV]!!.b64(),
                pubkey = p[KEY_PUB]!!.b64()
            )
        }
    }

    private fun deleteKeypair(): Completable {
        return rxCompletable {
            context.dataStore.edit { p ->
                p.remove(KEY_PUB)
                p.remove(KEY_PRIV)
            }
        }
    }

    companion object {
        data class StateEntry(
            var adminAuth: Boolean = false
        )
    }
}