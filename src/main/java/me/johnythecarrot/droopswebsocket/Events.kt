package me.johnythecarrot.droopswebsocket

data class MessageReceivedEvent(val message: String, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MessageReceivedEvent

        if (message != other.message) return false
        if (!bytes.contentEquals(other.bytes)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = message.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

data class WebSocketClosedEvent(val clean: Boolean, val statusCode: Int, val reason: String)