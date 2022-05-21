package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import net.ballmerlabs.uscatterbrain.BootstrapRequestScope
import java.io.Serializable
import javax.inject.Inject

/**
 * Simple parcelable key-value blob used to convey information to
 * initialize a transport module. This is sent by a module performing
 * device discovery to a module intended to perform data transfer
 *
 * The contents of the key-value data is dependent on the modules
 * performing the bootstrapping
 *
 * @constructor create an empty BootstrapRequest
 */
@BootstrapRequestScope
open class BootstrapRequest  : Parcelable {
    protected var extras: Bundle

    protected constructor(): this(Bundle())

    @Inject
    protected constructor(extras: Bundle){
        this.extras =extras
    }

    protected constructor(`in`: Parcel) {
        extras = Bundle()
        extras.readFromParcel(`in`)
    }

    override fun describeContents(): Int {
        return extras.describeContents()
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeBundle(extras)
    }

    fun <T : Parcelable> putParcelableExtra(key: String, parcelable: T) {
        extras.putParcelable(key, parcelable)
    }

    fun <T : Parcelable> getParcelableExtra(key: String): T {
        return extras.getParcelable(key)!!
    }

    fun putStringExtra(key: String, `val`: String) {
        extras.putString(key, `val`)
    }

    fun getStringExtra(key: String): String {
        return extras.getString(key)!!
    }

    fun putSerializableExtra(key: String, `val`: Serializable) {
        extras.putSerializable(key, `val`)
    }

    fun getSerializableExtra(key: String): Serializable {
        return extras.getSerializable(key)!!
    }

    companion object {
        const val TRANSPORT_TO = "proto-to"
        @JvmField
        val CREATOR: Parcelable.Creator<BootstrapRequest> = object : Parcelable.Creator<BootstrapRequest> {
            override fun createFromParcel(`in`: Parcel): BootstrapRequest {
                return BootstrapRequest(`in`)
            }

            override fun newArray(size: Int): Array<BootstrapRequest?> {
                return arrayOfNulls(size)
            }
        }
    }
}