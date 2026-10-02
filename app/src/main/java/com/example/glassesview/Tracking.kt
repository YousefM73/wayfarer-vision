package com.example.glassesview

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import android.util.Base64
import com.meta.wearable.dat.motion.types.MotionSample
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt
import org.json.JSONException
import org.json.JSONObject

/** Head angles in degrees. Without a fused orientation only tilt is known, so [hasYaw] is false. */
data class HeadAngles(val yaw: Float, val pitch: Float, val roll: Float, val hasYaw: Boolean)

/**
 * Position in metres in the MultiSet map's frame (right-handed, Y up), with the match confidence
 * and which way the camera faced there.
 */
data class MapPose(
    val x: Float,
    val y: Float,
    val z: Float,
    val confidence: Float?,
    val facing: HeadAngles?,
)

data class TrackingState(
    val head: HeadAngles? = null,
    val headNote: String? = null, // why there are no head angles
    val pose: MapPose? = null,
    val locationNote: String? = null, // why there's no (new) position
)

/**
 * What MultiSet needs to place a frame, an API credential and the map to look in, and how often
 * to ask. Every query counts against the MultiSet plan, so the pace is the user's to choose.
 */
data class MultiSetConfig(
    val clientId: String = "",
    val clientSecret: String = "",
    val mapCode: String = "", // MAP_..., or MSET_... for a set of maps
    val everySeconds: Int = 5,
) {
  companion object {
    // A query takes about a second, so 1 means one straight after another.
    val INTERVALS = listOf(1, 2, 5, 10)
  }

  /** Whether everything a position query needs has been filled in. */
  val complete: Boolean
    get() = clientId.isNotBlank() && clientSecret.isNotBlank() && mapCode.isNotBlank()
}

private fun degrees(radians: Float) = Math.toDegrees(radians.toDouble()).toFloat()

/**
 * Head angles from a motion sample: from the glasses' fused orientation when they send one,
 * otherwise tilt only, from the direction of gravity. Ray-Ban Meta has no magnetometer here, so
 * yaw is relative to where tracking started, not a compass heading.
 */
fun headAngles(sample: MotionSample): HeadAngles? {
  sample.orientation?.let { q ->
    val roll = atan2(2 * (q.w * q.x + q.y * q.z), 1 - 2 * (q.x * q.x + q.y * q.y))
    val pitch = asin((2 * (q.w * q.y - q.z * q.x)).coerceIn(-1f, 1f))
    val yaw = atan2(2 * (q.w * q.z + q.x * q.y), 1 - 2 * (q.y * q.y + q.z * q.z))
    return HeadAngles(degrees(yaw), degrees(pitch), degrees(roll), hasYaw = true)
  }
  val a = sample.accelerometer ?: return null
  val pitch = atan2(-a.x, sqrt(a.y * a.y + a.z * a.z))
  val roll = atan2(a.y, a.z)
  return HeadAngles(0f, degrees(pitch), degrees(roll), hasYaw = false)
}

/**
 * Which way a camera faces in a right-handed, Y-up map, from its rotation there (a quaternion).
 * Unrotated, the camera looks along -Z with +X to its right. Yaw is degrees turned to the right
 * of -Z, pitch is degrees looking up, and roll is degrees tilted toward the right shoulder.
 */
fun facing(x: Float, y: Float, z: Float, w: Float): HeadAngles {
  // The camera's forward (-Z) axis in the map, and the heights of its right and up axes.
  val forwardX = -2 * (x * z + w * y)
  val forwardY = -2 * (y * z - w * x)
  val forwardZ = -(1 - 2 * (x * x + y * y))
  val rightY = 2 * (x * y + w * z)
  val upY = 1 - 2 * (x * x + z * z)
  val yaw = atan2(forwardX, -forwardZ)
  val pitch = asin(forwardY.coerceIn(-1f, 1f))
  val roll = atan2(-rightY, upY)
  return HeadAngles(degrees(yaw), degrees(pitch), degrees(roll), hasYaw = true)
}

/** JPEG-encodes a decoded I420 frame (Y plane, then U, then V at quarter size). */
fun i420ToJpeg(i420: ByteArray, width: Int, height: Int, quality: Int = 80): ByteArray {
  // YuvImage takes NV21: the Y plane, then V and U interleaved.
  val luma = width * height
  val chroma = luma / 4
  val nv21 = ByteArray(luma + 2 * chroma)
  System.arraycopy(i420, 0, nv21, 0, luma)
  for (i in 0 until chroma) {
    nv21[luma + 2 * i] = i420[luma + chroma + i]
    nv21[luma + 2 * i + 1] = i420[luma + i]
  }
  val out = ByteArrayOutputStream()
  YuvImage(nv21, ImageFormat.NV21, width, height, null)
      .compressToJpeg(Rect(0, 0, width, height), quality, out)
  return out.toByteArray()
}

/**
 * Minimal client for MultiSet's visual positioning REST API: sends one camera frame with its
 * intrinsics and gets back the camera's pose inside a map scanned beforehand.
 * See https://docs.multiset.ai (Authentication, Map Query). All calls block; use an IO thread.
 */
class MultiSetClient(private val config: MultiSetConfig) {
  sealed interface Fix {
    data class Found(val pose: MapPose) : Fix

    data object NotFound : Fix

    data class Failed(val reason: String) : Fix
  }

  companion object {
    private const val TOKEN_URL = "https://api.multiset.ai/v1/m2m/token"
    private const val QUERY_URL = "https://api.multiset.ai/v1/vps/map/query-form"
    private const val TOKEN_LIFETIME_MS = 25 * 60_000L // tokens last 30 minutes

    // Ray-Ban Meta camera intrinsics at the 504x896 stream size, as calibrated in MultiSet's
    // wearable sample. The other stream sizes are the same 9:16 view, so they scale with width.
    private const val REF_WIDTH = 504f
    private const val REF_FX = 525.5f
    private const val REF_FY = 526.3f
  }

  private var token: String? = null
  private var tokenFetchedMs = 0L

  fun locate(jpeg: ByteArray, width: Int, height: Int): Fix =
      try {
        var response = query(bearer(), jpeg, width, height)
        if (response.first == HttpURLConnection.HTTP_UNAUTHORIZED) {
          token = null // expired early or revoked: sign in again once
          response = query(bearer(), jpeg, width, height)
        }
        val (status, body) = response
        when {
          status !in 200..299 -> Fix.Failed(refusal(status, body))
          else -> parse(JSONObject(body))
        }
      } catch (e: IOException) {
        Fix.Failed(e.message ?: "network error")
      } catch (e: JSONException) {
        Fix.Failed("unexpected reply")
      }

  /** Why MultiSet turned a query down: its own words when the reply has any, else the status. */
  private fun refusal(status: Int, body: String): String {
    val said =
        try {
          JSONObject(body).let { it.optString("message").ifEmpty { it.optString("error") } }
        } catch (_: JSONException) {
          body
        }
    val detail = said.trim().take(160)
    return if (detail.isEmpty()) "HTTP $status" else "$detail (HTTP $status)"
  }

  private fun parse(json: JSONObject): Fix {
    val position = json.optJSONObject("position")
    if (!json.optBoolean("poseFound", false) || position == null) return Fix.NotFound
    fun JSONObject.float(name: String, fallback: Double = 0.0) = optDouble(name, fallback).toFloat()
    return Fix.Found(
        MapPose(
            x = position.float("x"),
            y = position.float("y"),
            z = position.float("z"),
            confidence = if (json.has("confidence")) json.float("confidence") else null,
            facing =
                json.optJSONObject("rotation")?.let {
                  facing(it.float("x"), it.float("y"), it.float("z"), it.float("w", 1.0))
                },
        ))
  }

  private fun bearer(): String {
    token?.let { if (SystemClock.elapsedRealtime() - tokenFetchedMs < TOKEN_LIFETIME_MS) return it }
    val connection = URL(TOKEN_URL).openConnection() as HttpURLConnection
    try {
      connection.requestMethod = "POST"
      connection.connectTimeout = 10_000
      connection.readTimeout = 15_000
      val basic =
          Base64.encodeToString(
              "${config.clientId}:${config.clientSecret}".toByteArray(), Base64.NO_WRAP)
      connection.setRequestProperty("Authorization", "Basic $basic")
      connection.doOutput = true
      connection.outputStream.use { it.write(ByteArray(0)) }
      val status = connection.responseCode
      if (status !in 200..299) throw IOException("MultiSet sign-in failed (HTTP $status)")
      val body = connection.inputStream.bufferedReader().use { it.readText() }
      val fresh = JSONObject(body).optString("token")
      if (fresh.isEmpty()) throw IOException("MultiSet sign-in returned no token")
      token = fresh
      tokenFetchedMs = SystemClock.elapsedRealtime()
      return fresh
    } finally {
      connection.disconnect()
    }
  }

  /** Multipart form upload; returns the HTTP status and body. */
  private fun query(bearer: String, jpeg: ByteArray, width: Int, height: Int): Pair<Int, String> {
    val scale = width / REF_WIDTH
    val fields =
        mapOf(
            // A map set joins several maps into one frame and has its own kind of code.
            (if (config.mapCode.startsWith("MSET")) "mapSetCode" else "mapCode") to config.mapCode,
            "isRightHanded" to "true",
            "width" to width.toString(),
            "height" to height.toString(),
            "fx" to (REF_FX * scale).toString(),
            "fy" to (REF_FY * scale).toString(),
            "px" to (width / 2f).toString(),
            "py" to (height / 2f).toString(),
        )
    val boundary = "----glassesview${System.nanoTime()}"
    val body = ByteArrayOutputStream()
    fun text(s: String) = body.write(s.toByteArray())
    for ((name, value) in fields) {
      text("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
    }
    text(
        "--$boundary\r\nContent-Disposition: form-data; name=\"queryImage\"; " +
            "filename=\"query.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n")
    body.write(jpeg)
    text("\r\n--$boundary--\r\n")

    val connection = URL(QUERY_URL).openConnection() as HttpURLConnection
    try {
      connection.requestMethod = "POST"
      connection.connectTimeout = 10_000
      connection.readTimeout = 20_000
      connection.setRequestProperty("Authorization", "Bearer $bearer")
      connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
      connection.doOutput = true
      connection.setFixedLengthStreamingMode(body.size())
      connection.outputStream.use { body.writeTo(it) }
      val status = connection.responseCode
      val stream = if (status in 200..299) connection.inputStream else connection.errorStream
      return status to (stream?.bufferedReader()?.use { it.readText() } ?: "")
    } finally {
      connection.disconnect()
    }
  }
}
