# DroopsWebSocket
A Kotlin implementation of the WebSocket protocol.

# Usage

```kotlin
lateinit var websocket: WebSocket

fun main()
{
  /**
  * the host parameter does NOT accept URI's
  * if you want a secure connection, set the secure boolean to true
  **/
  websocket = WebSocket(false, "echo.websocket.org", "/", 80)
  websocket.connectedEvent = ::connectedEventHandler
  websocket.messageReceivedEvent = ::onMessageReceived
  websocket.webSocketClosedEvent = ::disconnectedEventHandler
  websocket.connect()
  
  websocket.send("Hello, World!")
}

fun connectedEventHandler()
{
  println("Connected!")
}

fun disconnectedEventHandler(event: WebSocketClosedEvent)
{
  println("Websocket closed.")
  println("Code: ${event.statusCode}")
  println("Clean: ${event.clean}")
  println("Reason: ${event.reason}")
}

private fun onMessageReceived(event: MessageReceivedEvent)
{
  println(event.message)
}
```
