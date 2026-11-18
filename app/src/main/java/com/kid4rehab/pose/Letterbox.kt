package com.kid4rehab.pose

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 把模型输出的坐标，转换回摄像头原始画面中的坐标。
 * letterbox 几何（keep_aspect 居中缩放进方形张量）：张量归一化坐标 ↔ 帧像素坐标互转。
 * 口径与官方 resize_with_pad 一致（内容居中、两侧补边）。
 */
class Letterbox(val padX: Int, val padY: Int, val contentW: Int, val contentH: Int, val size: Int) {

    /** 张量归一化 x（0-1，含 pad）→ 帧归一化 x（0-1，内容区） */
    fun toFrameX(xTensor: Float): Float = (xTensor * size - padX) / contentW

    /** 张量归一化 y（0-1，含 pad）→ 帧归一化 y（0-1，内容区） */
    fun toFrameY(yTensor: Float): Float = (yTensor * size - padY) / contentH

    companion object {
        /** 按帧宽高算 letterbox：等比缩放至 [size]×[size]，短边方向居中补边 */
        fun forFrame(frameW: Int, frameH: Int, size: Int): Letterbox {
            val scale = min(size.toFloat() / frameW, size.toFloat() / frameH)
            val cw = (frameW * scale).roundToInt().coerceAtLeast(1)
            val ch = (frameH * scale).roundToInt().coerceAtLeast(1)
            return Letterbox((size - cw) / 2, (size - ch) / 2, cw, ch, size)
        }
    }
}
