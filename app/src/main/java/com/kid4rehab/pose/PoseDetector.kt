package com.kid4rehab.pose

import android.media.Image

/** 单帧姿态检测产物：组装的引擎帧（无人时 null）+ 推理耗时 */
data class PoseDetectionResult(
    val frame: PoseFrame?,
    val inferenceMs: Long,
)

/** 检测器统一契约（引擎只消费 COCO17） */
interface PoseDetector : AutoCloseable {
    fun detect(bitmap: android.graphics.Bitmap, timestampMs: Long): PoseDetectionResult

    /**
     * 是否支持框模式采集：true 时相机源按 [RoiBox.computeRoi] 框定长方形 ROI
     * （比例与帧一致、占满整帧）直入——裁片 letterbox 进模型，免整帧 YUV→ARGB 搬运；
     * false = 整帧 letterbox 后端。
     */
    val supportsRoiCapture: Boolean
        get() = false

    /**
     * ARGB 整帧直入（已转正+镜像的分析帧像素空间）。
     * 支持直入的后端（NCNN）免 Bitmap 往返；默认实现包 Bitmap 走 [detect]。
     */
    fun detectArgb(argb: IntArray, width: Int, height: Int, timestampMs: Long): PoseDetectionResult {
        val bitmap = android.graphics.Bitmap.createBitmap(argb, width, height, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            return detect(bitmap, timestampMs)
        } finally {
            bitmap.recycle()
        }
    }

    /** YUV 直入（相机 ImageAnalysis 快路径）；不支持的后端返回 null，调用方回落 [detectArgb] */
    fun detectImage(
        image: Image,
        rotationDegrees: Int,
        mirror: Boolean,
        timestampMs: Long,
    ): PoseDetectionResult? = null

    /**
     * 框模式 YUV 直入：只转换 [roi]（转正帧像素空间，长方形）内像素，
     * 免整帧 YUV→ARGB 搬运，裁片 letterbox 进模型；返回关键点已平移回整帧坐标。
     * 不支持的后端返回 null，调用方回落 [detectImage] 整帧。
     */
    fun detectImageRoi(
        image: Image,
        rotationDegrees: Int,
        mirror: Boolean,
        roi: Roi,
        timestampMs: Long,
    ): PoseDetectionResult? = null
}
