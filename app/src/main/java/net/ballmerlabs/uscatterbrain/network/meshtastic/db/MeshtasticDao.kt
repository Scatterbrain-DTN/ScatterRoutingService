package net.ballmerlabs.uscatterbrain.network.meshtastic.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.sdk.ConfigBundle
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.SessionPasskey

const val SESSION_KEY_NODE_NUM = "node-num"
const val SESSION_KEY_FIRMWARE = "firmware"
const val SESSION_KEY_PASSKEY = "passkey"
const val SESSION_KEY_PASSKEY_EXP_MS = "passkey-expires"

@Dao
abstract class MeshtasticDao {
    @Query("SELECT * FROM meshtastic_sessions WHERE `key` = :key AND scope = :scope")
    abstract suspend fun getSession(key: String, scope: String): SessionEntity?

    @Query("SELECT * FROM configs WHERE `key` = :key AND scope = :scope")
    abstract suspend fun getConfig(key: String, scope: String): ConfigEntity?

    @Query("SELECT * FROM configs")
    abstract suspend fun selectAllConfigs(): List<ConfigEntity>

    @Query("SELECT * FROM meshtastic_sessions")
    abstract suspend fun selectAllSessions(): List<SessionEntity>

    @Query("SELECT * FROM meshtastic_nodes WHERE scope = :scope")
    abstract suspend fun loadNodesByScope(scope: String): List<Node>

    @Query("DELETE FROM meshtastic_nodes WHERE id = :id")
    abstract suspend fun deleteNodeById(id: Long)

    @Query("SELECT * FROM heartbeats")
    abstract suspend fun getAllHeartbeats(): List<Heartbeat>

    @Query("SELECT * FROM meshtastic_channels WHERE scope = :scope")
    abstract suspend fun loadChannelsByScope(scope: String): List<ChannelEntity>

    @Query("DELETE FROM meshtastic_sessions WHERE scope = :scope AND `key` = :key")
    abstract suspend fun deleteSession(scope: String, key: String)

    @Query("SELECT * FROM meshtastic_nodes WHERE nodeNum = :nodeNum")
    abstract suspend fun getNodeByNum(nodeNum: Long): Node?

    @Upsert
    abstract suspend fun upsertConfig(configEntity: ConfigEntity)

    @Upsert
    abstract suspend fun upsertSession(sessionEntity: SessionEntity)

    @Upsert
    abstract suspend fun upsertChannel(channelEntity: ChannelEntity)

    @Upsert
    abstract suspend fun upsertNode(node: Node)

    @Upsert
    abstract suspend fun upsertHeartbeat(heartbeat: Heartbeat)


    @Query("DELETE FROM configs")
    abstract suspend fun deleteAllConfigs()

    @Query("DELETE FROM meshtastic_nodes")
    abstract suspend fun deleteNodes()

    @Query("DELETE FROM meshtastic_channels")
    abstract suspend fun deleteChannels()

    @Query("DELETE FROM meshtastic_sessions")
    abstract suspend fun deleteSessions()

    @Transaction
    open suspend fun deleteSessionPasskey(scope: String) {
        deleteSession(scope, SESSION_KEY_PASSKEY)
        deleteSession(scope, SESSION_KEY_PASSKEY_EXP_MS)
    }

    @Transaction
    open suspend fun saveConfig(config: ConfigBundle, scope: String) {
        upsertSession(
            SessionEntity(
                scope,
                SESSION_KEY_MY_INFO,
                MyNodeInfo.ADAPTER.encode(config.myInfo)
            )
        )
        upsertSession(
            SessionEntity(
                scope,
                SESSION_KEY_METADATA,
                DeviceMetadata.ADAPTER.encode(config.metadata)
            )
        )
        deleteAllConfigs()
        config.configs.forEachIndexed { i, c ->
            val section = "config:$i"
            upsertConfig(
                ConfigEntity(
                    scope, section, Config.ADAPTER.encode(c)
                )
            )
        }
        config.moduleConfigs.forEachIndexed { i, m ->
            val section = "module:$i"
            upsertConfig(ConfigEntity(scope, section, ModuleConfig.ADAPTER.encode(m)))
        }
    }

    @Transaction
    open suspend fun saveSessionPasskey(scope: String, passkey: SessionPasskey) {
        upsertSession(SessionEntity(scope, SESSION_KEY_PASSKEY, passkey.bytes.toByteArray()))
        upsertSession(SessionEntity(scope, SESSION_KEY_PASSKEY_EXP_MS, passkey.expiresAtEpochMs.toString().encodeToByteArray()))
    }

    @Transaction
    open suspend fun saveOwnNode(scope: String, nodeNum: NodeId, firmwareVersion: String) {
        val storedNumBytes = getSession(SESSION_KEY_NODE_NUM, scope)?.data
        if (storedNumBytes != null) {
            val stored = storedNumBytes.decodeToString().toIntOrNull()
            if (stored != null && stored != nodeNum.raw) {
                deleteNodes()
                deleteChannels()
                deleteAllConfigs()
                deleteSessions()
            }
        }
        upsertSession(
            SessionEntity(
                scope,
                SESSION_KEY_NODE_NUM,
                nodeNum.raw.toString().encodeToByteArray()
            )
        )
        upsertSession(
            SessionEntity(
                scope,
                SESSION_KEY_FIRMWARE,
                firmwareVersion.encodeToByteArray()
            )
        )
        getNodeByNum(nodeNum.raw.toLong())?.let { node ->
            upsertNode(node)
        }
    }
}