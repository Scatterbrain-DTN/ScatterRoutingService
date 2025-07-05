package net.ballmerlabs.uscatterbrain.db

import io.reactivex.Flowable
import io.reactivex.Observable
import net.ballmerlabs.uscatterbrain.db.entities.MerkleBundle

data class MerkleElement(
    val bundle: MerkleBundle,
    val last: Boolean
)

data class HubResponse(
    val hubs: Flowable<MerkleElement>,
    val exclude: Flowable<ByteArray>
)