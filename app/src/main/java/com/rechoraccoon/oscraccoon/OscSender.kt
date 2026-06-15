package com.rechoraccoon.oscraccoon

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

object OscSender {
    private const val PORT = 9000

    fun sendChatboxMessage(message: String, immediate: Boolean = true) {
        Thread {
            try {
                val packet = buildOscMessage("/chatbox/input", message, immediate)
                val socket = DatagramSocket()
                socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName("127.0.0.1"), PORT))
                socket.close()
            } catch (e: Exception) { e.printStackTrace() }
        }.start()
    }

    fun clearChatbox() = sendChatboxMessage("", true)

    private fun buildOscMessage(address: String, vararg args: Any): ByteArray {
        val buf = ByteBuffer.allocate(1024)
        writeOscString(buf, address)
        val tag = StringBuilder(",")
        for (a in args) when (a) { is String -> tag.append('s'); is Boolean -> tag.append(if (a) 'T' else 'F') }
        writeOscString(buf, tag.toString())
        for (a in args) when (a) { is String -> writeOscString(buf, a); is Boolean -> {} }
        val r = ByteArray(buf.position()); buf.rewind(); buf.get(r); return r
    }

    private fun writeOscString(buf: ByteBuffer, s: String) {
        val b = s.toByteArray(StandardCharsets.UTF_8)
        buf.put(b); buf.put(0)
        val pad = (4 - ((b.size + 1) % 4)) % 4
        repeat(pad) { buf.put(0) }
    }
}
