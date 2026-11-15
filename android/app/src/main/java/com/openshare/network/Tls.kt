package com.openshare.network

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

object Tls {
    fun clientSocket(host: String, port: Int, timeoutMs: Int = 15000): Socket {
        val s = Socket()
        configure(s)
        s.connect(InetSocketAddress(host, port), timeoutMs)
        return s
    }

    fun serverSocket(port: Int): ServerSocket {
        val s = ServerSocket(port)
        s.reuseAddress = true
        return s
    }

    fun configure(s: Socket) {
        try { s.tcpNoDelay = true } catch (_: Exception) {}
        try { s.keepAlive = true } catch (_: Exception) {}
        try { s.soTimeout = 0 } catch (_: Exception) {}
        try { s.sendBufferSize = 1024 * 1024 } catch (_: Exception) {}
        try { s.receiveBufferSize = 1024 * 1024 } catch (_: Exception) {}
    }
}
