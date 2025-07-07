package com.example.wifi_direct_proxy

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.*

class RequestHandler(private val socket: Socket) : Runnable {
    override fun run() {
        val startTime = System.nanoTime()
        val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        var line = reader.readLine()
        Log.d(TAG, "start-line: $line")

        val matcher = connectPattern.matcher(line)
        if (!matcher.matches()) {
            socket.close()
            return
        }

        val writer = socket.getOutputStream().bufferedWriter(Charsets.ISO_8859_1)
        val host = matcher.group(1)
        val port = matcher.group(2)?.toIntOrNull()
        val version = matcher.group(3)

        if (port == null) {
            socket.close()
            return
        }

        while (reader.readLine()?.isNotEmpty() == true) {} // skip headers

        val forwardSocket: Socket
        val tunnelStartTime = System.nanoTime()

        try {
            forwardSocket = Socket()
            forwardSocket.soTimeout = 3000 // ⏱ set timeout
            forwardSocket.connect(InetSocketAddress(host, port), 3000)
        } catch (e: Exception) {
            writer.write("HTTP/$version 502 Bad Gateway\r\n\r\n")
            writer.flush()
            socket.close()
            return
        }

        socket.soTimeout = 3000 // ⏱ timeout for both sockets

        writer.write("HTTP/$version 200 Connection established\r\n\r\n")
        writer.flush()

        val connectElapsed = (System.nanoTime() - startTime) / 1_000_000
        Log.d(TAG, "✅ CONNECT 처리 완료까지 소요 시간: $connectElapsed ms")

        val inToOut = Thread {
            try {
                copyStream(forwardSocket.getInputStream(), socket.getOutputStream())
            } catch (_: Exception) {}
        }

        inToOut.start()

        try {
            copyStream(socket.getInputStream(), forwardSocket.getOutputStream())
        } catch (_: Exception) {}

        try {
            forwardSocket.shutdownOutput()
            socket.shutdownInput()
        } catch (_: Exception) {}

        inToOut.join()

        try {
            forwardSocket.close()
            socket.close()
        } catch (_: Exception) {}

        val tunnelElapsed = (System.nanoTime() - tunnelStartTime) / 1_000_000
        Log.d(TAG, "✅ HTTPS 터널링 전체 전송 소요 시간: $tunnelElapsed ms")
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val len = try {
                input.read(buffer)
            } catch (e: SocketTimeoutException) {
                break
            }

            if (len == -1) break
            output.write(buffer, 0, len)
            output.flush()
            total += len
        }
        Log.d(TAG, "✅ Copied $total bytes from stream")
    }
}

