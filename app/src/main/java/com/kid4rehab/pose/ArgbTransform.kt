package com.kid4rehab.pose

/**
 * 相机帧方向整理：ImageAnalysis 产出的 ARGB 是传感器方向，
 * 按 rotationDegrees 顺时针旋转转正；前置相机再水平镜像（镜面自拍约定，
 * 与系统相机预览一致）。
 *
 * 镜像在送推理**之前**做 → 预览、关键点天然同一像素空间。纯函数、无 Android 依赖。
 */
object ArgbTransform {

    /** 转正（+可选镜像）后的一帧：像素与尺寸一起换（90°/270° 下宽高交换） */
    data class Frame(val argb: IntArray, val width: Int, val height: Int)

    /**
     * @param argb 传感器方向的 [width*height] 像素
     * @param rotationDegrees 顺时针转正角度（ImageInfo.rotationDegrees，90 的倍数，可为负）
     * @param mirror 转正后水平镜像（前置相机传 true）
     */
    fun toUprightMirrored(
        argb: IntArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        mirror: Boolean,
    ): Frame {
        require(argb.size == width * height) {
            "像素数不符：期望 ${width * height}，实际 ${argb.size}"
        }
        val rotation = Math.floorMod(rotationDegrees, 360)
        require(rotation % 90 == 0) { "rotationDegrees 必须是 90 的倍数：$rotationDegrees" }
        if (rotation == 0 && !mirror) return Frame(argb, width, height)

        val swap = rotation == 90 || rotation == 270
        val outW = if (swap) height else width
        val outH = if (swap) width else height
        val out = IntArray(argb.size)
        for (y in 0 until outH) {
            for (x in 0 until outW) {
                // 先顺时针旋转，再对转正结果水平镜像：由目标坐标反解源坐标
                val rx = if (mirror) outW - 1 - x else x
                val ry = y
                val srcX: Int
                val srcY: Int
                when (rotation) {
                    0 -> { srcX = rx; srcY = ry }
                    90 -> { srcX = ry; srcY = height - 1 - rx }
                    180 -> { srcX = width - 1 - rx; srcY = height - 1 - ry }
                    else -> { srcX = width - 1 - ry; srcY = rx } // 270
                }
                out[y * outW + x] = argb[srcY * width + srcX]
            }
        }
        return Frame(out, outW, outH)
    }
}
