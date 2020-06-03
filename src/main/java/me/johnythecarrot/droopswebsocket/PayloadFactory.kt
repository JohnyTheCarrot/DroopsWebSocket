package me.johnythecarrot.droopswebsocket

import Opcode
import me.johnythecarrot.droopswebsocket.enums.StatusCode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.*
import kotlin.experimental.xor

val secWebSocketKeyBytes = createSecWebSocketKeyBytes()
val secWebSocketKey = String(secWebSocketKeyBytes!!)

fun createSecWebSocketKeyBytes(): ByteArray? {
    val bytes = ByteArray(16)
    SecureRandom.getInstanceStrong().nextBytes(bytes)
    return Base64.getEncoder().encode(bytes)!!
}

fun createOpeningHandshake(host: String, path: String = "/", port: Int = 80, protocolVersion: Int = 13): String
{
    var handshake = ""
    handshake += "GET $path HTTP/1.1\r\n"
    handshake += "Host: $host:$port\r\n"
    handshake += "Upgrade: websocket\r\n"
    handshake += "Connection: Keep-Alive, Upgrade\r\n"
    handshake += "Accept: */*\r\n"
    handshake += "Sec-WebSocket-Version: $protocolVersion\r\n"
    handshake += "Sec-WebSocket-Key: $secWebSocketKey\r\n\r\n"
    return handshake
}

fun bytesToLong(bytes: ByteArray): Long {
    val buffer = ByteBuffer.allocate(java.lang.Long.BYTES)
    buffer.put(bytes)
    buffer.flip() //need flip
    return buffer.long
}

fun longToBytes(value: Long): ByteArray {
    val buffer = ByteBuffer.allocate(8)
    buffer.order(ByteOrder.BIG_ENDIAN)
    buffer.putLong(value)
    buffer.flip()
    return buffer.array()
}

fun shortToBytes(value: Short): ByteArray {
    val buffer = ByteBuffer.allocate(2)
    buffer.order(ByteOrder.BIG_ENDIAN)
    buffer.putShort(value)
    buffer.flip()
    return buffer.array()
}

fun byteToBigEndian(value: Byte): Byte {
    val buffer = ByteBuffer.allocate(1)
    buffer.order(ByteOrder.BIG_ENDIAN)
    buffer.put(value)
    buffer.flip()
    return buffer.array()[0]
}

@ExperimentalUnsignedTypes
fun genPayloadLengthBytes(length: Int): ByteArray
{
    return if (length <= 125) {
        byteArrayOf(byteToBigEndian(length.toByte()))
    } else if (length in 126..65535) {
        byteArrayOf(126.toByte(), shortToBytes(length.toShort())[0], shortToBytes(length.toShort())[1])
    } else {
        val outputStream = ByteArrayOutputStream()
        outputStream.write(byteArrayOf(127.toByte()))
        outputStream.write(longToBytes(length.toLong()))
        outputStream.toByteArray()
    }
}

fun createMaskedData(mask: ByteArray, msg: ByteArray): ByteArray
{
    // j = i MOD 4
    // transformed-octet-i = original-octet-i XOR masking-key-octet-j
    val outputStream = ByteArrayOutputStream()
    msg.forEachIndexed { index, byte ->
        val maskingKeyOctetJ = mask[index % 4]
        val transformedOctetI = byte xor maskingKeyOctetJ
        outputStream.write(byteArrayOf(transformedOctetI))
    }
    return outputStream.toByteArray()
}

fun Boolean.toInt(): Int
{
    return if (this) 1 else 0
}

fun genFrameByte0(FIN: Boolean, opcode: Opcode): Byte
{
    // (1/0 shl 7) or opcode
    return ((FIN.toInt() shl 7) or opcode.b.toInt()).toByte()
}

@ExperimentalUnsignedTypes
fun getFrameByte1(MASK: Boolean = true, length: Int): Byte
{
    return ((MASK.toInt() shl 7) or genPayloadLengthBytes(length)[0].toInt()).toByte()
}

@ExperimentalUnsignedTypes
fun createPingFrame(content: ByteArray): ByteArray {
    return createDataFrame(Opcode.PING, 1, content)
}

@ExperimentalUnsignedTypes
fun createPongFrame(content: ByteArray): ByteArray {
    return createDataFrame(Opcode.PONG, 1, content)
}

@ExperimentalUnsignedTypes
fun createDataFrame(opcode: Opcode = Opcode.TEXT_FRAME, FIN: Int = 0, content: ByteArray): ByteArray
{
    val bytes = ByteArray(4)
    SecureRandom.getInstanceStrong().nextBytes(bytes)
    val maskedData = createMaskedData(bytes, content)
    val outputStream = ByteArrayOutputStream()
    val byteArray = byteArrayOf(
            genFrameByte0(FIN == 1, opcode),
            getFrameByte1(true, content.size)
    )
    outputStream.write(byteArray)
    if (content.size > 125)
    {
        val payloadLengthPartSize = if (content.size <= 65535) 2 else 8
        outputStream.write(genPayloadLengthBytes(content.size).copyOfRange(1, payloadLengthPartSize+1))
    }
    outputStream.write(bytes)
    outputStream.write(maskedData)
    return outputStream.toByteArray()
}

fun createCloseFrame(code: Int, reason: ByteArray): ByteArray
{
    val bytes = ByteArray(4)
    SecureRandom.getInstanceStrong().nextBytes(bytes)
    val outputStream = ByteArrayOutputStream()
    val closeCode = shortToBytes(code.toShort())
    val toMaskOutputStream = ByteArrayOutputStream()
    toMaskOutputStream.write(byteArrayOf(closeCode[0], closeCode[1]))
    toMaskOutputStream.write(reason)
    val maskedData = createMaskedData(bytes, toMaskOutputStream.toByteArray())
    val byteArray = byteArrayOf(
            genFrameByte0(true, Opcode.CONNECTION_CLOSE),
            getFrameByte1(true, 2 + reason.size)
    )
    outputStream.write(byteArray)
    outputStream.write(bytes)
    outputStream.write(maskedData)
    return outputStream.toByteArray()
}

fun createCloseFrame(code: StatusCode, reason: ByteArray): ByteArray
{
    return createCloseFrame(code.code, reason)
}