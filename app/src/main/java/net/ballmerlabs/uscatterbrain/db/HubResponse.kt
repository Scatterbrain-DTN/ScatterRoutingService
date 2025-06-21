package net.ballmerlabs.uscatterbrain.db

import io.reactivex.Flowable
import io.reactivex.Observable
import net.ballmerlabs.uscatterbrain.db.entities.MerkleBundle

data class HubResponse(
    val hubs: Flowable<MerkleBundle>,
    val exclude: Flowable<ByteArray>
)