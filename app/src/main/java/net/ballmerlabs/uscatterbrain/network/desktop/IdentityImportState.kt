package net.ballmerlabs.uscatterbrain.network.desktop

import android.os.Parcel
import android.os.ParcelUuid
import android.os.Parcelable
import java.util.UUID

data class IdentityImportState(
    val appName: String,
    val appSig: ByteArray,
    val handle: UUID
): Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readString()!!,
        parcel.createByteArray()!!,
        parcel.readParcelable<ParcelUuid>(ParcelUuid::class.java.classLoader)!!.uuid
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as IdentityImportState

        if (appName != other.appName) return false
        if (!appSig.contentEquals(other.appSig)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = appName.hashCode()
        result = 31 * result + appSig.contentHashCode()
        return result
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(appName)
        parcel.writeByteArray(appSig)
        parcel.writeParcelable(ParcelUuid(handle), flags)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<IdentityImportState> {
        override fun createFromParcel(parcel: Parcel): IdentityImportState {
            return IdentityImportState(parcel)
        }

        override fun newArray(size: Int): Array<IdentityImportState?> {
            return arrayOfNulls(size)
        }
    }
}