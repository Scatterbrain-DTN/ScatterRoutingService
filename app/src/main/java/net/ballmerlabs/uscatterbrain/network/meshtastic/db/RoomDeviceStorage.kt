package net.ballmerlabs.uscatterbrain.network.meshtastic.db

import net.ballmerlabs.uscatterbrain.network.MeshtasticDbScope
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticConnectionSubcomponent
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.sdk.ConfigBundle
import org.meshtastic.sdk.DeviceStorage
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.SessionPasskey
import javax.inject.Inject
import javax.inject.Named
import kotlin.time.Clock

const val SESSION_KEY_METADATA = "key-metadata"
const val SESSION_KEY_MY_INFO = "key-myinfo"


@MeshtasticDbScope
class RoomDeviceStorage @Inject constructor(
    val meshtasticDao: MeshtasticDao,
    @Named(MeshtasticConnectionSubcomponent.CONNECTION_NAME) val scope: String
) : DeviceStorage {
    override suspend fun loadNodes(): Map<NodeId, NodeInfo> {
        return meshtasticDao.loadNodesByScope(scope)
            .map{ v ->
                v.rawNodeInfo?.let { bytes ->
                try {
                    NodeInfo.ADAPTER.decode(bytes)
                } catch (_: Exception) {
                    null
                }
            } ?: NodeInfo.build {
                num = v.nodeNum.toInt()
            }
        }.associateBy { v -> NodeId(v.num) }
    }

    override suspend fun saveNode(node: NodeInfo) {
        meshtasticDao.upsertNode(Node(
            nodeNum = node.num.toLong(),
            userId = node.user?.id,
            longName = node.user?.long_name,
            shortName = node.user?.short_name,
            hwModel = node.user?.hw_model?.value?.toLong(),
            isLicensed = node.user?.is_licensed,
            role = node.user?.role?.value?.toLong(),
            publicKey = node.user?.public_key?.toByteArray(),
            lastHeardEpoch = node.last_heard.toLong(),
            snr = node.snr.toDouble(),
            rssi = 0,
            hopsAway = node.hops_away?.toLong(),
            viaMqtt = node.via_mqtt,
            isSelf = false,
            rawNodeInfo = NodeInfo.ADAPTER.encode(node),
            scope = scope
        ))
    }

    override suspend fun removeNode(nodeId: NodeId) {
        meshtasticDao.deleteNodeById(nodeId.raw.toLong())
    }

    override suspend fun loadConfig(): ConfigBundle? {
        val myInfoBytes = meshtasticDao.getSession(SESSION_KEY_MY_INFO, scope)?.data
            ?: return null
        val metaBytes = meshtasticDao.getSession(SESSION_KEY_METADATA, scope)?.data

        val myInfo: MyNodeInfo = try {
            MyNodeInfo.ADAPTER.decode(myInfoBytes)
        } catch (_: Exception) {
            return null
        }
        val metadata: DeviceMetadata = metaBytes?.let {
            try {
                DeviceMetadata.ADAPTER.decode(it)
            } catch (_: Exception) {
                null
            }
        } ?: DeviceMetadata.build {  }

        val allConfigs = meshtasticDao.selectAllConfigs()
        val configs: List<Config> = allConfigs
            .filter { it.key.startsWith("config:") }
            .mapNotNull { row ->
                try {
                    Config.ADAPTER.decode(row.data)
                } catch (_: Exception) {
                    null
                }
            }

        val moduleConfigs: List<ModuleConfig> = allConfigs
            .filter { it.key.startsWith("module:") }
            .mapNotNull { row ->
                try {
                    ModuleConfig.ADAPTER.decode(row.data)
                } catch (_: Exception) {
                    null
                }
            }

        return ConfigBundle(
            myInfo = myInfo,
            metadata = metadata,
            configs = configs,
            moduleConfigs = moduleConfigs,
        )
    }

    override suspend fun saveConfig(config: ConfigBundle) {
        meshtasticDao.saveConfig(config, scope)
    }

    override suspend fun loadChannels(): List<Channel> {
        return meshtasticDao.loadChannelsByScope(scope).map { v ->
            Channel.build {
                index = v.idx.toInt()
                role = Channel.Role.fromValue(v.role.toInt())!!
                settings = if (v.settingsRaw != null) {
                    try {
                        ChannelSettings.ADAPTER.decode(v.settingsRaw)
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }
            }
        }
    }

    override suspend fun saveChannels(channels: List<Channel>) {
        for (channel in channels) {
            val nc = ChannelEntity(
                idx = channel.index.toLong(),
                role = channel.role.value.toLong(),
                scope = scope,
                psk = channel.settings?.psk?.toByteArray(),
                pskIndex = null,
                uplink = channel.settings?.uplink_enabled ?: false,
                downlink = channel.settings?.downlink_enabled ?: false,
                name = channel.settings?.name,
                settingsRaw = Channel.ADAPTER.encode(channel)
            )
            meshtasticDao.upsertChannel(nc)
        }
    }

    override suspend fun recordOwnNode(
        nodeNum: NodeId,
        firmwareVersion: String,
    ) {
        meshtasticDao.saveOwnNode(scope, nodeNum, firmwareVersion)
    }

    override suspend fun clear() {
        meshtasticDao.deleteNodes()
        meshtasticDao.deleteChannels()
        meshtasticDao.deleteAllConfigs()
        meshtasticDao.deleteSessions()
    }

    override fun close() {

    }

    override suspend fun saveSessionPasskey(passkey: SessionPasskey) {
        meshtasticDao.saveSessionPasskey(scope, passkey)
    }

    override suspend fun loadSessionPasskey(): SessionPasskey? {
        val bytes = meshtasticDao.getSession(SESSION_KEY_PASSKEY, scope)?.data
            ?: return null
        val expiresBytes = meshtasticDao.getSession(SESSION_KEY_PASSKEY_EXP_MS, scope)?.data
            ?: return null
        val expiresAtMs = expiresBytes.decodeToString().toLongOrNull() ?: return null
        val now = Clock.System.now().toEpochMilliseconds()
        if (expiresAtMs <= now) {
            meshtasticDao.deleteSessionPasskey(scope)
            return null
        }
        return SessionPasskey(bytes.toByteString(), expiresAtMs)
    }

    override suspend fun saveHeartbeat(
        nodeId: NodeId,
        epochMillis: Long,
    ) {
        meshtasticDao.upsertHeartbeat(Heartbeat(nodeNum = nodeId.raw.toLong(), lastHeartbeatAt = epochMillis))
    }

    override suspend fun loadHeartbeats(): Map<NodeId, Long> {
        return meshtasticDao.getAllHeartbeats().associate { v -> NodeId(v.nodeNum.toInt()) to v.lastHeartbeatAt }
    }
}