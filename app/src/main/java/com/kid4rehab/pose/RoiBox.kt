package com.kid4rehab.pose

/** 分析帧（转正后像素空间）上的识别框：长方形、居中，宽高比例与帧一致（跟随采集画幅） */
data class Roi(val x: Int, val y: Int, val w: Int, val h: Int) {
    init {
        require(w >= 1 && h >= 1) { "w/h 必须 ≥1，实际 ${w}×${h}" }
    }
}

/**
 * 框模式几何：分析帧上的居中长方形 ROI（跟随采集画幅比例、占画面 [FRACTION]）。
 *
 * 框内裁片 letterbox（keep_aspect）进模型——模型输入必为方形（192×192），长方形
 * 裁片等比缩放后补黑边；框越接近全幅，人可站距离越近。
 */
object RoiBox {

    /** 框占画幅比例（宽高各取 100%：整帧送检） */
    const val FRACTION = 1.00f

    /** 居中长方形 ROI：宽高各 [fraction] 画幅、比例与帧一致 */
    fun computeRoi(frameW: Int, frameH: Int, fraction: Float = FRACTION): Roi {
        val w = (frameW * fraction).toInt().coerceIn(1, frameW)
        val h = (frameH * fraction).toInt().coerceIn(1, frameH)
        return Roi((frameW - w) / 2, (frameH - h) / 2, w, h)
    }
}
