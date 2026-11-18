package com.kid4rehab.pose

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 安卓使用 CameraX 是为了减少不同机型的适配工作量，CameraX 内部封装了 Camera2 API 的复杂性。
 * 相机实时帧源：CameraX Preview + ImageAnalysis（KEEP_ONLY_LATEST，逐帧尽力而为，
 * 推理慢时丢旧帧）→ 端上推理 → 回调。
 *
 * - 预览走 [Preview] use case 直出相机流（PreviewView 前置默认镜像，与分析帧同像素空间）。
 * - 默认前置；绑定失败时回退后置，仍失败才报错。仅前置镜像（后置不镜像），
 *   关键点与预览始终同向。
 * - 分析/预览两侧显式 4:3（AspectRatioStrategy 优先级高于 ResolutionStrategy，
 *   不显式设置会按设备默认漂移到 16:9，叠加与分析帧错位）；分析流小分辨率推理快。
 * - 帧只在端上处理，无任何网络上传。
 * - 时间戳用 ImageInfo.timestamp（开机单调时钟，纳秒→毫秒）。
 * YUV是类似于RGB的那种更合适的数据格式，因为它可以提供更好的图像质量和更低的带宽需求。
 */ 
class CameraPoseSource(                 //在 CameraX 1.2.0+ 版本中，CameraX 的 API 已经发生了变化，CameraX 现在使用 CameraSelector 来选择相机，而不是直接使用 CameraX.bindToLifecycle() 方法。
    private val detector: PoseDetector,
    /** 期望镜头朝向（FRONT/BACK）；实际以 [lensFacing] 为准（绑定失败回退另一侧） */
    val wantLensFacing: Int = CameraSelector.LENS_FACING_FRONT,
    private val onFrame: (
        width: Int,
        height: Int,
        roi: Roi?,
        captureMs: Long,
        pose: PoseFrame?,
        inferenceMs: Long,
        mirrored: Boolean,
    ) -> Unit,
    private val onError: (String) -> Unit,
) : AutoCloseable {

    /** 已送检帧数 */
    @Volatile
    var frameCount: Long = 0    //初始化
        private set

    /** 当前实际使用的镜头朝向；镜像口径随之：仅前置镜像 */
    @Volatile
    var lensFacing: Int = wantLensFacing     
        private set

    companion object {
        private const val TAG = "K4RPOSE"     
    }

    /** YUV 直入快路径持续失败时只告警一次（防逐帧刷屏） */
    private val fastFallbackLogged = AtomicBoolean(false)

    @Volatile
    private var provider: ProcessCameraProvider? = null

    @Volatile
    private var previewSurfaceProvider: Preview.SurfaceProvider? = null

    /** 已 close：放弃尚未完成的异步绑定（镜头切换时旧源正在启动的场合），防止占用相机 */
    @Volatile
    private var closed = false

    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "pose-camera-analysis") }
    private val started = AtomicBoolean(false)

    /** 预览输出面（PreviewView 提供）；须在 [start] 前设置 */
    fun setPreviewSurfaceProvider(provider: Preview.SurfaceProvider) {
        previewSurfaceProvider = provider
    }

    fun start(context: Context, lifecycleOwner: LifecycleOwner) {
        check(started.compareAndSet(false, true)) { "CameraPoseSource 只能 start 一次" }
        val appContext = context.applicationContext
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener({
            if (closed) return@addListener  // 等待 provider 期间已被关闭（快速切换镜头）
            try {
                val cameraProvider = future.get()
                provider = cameraProvider

                fun fourThirds(w: Int, h: Int) = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(w, h), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                    )
                    .build()

                // 每次绑定尝试用全新 use case（复用失败会话的实例会带上残留会话配置）
                fun newAnalysis(w: Int, h: Int) = ImageAnalysis.Builder()
                    .setResolutionSelector(fourThirds(w, h))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(executor, ::analyze) }

                fun newPreview(w: Int, h: Int) = Preview.Builder()
                    .setResolutionSelector(fourThirds(w, h))
                    .build()
                    .also { p -> previewSurfaceProvider?.let { p.setSurfaceProvider(it) } }

                fun bind(selector: CameraSelector): androidx.camera.core.Camera =
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner, selector,
                        newPreview(1280, 960), newAnalysis(320, 240),
                    )

                cameraProvider.unbindAll()
                // 按 wantLensFacing 绑定期望镜头，失败回退另一侧（仍失败才报错）
                val wantBack = wantLensFacing == CameraSelector.LENS_FACING_BACK
                val wantSelector =
                    if (wantBack) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                val fallbackSelector =
                    if (wantBack) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                val wantName = if (wantBack) "BACK" else "FRONT"
                val fallbackName = if (wantBack) "FRONT" else "BACK"
                try {
                    bind(wantSelector)
                    lensFacing = wantLensFacing
                    Log.i(TAG, "camera bound: $wantName（preview=1280x960 analysis=320x240）")
                } catch (e: Throwable) {
                    runCatching {
                        cameraProvider.unbindAll()
                        bind(fallbackSelector)
                    }.onFailure { throw e }
                    lensFacing =
                        if (wantBack) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                    Log.w(TAG, "wanted $wantName bind failed (${e.message})，camera bound: $fallbackName")
                }
            } catch (t: Throwable) {
                onError(t.message ?: t.javaClass.simpleName)
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    /** 分析线程：快路径 YUV 直入推理；失败回落整帧转换慢路径 */
    private fun analyze(proxy: ImageProxy) {
        var returned = false
        try {
            if (frameCount == 0L) Log.i(TAG, "第一帧分析到达: ${proxy.width}x${proxy.height} rot=${proxy.imageInfo.rotationDegrees}")
            val width = proxy.width
            val height = proxy.height
            val rotation = proxy.imageInfo.rotationDegrees
            val captureMs = proxy.imageInfo.timestamp / 1_000_000L

            // 镜像口径：仅前置镜像（PreviewView 前置预览默认镜像、后置不镜像，关键点须与预览同向）
            val mirror = lensFacing == CameraSelector.LENS_FACING_FRONT

            // 框模式：转正帧尺寸 → 居中长方形 ROI（比例与帧一致，占满整帧）
            val swap = Math.floorMod(rotation, 180) != 0
            val fw = if (swap) height else width
            val fh = if (swap) width else height
            val roi = if (detector.supportsRoiCapture) RoiBox.computeRoi(fw, fh) else null

            // 快路径：YUV 直入支持的检测器，免 YUV→ARGB→转正镜像→Bitmap 多趟整帧 CPU 搬运；
            // 失败只告警一次并逐帧回落慢路径。
            val fast = proxy.image?.let { image ->
                runCatching {
                    roi?.let { r -> detector.detectImageRoi(image, rotation, mirror, r, timestampMs = captureMs) }
                        ?: detector.detectImage(image, rotation, mirror, timestampMs = captureMs)
                }
                    .onFailure {
                        if (fastFallbackLogged.compareAndSet(false, true)) {
                            Log.w(TAG, "YUV 直入失败（${it.message}），回落整帧像素路径")
                        }
                    }
                    .getOrNull()
            }
            if (fast != null) {
                // 快路径成功，使用分析结果
                returned = true
                proxy.close()
                frameCount++
                onFrame(fw, fh, roi, captureMs, fast.frame, fast.inferenceMs, mirror)
                return
            }

            // 慢路径：整帧 YUV→ARGB→转正镜像 → ARGB 直入
            fun plane(i: Int) = proxy.planes[i].let { YuvPlane(it.buffer, it.rowStride, it.pixelStride) }
            val raw = Yuv420.toArgb(width, height, plane(0), plane(1), plane(2))
            returned = true
            proxy.close()

            val upright = ArgbTransform.toUprightMirrored(raw, width, height, rotation, mirror)
            val result = detector.detectArgb(upright.argb, upright.width, upright.height, captureMs)
            frameCount++
            onFrame(upright.width, upright.height, roi, captureMs, result.frame, result.inferenceMs, mirror)
        } catch (t: Throwable) {
            onError(t.message ?: t.javaClass.simpleName)
        } finally {
            if (!returned) runCatching { proxy.close() }
        }
    }

    override fun close() {
        closed = true
        runCatching { provider?.unbindAll() }
        executor.shutdown()
    }
}
