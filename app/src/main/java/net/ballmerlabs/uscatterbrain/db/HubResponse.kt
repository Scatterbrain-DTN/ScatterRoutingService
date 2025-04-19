package net.ballmerlabs.uscatterbrain.db

import io.reactivex.Observable
import net.ballmerlabs.uscatterbrain.db.entities.MerkleBundle

data class HubResponse(
    val hubs: Observable<MerkleBundle>,
    val exclude: Observable<ByteArray>
)