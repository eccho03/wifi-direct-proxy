package com.example.wifi_direct_proxy

import android.util.Log
import java.net.*
import java.util.concurrent.CountDownLatch

class RequestHandler(private val socket: Socket) : Runnable {
    override fun run() {
        val startTime = System.nanoTime()
        val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        var line = reader.readLine()
        Log.d(TAG, "start-line: $line")

        val matcher = connectPattern.matcher(line)
        if (matcher.matches()) {
            val writer = socket.getOutputStream().bufferedWriter(Charsets.ISO_8859_1)
            val host = matcher.group(1)
            val port = matcher.group(2)
            val version = matcher.group(3)

            if (port?.toIntOrNull() == null) {
                socket.close()
                return
            }

            while (true) {
                line = reader.readLine()
                if (!line.isNullOrEmpty()) {
                    Log.d(TAG, "header: $line")
                } else {
                    break
                }
            }

            val forwardSocket: Socket
            val tunnelStartTime = System.nanoTime()

            try {
                val proxyHost = System.getProperty("http.proxyHost")
                val proxyPort = System.getProperty("http.proxyPort")
                forwardSocket = if (useSystemProxy && proxyHost != null && proxyPort != null) {
                    Log.d(TAG, "system http proxy: $proxyHost:$proxyPort")
                    val proxyAddress = InetSocketAddress(proxyHost, proxyPort.toInt())
                    Socket(Proxy(Proxy.Type.SOCKS, proxyAddress)).apply {
                        connect(InetSocketAddress(host, port.toInt()))
                    }
                } else {
                    Socket(host, port.toInt())
                }

                forwardSocket.soTimeout = 5000 // 타임아웃
                socket.soTimeout = 5000

            } catch (e: Exception) {
                Log.w(TAG, e)
                writer.write("HTTP/$version 502 Bad Gateway\r\n")
                writer.write("\r\n")
                writer.flush()
                Log.d(TAG, "HTTP/$version 502 Bad Gateway")
                socket.close()
                return
            }

            writer.write("HTTP/$version 200 Connection established\r\n")
            writer.write("\r\n")
            writer.flush()

            val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
            Log.d(TAG, "✅ CONNECT 처리 완료까지 소요 시간: $elapsedMs ms")

            val latch = CountDownLatch(1)
            executorService.submit {
                try {
                    val totalBytes = forwardSocket.getInputStream().copyTo(socket.getOutputStream())
                    Log.d(TAG, "✅ Copied $totalBytes bytes from forward socket")

                } catch (e: Exception) {
                    Log.w(TAG, e)
                } finally {
                    try {
                        forwardSocket.shutdownInput()
                        socket.shutdownOutput()
                    } catch (_: Exception) {}
                    latch.countDown()
                }
            }

            try {
                val totalBytes = socket.getInputStream().copyTo(forwardSocket.getOutputStream())
                Log.d(TAG, "✅ Copied $totalBytes bytes from forward socket")
            } catch (e: Exception) {
                Log.w(TAG, e)
            } finally {
                try {
                    socket.shutdownInput()
                    forwardSocket.shutdownOutput()
                } catch (_: Exception) {}
            }

            latch.await()
            val tunnelEndTime = System.nanoTime()
            val tunnelElapsed = (tunnelEndTime - tunnelStartTime) / 1_000_000
            Log.d(TAG, "✅ HTTPS 터널링 전체 전송 소요 시간: $tunnelElapsed ms")

            forwardSocket.close()
        }
        socket.close()
    }
}
