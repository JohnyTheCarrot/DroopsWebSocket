package me.johnythecarrot.droopswebsocket

import Opcode
import me.johnythecarrot.droopswebsocket.enums.ConnectionState
import me.johnythecarrot.droopswebsocket.enums.StatusCode
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.*
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread
import kotlin.experimental.and

@ExperimentalUnsignedTypes
class WebSocket(
        private val secure: Boolean,
        private val host: String,
        private val path: String = "/",
        private val port: Int = 80,
        private val protocolVersion: Int = 13,
        private val verbose: Boolean = false
) {
    private var client: Socket? = null
    private val globallyUniqueIdentifier = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    var connectionState: ConnectionState = ConnectionState.DISCONNECTED

    private var closeStatusCode: Int? = null
    private var closedByClient: Boolean = false

    // events
    var connectedEvent: (() -> Unit)? = null
    var messageReceivedEvent: ((MessageReceivedEvent) -> Unit)? = null
    var webSocketClosedEvent: ((WebSocketClosedEvent) -> Unit)? = null

    init {
        // Detect application close, send close frame.
        Runtime.getRuntime().addShutdownHook(object : Thread() {
            override fun run() {
                close(StatusCode.CLOSE_NORMAL, "")
            }
        })
    }

    private val secureSocketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory

    @ExperimentalUnsignedTypes
    fun connect()
    {
        if (client != null)
        {
            throw AlreadyConnectedException("WebSocket already connected")
        }
        client = if (secure)
            secureSocketFactory.createSocket(host, port)
        else
            Socket(host, port)
        connectionState = ConnectionState.CONNECTING
        val handshake = createOpeningHandshake(host, path, protocolVersion = protocolVersion)
        printVerbose("Starting handshake..")
        client!!.getOutputStream().write(handshake.toByteArray())
        val input0 = DataInputStream(client!!.getInputStream())
        thread(start = true)
        {
            while (connectionState != ConnectionState.DISCONNECTED)
            {
                if (connectionState == ConnectionState.CONNECTING)
                {
                    parseHandshakeResponse(input0)
                } else {
                    parseFrame(input0)
                }
            }
        }
    }

    private fun printVerbose(msg: String)
    {
        if (verbose) println(msg)
    }

    private fun isBitEnabled(byte: Byte, position: Int): Boolean
    {
        return (byte.toInt() and (1 shl position-1)) == 1
    }

    private var frameDataFragments = ByteArrayOutputStream()

    @ExperimentalUnsignedTypes
    private fun parseFrame(input: DataInputStream)
    {
        try {
            val byte0 = input.read().toByte()
            if (byte0.toInt() == -1) return
            val fin = isBitEnabled(byte0, 1)

            // if FIN, clear frameDataFragments
            if (fin) frameDataFragments = ByteArrayOutputStream()
            val opcode = Opcode.fromInt((byte0 and 0x0F).toInt())
            val byte1 = input.read() and 0b01111111
            var payloadLength = 0.toLong()
            if (byte1 <= 125) payloadLength = byte1.toLong()
            else if (byte1 == 126) payloadLength = ((input.read() shl 8) or input.read()).toLong()
            else if (byte1 == 127) {
                val byteStream = ByteArrayOutputStream()
                (0..7).forEach { _ ->
                    byteStream.write(input.read())
                }
                payloadLength = bytesToLong(byteStream.toByteArray())
            }
            val payloadData = ByteArray(payloadLength.toInt())
            if (byte1 > 0) {
                input.readFully(payloadData, 0, payloadLength.toInt())
            }

            printVerbose("Received ${opcode.name}.")
            when (opcode) {
                Opcode.TEXT_FRAME -> handleTextFrame(payloadData)
                Opcode.CONNECTION_CLOSE -> handleCloseFrame(payloadData)
                Opcode.CONTINUATION_FRAME -> handleContinuationFrame(fin, payloadData)
                Opcode.PING -> handlePingFrame(payloadData)
                Opcode.PONG -> handlePongFrame(payloadData)
                else -> throw closeAndReturnException(UnknownOpcodeException("Unknown opcode: ${byte0 and 0x0F}"), StatusCode.CLOSE_NORMAL)
            }
        } catch (e: SocketException) {
            connectionState = ConnectionState.DISCONNECTED
            if (connectionState == ConnectionState.CONNECTED) {
                webSocketClosedEvent?.let {
                    it(
                            WebSocketClosedEvent(
                                    false,
                                    StatusCode.CLOSE_ABNORMAL.code,
                                    "TCP connection was abruptly ended"
                            )
                    )
                }
            } else {
                /**
                 *  We can safely assume if the code got to this point, and the client isn't in the CONNECTED state,
                 *  that it's in the DISCONNECTING state
                 *  Now that we've reached this, let's close the connection cleanly.
                 */
                webSocketClosedEvent?.let {
                    it(
                            WebSocketClosedEvent(
                                    true,
                                    closeStatusCode!!,
                                    "Connection closed by ${if (closedByClient) "client" else "server"}."
                            )
                    )
                }
            }
            return
        }
    }

    private fun handleTextFrame(payloadData: ByteArray)
    {
        messageReceivedEvent?.let { it(MessageReceivedEvent(String(payloadData), payloadData)) }
    }

    @ExperimentalUnsignedTypes
    private fun handleCloseFrame(payloadData: ByteArray)
    {
        val closeCode = ((payloadData[0].toInt() and 0xff) shl 8) or (payloadData[1].toInt() and 0xff)
        closeStatusCode = closeCode
        // TODO: read reason
        close(closeCode, closedByClient=false)
    }

    @ExperimentalUnsignedTypes
    private fun handlePingFrame(payloadData: ByteArray)
    {
        val outputStream = ByteArrayOutputStream()
        payloadData.forEach {
            outputStream.write(byteArrayOf(it))
        }
        write(createDataFrame(Opcode.PONG, 1, outputStream.toByteArray()))
    }

    private fun handlePongFrame(payloadData: ByteArray)
    {
        println("PONG")
    }

    private fun parseHandshakeResponse(input: DataInputStream)
    {
        val line = input.readLine()
        if (line == "" || line == null) {
            connectionState = ConnectionState.CONNECTED
            connectedEvent?.let { it() }
            return
        }
        printVerbose(line)
        if (line.split(" ")[0] == "HTTP/1.1")
        {
            if (line.split(" ")[1] != "101")
            {
                throw closeAndReturnException(
                        InvalidHTTPResponse("Invalid HTTP response: ${line.split(" ")[1]}")
                )
            }
        } else {
            val headerName = line.split(": ")[0]
            val headerValue = line.split(": ")[1]
            when (headerName)
            {
                "Sec-WebSocket-Accept" -> {
                    verifyWebSocketAcceptHeader(headerValue)
                    printVerbose("Handshake complete. Washing hands..")
                    printVerbose("Washing hands complete.")
                }
            }
        }
    }

    private fun handleContinuationFrame(FIN: Boolean, payloadData: ByteArray)
    {
        frameDataFragments.write(payloadData)
        if (FIN)
        {
            val frameDataFragmentsByteArray = frameDataFragments.toByteArray()
            var message = ""
            frameDataFragmentsByteArray.forEach { byte ->
                message += byte.toChar()
            }
            messageReceivedEvent?.let { it(MessageReceivedEvent(message, frameDataFragmentsByteArray)) }
            frameDataFragments.close()
        }
    }

    private fun verifyWebSocketAcceptHeader(value: String?)
    {
        if (value == null)
            throw closeAndReturnException(
                    ConnectionRejectedException("Sec-WebSocket-Accept response header is missing.")
            )
        if (value != generateSecWebSocketAcceptHeader())
            throw closeAndReturnException(
                    ConnectionRejectedException("Sec-WebSocket-Accept response header value does not match the expected value.")
            )
    }

    private fun closeAndReturnException(exception: Exception, code: StatusCode? = null): Exception {
        if (connectionState == ConnectionState.CONNECTED) close(code!!)
        else client!!.close()
        connectionState = ConnectionState.DISCONNECTED
        return exception
    }

    private fun generateSecWebSocketAcceptHeader(): String
    {
        val md = MessageDigest.getInstance("SHA-1")
        // Concatenate Sec-WebSocket-Key to Globally Unique Identifier, both as string
        val concatenated = secWebSocketKey + globallyUniqueIdentifier
        // Convert concatenated string object to ByteArray
        val concatenatedBytes = concatenated.toByteArray()
        // SHA-1 digest the byte array
        val digested = md.digest(concatenatedBytes)
        // Encode digested ByteArray as Base64
        val base64Encoded = Base64.getEncoder().encode(digested)
        // Convert to String and return.
        return String(base64Encoded)
    }

    private fun write(byteArray: ByteArray)
    {
        client!!.getOutputStream().write(byteArray)
    }

    @ExperimentalUnsignedTypes
    fun send(msg: String)
    {
        if (connectionState != ConnectionState.CONNECTED)
        {
            throw ConnectionClosedException("Cannot send message, connection is closed.")
        }
        val dataFrame = createDataFrame(Opcode.TEXT_FRAME, 1, msg.toByteArray())
        client!!.getOutputStream().write(
                dataFrame
        )
    }

    @ExperimentalUnsignedTypes
    fun close(code: Int = StatusCode.CLOSE_NORMAL.code, reason: String = "", closedByClient: Boolean = true)
    {
        if (connectionState == ConnectionState.DISCONNECTED) return
        if (!client!!.isClosed) {
            client!!.getOutputStream().write(createCloseFrame(code, reason.toByteArray()))
            client!!.close()
        }
        this.closedByClient = closedByClient
        closeStatusCode = code
        connectionState = if (client!!.isClosed) ConnectionState.DISCONNECTED else ConnectionState.DISCONNECTING
        if (client!!.isClosed)
        {
            webSocketClosedEvent?.let {
                it (
                        WebSocketClosedEvent(
                                true,
                                closeStatusCode!!,
                                "Connection closed by ${if (closedByClient) "client" else "server"}."
                        )
                )
            }
        }
    }

    @ExperimentalUnsignedTypes
    fun close(code: StatusCode = StatusCode.CLOSE_NORMAL, reason: String = "", closedByClient: Boolean = true)
    {
        close(code.code, reason, closedByClient)
    }

}