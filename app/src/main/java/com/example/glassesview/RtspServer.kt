package com.example.glassesview

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A minimal RTSP server for one player at a time. It serves the glasses' HEVC stream as RTP
 * (RFC 7798) interleaved on the RTSP connection itself, i.e. RTP over TCP, which also works
 * through `adb forward`. A player that asks for UDP gets 461 and retries over TCP, as VLC,
 * ffmpeg and OBS all do. Parameter sets travel in-band at the start of every stream, so the
 * session description doesn't need them.
 *
 * [send] queues one access unit (Annex-B) for the player; frames are dropped while nobody is
 * playing. Every frame depends on the ones before it, so nothing is ever dropped from the middle
 * of the stream: if the player falls behind, the queue is emptied and sending resumes at the
 * next keyframe.
 */
class RtspServer(private val port: Int, private val scope: CoroutineScope) {

  companion object {
    private const val TAG = "RtspServer"
    private const val QUEUE = 60 // frames, ~2 s at 30 fps
    private const val PAYLOAD_TYPE = 96
    private const val MAX_PAYLOAD = 1400 // bytes of RTP payload per packet
    private const val FU_TYPE = 49 // HEVC fragmentation unit

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

  private class Frame(val data: ByteArray, val ptsMs: Long)

  private class Request(val method: String, val uri: String, val headers: Map<String, String>)

  private val _client = MutableStateFlow<String?>(null)

  /** Address of the player that is playing, or null while waiting for one. */
  val client: StateFlow<String?> = _client.asStateFlow()

  private val frames = Channel<Frame>(QUEUE)
  @Volatile private var awaitingKeyframe = true // only ever start (or restart) on a keyframe
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
          Log.i(TAG, "Listening on port $port")
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

  fun send(frame: ByteArray, ptsMs: Long) {
    if (_client.value == null) return
    if (awaitingKeyframe) {
      if (!isKeyframe(frame)) return
      awaitingKeyframe = false
    }
    if (frames.trySend(Frame(frame, ptsMs)).isFailure) {
      Log.w(TAG, "Player is behind; skipping to the next keyframe")
      while (frames.tryReceive().isSuccess) Unit
      awaitingKeyframe = true
    }
  }

  /** Whether an Annex-B access unit's picture is an IRAP (BLA, IDR or CRA: types 16 to 21). */
  private fun isKeyframe(data: ByteArray): Boolean {
    var i = 0
    while (i + 3 < data.size) {
      if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
        val type = (data[i + 3].toInt() shr 1) and 0x3F
        if (type < 32) return type in 16..21 // the first picture NAL decides
        i += 3
      } else {
        i++
      }
    }
    return false
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

  /** Handles one player's RTSP conversation until it tears down or disconnects. */
  private fun serve(socket: Socket) {
    clientSocket = socket
    val address = socket.inetAddress.hostAddress
    val session = java.lang.Long.toHexString(System.nanoTime() and 0xFFFFFFFFL)
    var rtpChannel = 0
    var sender: Job? = null
    try {
      socket.tcpNoDelay = true
      val input = BufferedInputStream(socket.getInputStream())
      val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
      while (true) {
        val request = readRequest(input) ?: break
        val cseq = request.headers["cseq"] ?: "0"
        when (request.method) {
          "OPTIONS" ->
              respond(
                  out,
                  cseq,
                  "200 OK",
                  "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER")
          "DESCRIBE" ->
              respond(
                  out,
                  cseq,
                  "200 OK",
                  "Content-Base: ${request.uri.trimEnd('/')}/",
                  "Content-Type: application/sdp",
                  body = sessionDescription())
          "SETUP" -> {
            val transport = request.headers["transport"] ?: ""
            if (!transport.contains("RTP/AVP/TCP")) {
              // UDP isn't offered; the player retries with RTP over this connection.
              respond(out, cseq, "461 Unsupported Transport")
            } else {
              val channels = Regex("interleaved=(\\d+)-(\\d+)").find(transport)
              rtpChannel = channels?.groupValues?.get(1)?.toInt() ?: 0
              val rtcpChannel = channels?.groupValues?.get(2)?.toInt() ?: (rtpChannel + 1)
              respond(
                  out,
                  cseq,
                  "200 OK",
                  "Transport: RTP/AVP/TCP;unicast;interleaved=$rtpChannel-$rtcpChannel",
                  "Session: $session;timeout=60")
            }
          }
          "PLAY" -> {
            respond(out, cseq, "200 OK", "Session: $session", "Range: npt=0.000-")
            if (sender == null) {
              while (frames.tryReceive().isSuccess) Unit // anything queued is from an old stream
              awaitingKeyframe = true
              Log.i(TAG, "Player playing from $address")
              _client.value = address
              val channel = rtpChannel
              sender =
                  scope.launch(Dispatchers.IO) {
                    val packetizer = Packetizer(out, channel)
                    try {
                      for (frame in frames) packetizer.write(frame)
                    } catch (_: IOException) {
                      // The reader notices the closed connection and cleans up.
                    }
                  }
            }
          }
          "GET_PARAMETER",
          "SET_PARAMETER" -> respond(out, cseq, "200 OK", "Session: $session") // keep-alive
          "TEARDOWN" -> {
            respond(out, cseq, "200 OK", "Session: $session")
            break
          }
          else ->
              respond(
                  out,
                  cseq,
                  "405 Method Not Allowed",
                  "Allow: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER")
        }
      }
    } catch (e: IOException) {
      Log.i(TAG, "Player connection ended: ${e.message}")
    } finally {
      sender?.cancel()
      if (_client.value != null) Log.i(TAG, "Player gone")
      _client.value = null
      clientSocket = null
      try {
        socket.close()
      } catch (_: IOException) {}
    }
  }

  private fun sessionDescription(): String =
      listOf(
              "v=0",
              "o=- 0 0 IN IP4 127.0.0.1",
              "s=Glasses View",
              "c=IN IP4 0.0.0.0",
              "t=0 0",
              "a=range:npt=0-",
              "m=video 0 RTP/AVP $PAYLOAD_TYPE",
              "a=rtpmap:$PAYLOAD_TYPE H265/90000",
              "a=control:trackID=0",
          )
          .joinToString("") { "$it\r\n" }

  private fun respond(
      out: OutputStream,
      cseq: String,
      status: String,
      vararg headers: String,
      body: String = "",
  ) {
    val bytes = body.toByteArray()
    val head = buildString {
      append("RTSP/1.0 $status\r\nCSeq: $cseq\r\nServer: GlassesView\r\n")
      for (header in headers) append("$header\r\n")
      if (bytes.isNotEmpty()) append("Content-Length: ${bytes.size}\r\n")
      append("\r\n")
    }
    // The RTP sender shares this stream; a reply must not land inside one of its packets.
    synchronized(out) {
      out.write(head.toByteArray())
      out.write(bytes)
      out.flush()
    }
  }

  /** Reads the next RTSP request, skipping the RTCP reports a player interleaves on the socket. */
  private fun readRequest(input: InputStream): Request? {
    while (true) {
      val first = input.read()
      if (first < 0) return null
      if (first == '$'.code) {
        input.read() // channel
        val high = input.read()
        val low = input.read()
        if (low < 0) return null
        var left = (high shl 8) or low
        while (left > 0) {
          val skipped = input.skip(left.toLong())
          if (skipped <= 0) {
            if (input.read() < 0) return null
            left--
          } else {
            left -= skipped.toInt()
          }
        }
        continue
      }
      if (first == '\r'.code || first == '\n'.code) continue

      val requestLine = (first.toChar() + (readLine(input) ?: return null)).split(' ')
      val headers = mutableMapOf<String, String>()
      while (true) {
        val line = readLine(input) ?: return null
        if (line.isEmpty()) break
        val colon = line.indexOf(':')
        if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
      }
      var body = headers["content-length"]?.toIntOrNull() ?: 0
      while (body-- > 0) if (input.read() < 0) return null
      return Request(requestLine[0], requestLine.getOrElse(1) { "" }, headers)
    }
  }

  private fun readLine(input: InputStream): String? {
    val line = StringBuilder()
    while (true) {
      val c = input.read()
      if (c < 0) return null
      if (c == '\n'.code) return line.toString().trimEnd('\r')
      line.append(c.toChar())
    }
  }

  /** Turns Annex-B access units into interleaved RTP packets on the RTSP connection. */
  private class Packetizer(private val out: OutputStream, private val channel: Int) {
    private val ssrc = System.nanoTime().toInt()
    private var sequence = 0
    private val header = ByteArray(16) // 4 bytes of interleaving, 12 of RTP

    fun write(frame: Frame) {
      val data = frame.data
      val timestamp = (frame.ptsMs * 90).toInt() // 90 kHz clock
      val nals = nalUnits(data)
      synchronized(out) {
        for ((index, nal) in nals.withIndex()) {
          val (start, end) = nal
          val lastNal = index == nals.lastIndex
          if (end - start <= MAX_PAYLOAD) {
            packet(timestamp, marker = lastNal, data, start, end - start)
            continue
          }
          // Too big for one packet: fragmentation units. Each carries a 2-byte payload header
          // (the NAL's own, retyped as FU) and a 1-byte FU header with start/end flags and
          // the original NAL type; the NAL's own 2-byte header isn't repeated.
          val type = (data[start].toInt() shr 1) and 0x3F
          val fu = ByteArray(3)
          fu[0] = ((data[start].toInt() and 0x81) or (FU_TYPE shl 1)).toByte()
          fu[1] = data[start + 1]
          var position = start + 2
          while (position < end) {
            val size = min(MAX_PAYLOAD - fu.size, end - position)
            val isFirst = position == start + 2
            val isLast = position + size == end
            fu[2] = ((if (isFirst) 0x80 else 0) or (if (isLast) 0x40 else 0) or type).toByte()
            packet(timestamp, marker = lastNal && isLast, data, position, size, prefix = fu)
            position += size
          }
        }
        out.flush()
      }
    }

    private fun packet(
        timestamp: Int,
        marker: Boolean,
        data: ByteArray,
        offset: Int,
        size: Int,
        prefix: ByteArray? = null,
    ) {
      val length = 12 + (prefix?.size ?: 0) + size
      header[0] = '$'.code.toByte()
      header[1] = channel.toByte()
      header[2] = (length shr 8).toByte()
      header[3] = length.toByte()
      header[4] = 0x80.toByte() // RTP version 2
      header[5] = ((if (marker) 0x80 else 0) or PAYLOAD_TYPE).toByte()
      header[6] = (sequence shr 8).toByte()
      header[7] = sequence.toByte()
      header[8] = (timestamp shr 24).toByte()
      header[9] = (timestamp shr 16).toByte()
      header[10] = (timestamp shr 8).toByte()
      header[11] = timestamp.toByte()
      header[12] = (ssrc shr 24).toByte()
      header[13] = (ssrc shr 16).toByte()
      header[14] = (ssrc shr 8).toByte()
      header[15] = ssrc.toByte()
      sequence = (sequence + 1) and 0xFFFF
      out.write(header)
      if (prefix != null) out.write(prefix)
      out.write(data, offset, size)
    }

    /** NAL units in Annex-B data as (start, end) offsets, without their start codes. */
    private fun nalUnits(data: ByteArray): List<Pair<Int, Int>> {
      val starts = mutableListOf<Int>() // offset just after each 00 00 01
      var i = 0
      while (i + 2 < data.size) {
        if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
          starts += i + 3
          i += 3
        } else {
          i++
        }
      }
      return starts.mapIndexedNotNull { n, start ->
        var end = if (n + 1 < starts.size) starts[n + 1] - 3 else data.size
        // Zeros before the next start code are padding (or its 4-byte form), not NAL data.
        while (end > start && data[end - 1].toInt() == 0) end--
        if (end - start >= 2) start to end else null
      }
    }
  }
}
