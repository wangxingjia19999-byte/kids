package com.kid4rehab.pose

import java.nio.ByteBuffer

/**
 * 一个 YUV 平面的内存描述（buffer + row/pixel stride）。
 * 按 Image.Plane.getBuffer() 契约，buffer 的 position 指向本平面数据起始
 * （半平面排布下 V 与 U 共享一段内存、position 相差 1 字节）。
 */
data class YuvPlane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)

/**
 * YUV_420_888 → ARGB IntArray（BT.601 全量程）。
 *
 * 覆盖相机/解码 Image 输出的两种内存排布：planar（I420，pixelStride=1）与
 * 半平面（NV12/NV21 类，UV 交错，pixelStride=2）。纯函数、无 Android 依赖。
 * 这个是典型的 YUV 转 ARGB 的实现。
 */
object Yuv420 {

    /**
     * @return [width*height] 的 ARGB 像素（0xFF 透明度位补齐）；
     *         寻址一律 position + 行列偏移的绝对读，不移动 position
     */
    fun toArgb(width: Int, height: Int, y: YuvPlane, u: YuvPlane, v: YuvPlane): IntArray {
        val yBase = y.buffer.position()
        val uBase = u.buffer.position()
        val vBase = v.buffer.position()
        val yMax = maxOf(y.buffer.limit() - 1, 0)
        val uMax = maxOf(u.buffer.limit() - 1, 0)
        val vMax = maxOf(v.buffer.limit() - 1, 0)
        val out = IntArray(width * height)
        var row = 0
        while (row < height) {
            val yRowBase = yBase + row * y.rowStride
            val uvRow = row shr 1
            val uRowBase = uBase + uvRow * u.rowStride
            val vRowBase = vBase + uvRow * v.rowStride
            var col = 0
            while (col < width) {
                val yIdx = minOf(yRowBase + col, yMax)
                val uvCol = col shr 1
                val uIdx = minOf(uRowBase + uvCol * u.pixelStride, uMax)
                val vIdx = minOf(vRowBase + uvCol * v.pixelStride, vMax)
                val yy = (y.buffer.get(yIdx).toInt() and 0xFF).toFloat()
                val cb = (u.buffer.get(uIdx).toInt() and 0xFF).toFloat() - 128f
                val cr = (v.buffer.get(vIdx).toInt() and 0xFF).toFloat() - 128f
                val r = clamp(yy + 1.402f * cr)
                val g = clamp(yy - 0.344136f * cb - 0.714136f * cr)
                val b = clamp(yy + 1.772f * cb)
                out[row * width + col] = 0xFF shl 24 or
                    (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
                col++
            }
            row++
        }
        return out
    }

    private fun clamp(v: Float): Float = v.coerceIn(0f, 255f)
}
