package me.johnythecarrot.droopswebsocket

class AlreadyConnectedException(message: String): Exception(message)

class ConnectionRejectedException(message: String) : Exception(message)

class ConnectionClosedException(message: String) : Exception(message)

class UnknownOpcodeException(message: String) : Exception(message)

class InvalidHTTPResponse(message: String) : Exception(message)

class MalformedFrameException(message: String): Exception(message)