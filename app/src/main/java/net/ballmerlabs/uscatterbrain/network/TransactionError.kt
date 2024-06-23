package net.ballmerlabs.uscatterbrain.network

import java.util.UUID

class TransactionError(val luid: UUID, override val message: String): Throwable() {
}