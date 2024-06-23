package net.ballmerlabs.uscatterbrain.network.wifidirect

data class WifiGroupInfo(
    val networkName: String,
    val passphrase: String,
    val band: Int
)