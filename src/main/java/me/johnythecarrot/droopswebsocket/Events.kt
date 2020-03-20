package me.johnythecarrot.droopswebsocket

data class MessageReceivedEvent(val message: String)

data class WebSocketClosedEvent(val clean: Boolean, val statusCode: Int, val reason: String)