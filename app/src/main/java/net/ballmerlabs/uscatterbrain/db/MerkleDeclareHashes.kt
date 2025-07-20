package net.ballmerlabs.uscatterbrain.db

import net.ballmerlabs.uscatterbrain.db.entities.MerkleBundle
import net.ballmerlabs.uscatterbrain.network.proto.DeclareHashesPacket

data class MerkleDeclareHashes(
    val bundle: MerkleBundle,
    val declareHashesPacket: DeclareHashesPacket.Builder
)