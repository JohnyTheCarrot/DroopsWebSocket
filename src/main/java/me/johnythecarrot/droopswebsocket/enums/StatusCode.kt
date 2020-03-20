package me.johnythecarrot.droopswebsocket.enums

enum class StatusCode(val code: Int) {
    CLOSE_NORMAL(1000),
    CLOSE_GOING_AWAY(1001),
    CLOSE_PROTOCOL_ERROR(1002),
    CLOSE_ABNORMAL(1006);

    companion object {
        fun fromInt(value: Int) = values().first { it.code == value }
    }
}