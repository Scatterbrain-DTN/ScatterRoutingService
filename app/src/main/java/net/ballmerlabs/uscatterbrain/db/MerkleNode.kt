package net.ballmerlabs.uscatterbrain.db

import net.ballmerlabs.uscatterbrain.db.entities.MerkleBundle

data class MerkleNode(
    val bundle: MerkleBundle,
    var childOne: MerkleNode? = null,
    var childTwo: MerkleNode? = null,
) {
    companion object {
        fun fromBundles(bundles: List<MerkleBundle>, root: Long): MerkleNode {
            val childmap = mutableMapOf<Long, MerkleBundle>()
            for (bundle in bundles) {
                if (bundle.id != null)
                    childmap[bundle.id!!] = bundle
            }

            val root = childmap[root]!!


            return fromBundles(root, childmap)

        }

        private fun fromBundles(root: MerkleBundle, childMap: Map<Long, MerkleBundle>): MerkleNode {
            return MerkleNode(
                bundle = root,
                childOne = if (childMap.containsKey(root.childOne))
                    fromBundles(childMap[root.childOne!!]!!, childMap)
                else
                    null,
                childTwo = if (childMap.containsKey(root.childTwo))
                    fromBundles(childMap[root.childTwo!!]!!, childMap)
                else
                    null
            )
        }
    }
}