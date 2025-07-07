package com.example.wifi_direct_proxy

import android.util.Log
import java.net.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.regex.Pattern

const val TAG = "com.example.httpproxyserver.HttpProxy"

val executorService: ExecutorService = Executors.newCachedThreadPool()
val connectPattern: Pattern =
    Pattern.compile("CONNECT (.+):(.+) HTTP/(1\\.[01])", Pattern.CASE_INSENSITIVE)
var useSystemProxy = false

class HttpProxy(private val port: Int = 1081) : Runnable {

    var messageListener: ((String) -> Unit)? = null
    var started = false
    private var serverSocket: ServerSocket? = null

    override fun run() {
        started = true
        try {
            serverSocket = ServerSocket(port)
            sendMsg("Http proxy is listening on port: $port")
        } catch (e: Exception) {
            Log.w(TAG, e)
            started = false
            sendMsg("failed to open http proxy on port: $port")
            return
        }

        try {
            while (true) {
                val socket = serverSocket!!.accept()
                executorService.submit(RequestHandler(socket))
            }
        } catch (e: Exception) {
            Log.w(TAG, e)
        } finally {
            serverSocket?.close()
            serverSocket = null
            started = false
            sendMsg("Http proxy is stopped")
        }
    }

    private fun sendMsg(msg: String) {
        Log.d(TAG, msg)
        messageListener?.invoke(msg)
    }

    fun stop() {
        serverSocket?.close()
        serverSocket = null
    }

    companion object {
        fun start(port: Int): HttpProxy {
            val httpProxy = HttpProxy(port)
            executorService.submit(httpProxy)
            return httpProxy
        }
    }
}