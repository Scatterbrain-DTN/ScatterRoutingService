package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import java.io.Serializable

open class BootstrapRequest : Parcelable {
    private val extras = Bundle()

    protected constructor() {}
    protected constructor(`in`: Parcel) {
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
        const val TRANSPORT_FROM = "proto-from"
        const val TRANSPORT_TO = "proto-to"
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