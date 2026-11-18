package com.kid4rehab.pose

import android.content.Context
import android.media.Image
import android.os.SystemClock
import java.io.File

/**
 * MoveNet SinglePose Lightning · NCNN 检测器。
 *
 * 模型：官方 tflite（kaggle google/movenet singlepose-lightning）切头自转换——
 * 卷积主干进 ncnn（heatmap [48,48,17] + offset [48,48,34]），头解码（argmax+offset）在
 * Kotlin（[MoveNetDecode]，与完整官方模型输出数值对拍）。输入 192×192 letterbox 黑边
 * （resize_with_pad 口径）、像素 0-255 原值（模型内部自带 Sub 预处理）。
 * 框模式：[detectImageRoi] 只取长方形 ROI 裁片（比例与帧一致，接近全幅），
 * 裁片 letterbox 进 192×192——人可站得更近。
 *
 * - [detectImage] YUV 直入（[YuvUprightArgb] 零 GC）；[detectArgb] ARGB 直入；[detect] Bitmap 兼容。
 * - score=17 点 conf 均值，[NO_PERSON_SCORE] 以下无人。
 */
class NcnnMoveNetDetector private constructor(
    mnParamPath: String,
    mnBinPath: String,
) : PoseDetector {

    /** 框模式声明：方形 192 输入 + 单阶段全帧几何 → 支持长方形 ROI 裁片直入 */
    override val supportsRoiCapture: Boolean = true

    /** 实际推理设备（Vulkan 失败回 CPU） */
    val device: String

    private var closed = false

    private var uprightPixelsBuffer = IntArray(0)

    /** 框模式 ROI 裁片复用缓冲（roi.w×roi.h 长方形） */
    private var roiPixelsBuffer = IntArray(0)

    /** 192×192 letterbox 复用缓冲 */
    private val cropBuffer = IntArray(MoveNetDecode.SIZE * MoveNetDecode.SIZE)

    /** 关键点复用缓冲（17×3 帧像素） */
    private val keypointsBuffer = FloatArray(Coco17.COUNT * 3)

    /** nativeRun 展平输出复用缓冲（heatmap+offset 连续段，免每帧 ~470KB 分配） */
    private val rawBuffer = FloatArray(MoveNetDecode.HEATMAP_LEN + MoveNetDecode.OFFSET_LEN)

    init {
        val mode = NcnnJni.nativeInit(mnParamPath, mnBinPath, MoveNetDecode.SIZE, tryVulkan)
        require(mode >= 0) { "ncnn 初始化失败（mode=$mode）" }
        device = if (mode == 1) "Vulkan" else "CPU"
    }

    override fun detect(bitmap: android.graphics.Bitmap, timestampMs: Long): PoseDetectionResult {
        val argb = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return detectArgb(argb, bitmap.width, bitmap.height, timestampMs)
    }

    override fun detectArgb(argb: IntArray, width: Int, height: Int, timestampMs: Long): PoseDetectionResult {
        return runCropPipeline(argb, width, height, timestampMs, roi = null)
    }

    @Synchronized
    override fun detectImage(
        image: Image,
        rotationDegrees: Int,
        mirror: Boolean,
        timestampMs: Long,
    ): PoseDetectionResult? {
        if (image.planes.size < 3) return null
        val upright = YuvUprightArgb.toUprightArgb(image, rotationDegrees, mirror, uprightPixelsBuffer)
        uprightPixelsBuffer = upright.argb
        return runCropPipeline(upright.argb, upright.width, upright.height, timestampMs, roi = null)
    }

    /** 框模式：只转换长方形 ROI 内像素（免整帧 YUV→ARGB），裁片 letterbox 进 192 */
    @Synchronized
    override fun detectImageRoi(
        image: Image,
        rotationDegrees: Int,
        mirror: Boolean,
        roi: Roi,
        timestampMs: Long,
    ): PoseDetectionResult? {
        if (image.planes.size < 3) return null
        val crop = YuvUprightArgb.toUprightArgbRoi(image, rotationDegrees, mirror, roi, roiPixelsBuffer)
        roiPixelsBuffer = crop.argb
        return runCropPipeline(crop.argb, crop.width, crop.height, timestampMs, roi)
    }

    /**
     * 统一管线：源（整帧或框内长方形裁片）→ 192×192 → ncnn → 头解码 → COCO17。
     * 源为 192×192 正方形（方形裁片）时直用免 letterbox，否则 letterbox 等比缩放；
     * [roi] 非 null 时解码后平移回整帧坐标。
     */
    @Synchronized
    private fun runCropPipeline(
        argb: IntArray,
        width: Int,
        height: Int,
        timestampMs: Long,
        roi: Roi?,
    ): PoseDetectionResult {
        val t0 = SystemClock.elapsedRealtime()

        // ① 源 → 192×192（黑边，像素 0-255 原值：mean 0 / norm 1）；同尺寸直用零 resize
        val lb = Letterbox.forFrame(width, height, MoveNetDecode.SIZE)
        val tensor = if (width == MoveNetDecode.SIZE && height == MoveNetDecode.SIZE) {
            argb
        } else {
            MoveNetDecode.buildLetterboxCrop(argb, width, height, MoveNetDecode.SIZE, cropBuffer)
            cropBuffer
        }
        NcnnJni.nativeRun(tensor, MoveNetDecode.SIZE, 0f, 1f, rawBuffer)

        // ② 输出定位：按形状语义在 rawBuffer 中定位 heatmap/offset 段基址（免切片复制）
        var heatmapBase = -1
        var offsetBase = -1
        var o = 0
        val lens = NcnnJni.nativeOutputLens()
        for (len in lens) {
            when (len) {
                MoveNetDecode.HEATMAP_LEN -> heatmapBase = o
                MoveNetDecode.OFFSET_LEN -> offsetBase = o
            }
            o += len
        }
        if (heatmapBase < 0 || offsetBase < 0) {
            throw IllegalStateException("MoveNet 输出形状异常 lens=${lens.toList()}")
        }

        // ③ 头解码 → COCO17 帧像素（roi 模式先得裁片坐标再平移回整帧）；无人短路
        val score = MoveNetDecode.decode(rawBuffer, heatmapBase, offsetBase, width, height, lb, keypointsBuffer)
        roi?.let { MoveNetDecode.applyRoiOffset(keypointsBuffer, it) }
        val frame = if (score < NO_PERSON_SCORE) null else PoseFrame(timestampMs, score, keypointsBuffer.copyOf())
        return PoseDetectionResult(frame, SystemClock.elapsedRealtime() - t0)
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            NcnnJni.nativeClose()
        }
    }

    companion object {
        /** MoveNet 输入边长 */
        const val SIZE = 192

        /** 无人判定阈值（conf 均值口径） */
        const val NO_PERSON_SCORE = 0.3f

        private const val PARAM_ASSET = "pose/ncnn/movenet_lightning.param"
        private const val BIN_ASSET = "pose/ncnn/movenet_lightning.bin"

        /**
         * Vulkan 总开关（默认关：个别机型 Vulkan 驱动下 ncnn 段错误——
         * recover4kids 真机实测小米 23116PN5BC；CPU 档 320×240 分析流已够实时）。
         */
        var tryVulkan: Boolean = false

        fun isAvailable(context: Context): Boolean =
            runCatching {
                context.assets.open(PARAM_ASSET).close()
                context.assets.open(BIN_ASSET).close()
            }.isSuccess

        fun fromAssets(context: Context): NcnnMoveNetDetector {
            check(isAvailable(context)) { "MoveNet ncnn 资产缺失（$PARAM_ASSET 等）" }
            val dir = File(context.cacheDir, "pose-ncnn")
            dir.mkdirs()
            val files = arrayOf(PARAM_ASSET, BIN_ASSET).map { assetPath ->
                val f = File(dir, assetPath.substringAfterLast('/'))
                if (!f.exists() || f.length() == 0L) {
                    context.assets.open(assetPath).use { input ->
                        f.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                f.absolutePath
            }
            return NcnnMoveNetDetector(files[0], files[1])
        }
    }
}
