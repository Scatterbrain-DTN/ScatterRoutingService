package net.ballmerlabs.uscatterbrain.db

import io.reactivex.Flowable
import net.ballmerlabs.uscatterbrain.db.entities.MerkleBundle

data class MerkleElement(
    val bundle: MerkleBundle,
)

data class HubResponse(
    val hubs: Flowable<MerkleElement>,
    val exclude: Flowable<ByteArray>
)