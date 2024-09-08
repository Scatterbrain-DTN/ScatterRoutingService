package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Database object for an app permission ACL
 * maps an identity to a package name and signature
 */
@Entity(indices = [
    Index(value = ["packageName"], unique = true),
    Index(value = ["packageName", "packageSignature"], unique = true),
])
data class ClientApp(
    @ColumnInfo
    var identityFK: Long? = null,

    @ColumnInfo
    var packageName: String,

    @ColumnInfo
    var packageSignature: String? = null,

    @ColumnInfo(defaultValue = "false")
    var isDesktop: Boolean = false
) {
    @PrimaryKey(autoGenerate = true)
    var clientAppID: Long? = null
}

/**
 * internal class used for querying Room database by package name
 */
data class JustPackageName(
        var packageName: String
)

data class JustPackageSig(
    var packageSignature: String
)