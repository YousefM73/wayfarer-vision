package com.example.glassesview

import android.util.Log
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Serves the glasses' compressed HEVC stream to one player at a time over plain TCP, as raw
 * Annex-B bytes, so `ffplay -f hevc -i tcp://host:port` plays it. [send] queues a frame for the
 * connected player; frames are dropped while nobody is connected, and if the player falls behind
 * the oldest queued frames are dropped.
 */
class StreamServer(private val port: Int, private val scope: CoroutineScope) {

  companion object {
    private const val TAG = "StreamServer"
    private const val QUEUE = 60 // frames, ~2 s at 30 fps

    /** The phone's IPv4 address on the local network (Wi-Fi first), if it has one. */
    fun localAddress(): String? =
        NetworkInterface.getNetworkInterfaces()
            ?.toList()
            ?.filter { it.isUp && !it.isLoopback }
            ?.sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            ?.flatMap { it.inetAddresses.toList() }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
  }

  private val _client = MutableStateFlow<String?>(null)

  /** Address of the connected player, or null while waiting for one. */
  val client: StateFlow<String?> = _client.asStateFlow()

  private val frames = Channel<ByteArray>(QUEUE, BufferOverflow.DROP_OLDEST)
  private var serverSocket: ServerSocket? = null
  @Volatile private var clientSocket: Socket? = null
  private var job: Job? = null

  fun start() {
    job =
        scope.launch(Dispatchers.IO) {
          val server =
              try {
                ServerSocket(port).also { it.reuseAddress = true }
              } catch (e: IOException) {
                Log.e(TAG, "Couldn't listen on port $port", e)
                return@launch
              }
          serverSocket = server
          while (isActive) {
            val socket =
                try {
                  server.accept()
                } catch (e: IOException) {
                  break // the server socket was closed
                }
            serve(socket)
          }
        }
  }

  private suspend fun serve(socket: Socket) {
    clientSocket = socket
    while (frames.tryReceive().isSuccess) Unit // anything queued was for the previous player
    _client.value = socket.inetAddress.hostAddress
    Log.i(TAG, "Player connected from ${_client.value}")
    try {
      socket.tcpNoDelay = true
      val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
      while (true) {
        out.write(frames.receive())
        out.flush()
      }
    } catch (e: IOException) {
      Log.i(TAG, "Player disconnected: ${e.message}")
    } finally {
      _client.value = null
      clientSocket = null
      try {
        socket.close()
      } catch (_: IOException) {}
    }
  }

  fun send(frame: ByteArray) {
    if (_client.value != null) frames.trySend(frame)
  }

  fun stop() {
    job?.cancel()
    job = null
    try {
      serverSocket?.close()
    } catch (_: IOException) {}
    try {
      clientSocket?.close()
    } catch (_: IOException) {}
    serverSocket = null
    _client.value = null
  }
}
