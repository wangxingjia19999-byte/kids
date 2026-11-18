package com.kid4rehab.pose

import android.media.Image
import java.nio.ByteBuffer

/**
 * YUV_420_888 → 转正（旋转 + 可选镜像）ARGB 的快路径：单趟逐像素、BT.601 全范围、
 * 索引越界钳制。零 GC：调用方持有 [buffer] 跨帧复用；尺寸不足时返回新数组，调用方应回存。
 *
 * 框模式：[toUprightArgbRoi] 只转换 [Roi]（长方形，比例与帧一致）内像素，
 * 免整帧 YUV→ARGB 搬运；镜像在整帧宽度口径内做，居中框镜像后仍是同一框。
 * 采样数学在 [convertCore]（ByteBuffer 入参，可 JVM 单测对拍），Image 只做取平面胶水。
 */
object YuvUprightArgb {

    /** 转正后的 ARGB；[argb] 恰好 [width]*[height] 个有效元素（ROI 模式为 roi.w×roi.h 裁片） */
    class Upright(val argb: IntArray, val width: Int, val height: Int)

    /**
     * [image] 相机/解码输出的 YUV_420_888；[rotationDegrees] 顺时针转正角度（ImageInfo 口径）；
     * [mirror] 前置镜像（输出为镜像后的转正帧）；[buffer] 复用缓冲（可为空数组）。
     */
    fun toUprightArgb(image: Image, rotationDegrees: Int, mirror: Boolean, buffer: IntArray): Upright {
        val planes = image.planes
        require(planes.size >= 3) { "需要 YUV_420_888 三平面，实际 ${planes.size}" }
        val argb = convertCore(
            y = planes[0].buffer, u = planes[1].buffer, v = planes[2].buffer,
            yRowStride = planes[0].rowStride, uRowStride = planes[1].rowStride, vRowStride = planes[2].rowStride,
            uPixelStride = planes[1].pixelStride, vPixelStride = planes[2].pixelStride,
            width = image.width, height = image.height,
            rotationDegrees = rotationDegrees, mirror = mirror,
            roi = null, out = buffer,
        )
        val swap = Math.floorMod(rotationDegrees, 360).let { it == 90 || it == 270 }
        val w = if (swap) image.height else image.width
        val h = if (swap) image.width else image.height
        return Upright(argb, w, h)
    }

    /**
     * 框模式直转：只输出 [roi]（转正帧像素空间）内 w×h 个像素（长方形裁片）。
     * [roi] 须在转正帧内（[RoiBox.computeRoi] 保证）；返回 Upright 宽高为 roi.w/roi.h。
     */
    fun toUprightArgbRoi(
        image: Image,
        rotationDegrees: Int,
        mirror: Boolean,
        roi: Roi,
        buffer: IntArray,
    ): Upright {
        val planes = image.planes
        require(planes.size >= 3) { "需要 YUV_420_888 三平面，实际 ${planes.size}" }
        val argb = convertCore(
            y = planes[0].buffer, u = planes[1].buffer, v = planes[2].buffer,
            yRowStride = planes[0].rowStride, uRowStride = planes[1].rowStride, vRowStride = planes[2].rowStride,
            uPixelStride = planes[1].pixelStride, vPixelStride = planes[2].pixelStride,
            width = image.width, height = image.height,
            rotationDegrees = rotationDegrees, mirror = mirror,
            roi = roi, out = buffer,
        )
        return Upright(argb, roi.w, roi.h)
    }

    /**
     * 单趟转换核心：输出转正（+可选镜像）帧的整幅（roi=null）或 [roi] 长方形裁片。
     * 输出像素 (x,y)（或 roi 内 (roi.x+u, roi.y+v)）→ 镜像 → 逆旋转 → 源平面采样；
     * 缓冲不足时返回新数组。
     */
    internal fun convertCore(
        y: ByteBuffer, u: ByteBuffer, v: ByteBuffer,
        yRowStride: Int, uRowStride: Int, vRowStride: Int,
        uPixelStride: Int, vPixelStride: Int,
        width: Int, height: Int,
        rotationDegrees: Int,
        mirror: Boolean,
        roi: Roi?,
        out: IntArray,
    ): IntArray {
        val rotation = Math.floorMod(rotationDegrees, 360)
        val swap = rotation == 90 || rotation == 270
        val uprightW = if (swap) height else width
        val uprightH = if (swap) width else height
        val outX0 = roi?.x ?: 0
        val outY0 = roi?.y ?: 0
        val outW = roi?.w ?: uprightW
        val outH = roi?.h ?: uprightH
        require(outX0 >= 0 && outY0 >= 0 && outX0 + outW <= uprightW && outY0 + outH <= uprightH) {
            "roi $roi 越界（转正帧 ${uprightW}x$uprightH）"
        }
        val pixels = if (out.size >= outW * outH) out else IntArray(outW * outH)

        val yMax = maxOf(y.limit() - 1, 0)
        val uMax = maxOf(u.limit() - 1, 0)
        val vMax = maxOf(v.limit() - 1, 0)
        val yBase = y.position()
        val uBase = u.position()
        val vBase = v.position()

        for (row in 0 until outH) {
            val ry = outY0 + row
            for (col in 0 until outW) {
                val x = outX0 + col
                val rx = if (mirror) uprightW - 1 - x else x
                val srcX: Int
                val srcY: Int
                when (rotation) {
                    0 -> { srcX = rx; srcY = ry }
                    90 -> { srcX = ry; srcY = height - 1 - rx }
                    180 -> { srcX = width - 1 - rx; srcY = height - 1 - ry }
                    else -> { srcX = width - 1 - ry; srcY = rx }
                }
                val yIndex = minOf(yBase + srcY * yRowStride + srcX, yMax)
                val uvRow = srcY shr 1
                val uvCol = srcX shr 1
                val uIndex = minOf(uBase + uvRow * uRowStride + uvCol * uPixelStride, uMax)
                val vIndex = minOf(vBase + uvRow * vRowStride + uvCol * vPixelStride, vMax)

                val yVal = (y.get(yIndex).toInt() and 0xFF).toFloat()
                val cb = (u.get(uIndex).toInt() and 0xFF).toFloat() - 128f
                val cr = (v.get(vIndex).toInt() and 0xFF).toFloat() - 128f

                val r = (yVal + 1.402f * cr).coerceIn(0f, 255f).toInt()
                val g = (yVal - 0.344136f * cb - 0.714136f * cr).coerceIn(0f, 255f).toInt()
                val b = (yVal + 1.772f * cb).coerceIn(0f, 255f).toInt()

                pixels[row * outW + col] = (r shl 16) or (g shl 8) or b
            }
        }
        return pixels
    }
}
