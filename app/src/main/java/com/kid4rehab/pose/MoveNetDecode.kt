package com.kid4rehab.pose

import kotlin.math.max

/**
 * MoveNet Lightning 头解码（纯逻辑，可单测）。
 *
 * 模型输出（官方 tflite 切头转换，heatmap/offset 两张量）：
 * - heatmap [48,48,17]：每关键点热图（sigmoid，CHW 布局）
 * - offset  [48,48,34]：热图峰值处的亚像素偏移（每关键点 dy,dx 交错，CHW）
 *
 * 解码（对齐官方 tflite 内置头，经完整模型输出数值对拍）：
 *   flat = argmax(heatmap[:,:,k])；y=flat/48、x=flat%48
 *   y_norm = (y + offset[y,x,2k]) / 48；x_norm = (x + offset[y,x,2k+1]) / 48
 *   conf = heatmap[y,x,k]
 * 归一化坐标为 letterbox 张量空间（192×192 黑边 keep_aspect），
 * 反变换到帧像素经 [Letterbox]（size=192）。
 */
object MoveNetDecode {

    const val SIZE = 192
    const val HEATMAP_SIZE = 48
    const val HEATMAP_LEN = 48 * 48 * 17
    const val OFFSET_LEN = 48 * 48 * 34

    /**
     * heatmap/offset → 17×3（帧像素 x,y,conf）。返回 score=17 点 conf 均值。
     * [heatmapBase]/[offsetBase]：两段在 [raw]（nativeRun 展平输出）中的起始下标，免切片复制。
     */
    fun decode(
        raw: FloatArray,
        heatmapBase: Int,
        offsetBase: Int,
        frameW: Int,
        frameH: Int,
        lb: Letterbox,
        outKeypoints: FloatArray,
    ): Float {
        val hm = raw
        val size = HEATMAP_SIZE
        var confSum = 0f
        for (k in 0 until Coco17.COUNT) {
            // 每通道 48×48 argmax（CHW 布局：[k][y][x]，经 onnx2ncnn 转换与 ONNX 逐位对拍验证）
            val chBase = heatmapBase + k * size * size
            var bestIdx = 0
            var bestVal = -1f
            for (i in 0 until size * size) {
                val v = hm[chBase + i]
                if (v > bestVal) {
                    bestVal = v
                    bestIdx = i
                }
            }
            val y = bestIdx / size
            val x = bestIdx % size
            // offset 同为 CHW [34][48][48]：通道 2k=dy、2k+1=dx
            val offBase = offsetBase + (k * 2) * size * size + y * size + x
            val offBaseDx = offsetBase + (k * 2 + 1) * size * size + y * size + x
            val yTensor = (y + raw[offBase]) / size
            val xTensor = (x + raw[offBaseDx]) / size
            // 张量归一化坐标 → 帧像素（letterbox 反变换）
            val fx = lb.toFrameX(xTensor) * frameW
            val fy = lb.toFrameY(yTensor) * frameH
            outKeypoints[k * 3] = fx
            outKeypoints[k * 3 + 1] = fy
            outKeypoints[k * 3 + 2] = bestVal
            confSum += bestVal
        }
        return confSum / Coco17.COUNT
    }

    /**
     * 框模式坐标回程：裁片空间关键点（decode 产物）平移回整帧坐标。
     * 纯平移，原地修改；配合 [RoiBox.computeRoi] 的居中方框使用。
     */
    fun applyRoiOffset(keypoints: FloatArray, roi: Roi) {
        for (k in 0 until Coco17.COUNT) {
            keypoints[k * 3] += roi.x
            keypoints[k * 3 + 1] += roi.y
        }
    }

    /** 整帧 ARGB → letterbox（keep_aspect 居中黑边，官方 resize_with_pad 口径）[size]×[size] ARGB，双线性 */
    fun buildLetterboxCrop(
        argb: IntArray,
        frameW: Int,
        frameH: Int,
        size: Int,
        buffer: IntArray,
    ): Letterbox {
        val lb = Letterbox.forFrame(frameW, frameH, size)
        java.util.Arrays.fill(buffer, 0, size * size, 0xFF000000.toInt())  // 黑边
        val xRatio = (frameW - 1).toFloat() / max(lb.contentW - 1, 1)
        val yRatio = (frameH - 1).toFloat() / max(lb.contentH - 1, 1)
        for (v in 0 until lb.contentH) {
            val syf = v * yRatio
            val sy0 = syf.toInt()
            val sy1 = if (sy0 + 1 < frameH) sy0 + 1 else sy0
            val fy = syf - sy0
            val rowBase = (v + lb.padY) * size + lb.padX
            for (u in 0 until lb.contentW) {
                val sxf = u * xRatio
                val sx0 = sxf.toInt()
                val sx1 = if (sx0 + 1 < frameW) sx0 + 1 else sx0
                val fx = sxf - sx0
                val p00 = argb[sy0 * frameW + sx0]
                val p10 = argb[sy0 * frameW + sx1]
                val p01 = argb[sy1 * frameW + sx0]
                val p11 = argb[sy1 * frameW + sx1]
                // 全程浮点插值（中途 .toInt() 会截断权重 → 整图黑，真机已踩）
                val w00 = (1 - fx) * (1 - fy)
                val w10 = fx * (1 - fy)
                val w01 = (1 - fx) * fy
                val w11 = fx * fy
                val r = (p00 ushr 16 and 0xFF) * w00 + (p10 ushr 16 and 0xFF) * w10 +
                    (p01 ushr 16 and 0xFF) * w01 + (p11 ushr 16 and 0xFF) * w11
                val g = (p00 ushr 8 and 0xFF) * w00 + (p10 ushr 8 and 0xFF) * w10 +
                    (p01 ushr 8 and 0xFF) * w01 + (p11 ushr 8 and 0xFF) * w11
                val b = (p00 and 0xFF) * w00 + (p10 and 0xFF) * w10 +
                    (p01 and 0xFF) * w01 + (p11 and 0xFF) * w11
                buffer[rowBase + u] = 0xFF000000.toInt() or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
            }
        }
        return lb
    }
}
