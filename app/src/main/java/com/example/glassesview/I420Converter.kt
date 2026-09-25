package com.example.glassesview

import android.graphics.Bitmap
import java.nio.ByteBuffer

/**
 * Converts the SDK's decoded I420 frames (Y plane, then U, then V at quarter size) to ARGB
 * bitmaps. Output bitmaps rotate through a small pool so the one on screen isn't overwritten
 * while it's being drawn.
 */
class I420Converter {

  private var yuv = ByteArray(0)
  private var pixels = IntArray(0)
  private val pool = arrayOfNulls<Bitmap>(3)
  private var next = 0

  fun convert(buffer: ByteBuffer, width: Int, height: Int): Bitmap? {
    if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) return null
    val lumaSize = width * height
    val chromaSize = lumaSize / 4
    val needed = lumaSize + 2 * chromaSize

    val src = buffer.duplicate()
    if (src.remaining() < needed) return null
    if (yuv.size != needed) yuv = ByteArray(needed)
    if (pixels.size != lumaSize) pixels = IntArray(lumaSize)
    src.get(yuv, 0, needed)

    val uStart = lumaSize
    val vStart = lumaSize + chromaSize
    val chromaWidth = width / 2

    // BT.601 limited range, fixed point (x1024).
    for (row in 0 until height) {
      val yRow = row * width
      val cRow = (row shr 1) * chromaWidth
      for (col in 0 until width) {
        val y = ((yuv[yRow + col].toInt() and 0xFF) - 16).coerceAtLeast(0) * 1192
        val c = cRow + (col shr 1)
        val u = (yuv[uStart + c].toInt() and 0xFF) - 128
        val v = (yuv[vStart + c].toInt() and 0xFF) - 128

        val r = (y + 1634 * v).coerceIn(0, 262143) shr 10
        val g = (y - 833 * v - 400 * u).coerceIn(0, 262143) shr 10
        val b = (y + 2066 * u).coerceIn(0, 262143) shr 10
        pixels[yRow + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
      }
    }

    val bitmap =
        pool[next]?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { pool[next] = it }
    next = (next + 1) % pool.size
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap
  }
}
