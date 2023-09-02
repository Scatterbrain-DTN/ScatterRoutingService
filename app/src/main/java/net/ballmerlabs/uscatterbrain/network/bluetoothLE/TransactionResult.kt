package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import io.reactivex.Maybe

/**
 * transactionresult is a combination optional and data class holding the result of a GATT
 * transaction. It is used to set the next stage in the FSM and optionally to bootstrap
 */
data class TransactionResult<T>(
        val item: T? = null,
        val err: Throwable? = null,
        val stage: String? = null
) {
    val isPresent: Boolean
        get() = item != null

    val isError: Boolean
    get() = err != null

    fun merge(newres: TransactionResult<T>): Maybe<TransactionResult<T>> {
        return Maybe.defer {
            when {
                newres.err != null -> Maybe.error(newres.err)
                err != null -> Maybe.error(err)
                newres.stage == null && stage == null -> Maybe.empty()
                newres.item != null && item != null && newres.item != item -> Maybe.error(IllegalStateException("conflicting items"))
                newres.stage != null && stage != null -> Maybe.error(IllegalStateException("conflicting stages"))
                else -> {
                    val newitem = item?:newres.item
                    val newstage = stage?:newres.stage
                    val err = err?:newres.err
                    val result = TransactionResult(
                        item = newitem,
                        err = err,
                        stage = newstage
                    )
                    Maybe.just(result)
                }
            }
        }
    }

    companion object {
        fun <T> of(v: T): TransactionResult<T> {
            return TransactionResult(v)
        }

        fun <T> of(v: T, stage: String): TransactionResult<T> {
            return TransactionResult(v, stage = stage)
        }

        fun <T> of(stage: String): TransactionResult<T> {
            return TransactionResult(item = null, stage = stage)
        }

        fun <T> empty(): TransactionResult<T> {
            return TransactionResult(null)
        }

        fun <T> err(throwable: Throwable): TransactionResult<T> {
            return TransactionResult(item = null, err = throwable, stage = STAGE_TERMINATE)
        }

        const val STAGE_TERMINATE = "exit"
        const val STAGE_START = "start"
        const val STAGE_LUID = "luid"
        const val STAGE_ADVERTISE = "advertise"
        const val STAGE_ELECTION_HASHED = "election-hashed"
        const val STAGE_ELECTION = "election"
        const val STAGE_UPGRADE = "upgrade"
        const val STAGE_BLOCKDATA = "blockdata"
        const val STAGE_DECLARE_HASHES = "declarehashes"
        const val STAGE_IDENTITY = "identity"
        
    }
}