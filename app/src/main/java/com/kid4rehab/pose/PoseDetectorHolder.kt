package com.kid4rehab.pose

import android.content.Context
import android.media.Image
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

/**
 * 进程级单检测器 + 单推理线程。
 *
 * 背景（HyperOS 真机实测）：libomp 静态链入 libpose_ncnn.so。检测器创建线程的首次
 * 并行区（warm_up）正常；当**第二个线程**成为 OMP master（相机分析线程首帧推理）时，
 * libomp 为新 root 重跑 __kmp_affinity_initialize，在该类设备核布局/cgroup 下断言失败
 * → __kmp_abort_process SIGABRT（KMP_AFFINITY=disabled 环境变量实测无效）。
 *
 * 因此检测器创建（含 warm_up）与所有推理固定在同一专用线程（[executor]）——
 * libomp 全进程只有一个外来 master root，从根上绕开新 root 的亲和性初始化路径。
 * 进程级单例：Activity 重建不 close（权重常驻，也避免新线程再触发 root 初始化），
 * 进程退出随 VM 释放。CameraX 分析线程经 [PinnedDetector] 同步转发到推理线程。
 */
object PoseDetectorHolder {

    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "pose-inference") }

    @Volatile
    private var instance: PinnedDetector? = null

    fun get(context: Context): PinnedDetector {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val task = FutureTask { NcnnMoveNetDetector.fromAssets(context.applicationContext) }
            executor.execute(task)
            val detector = try {
                task.get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
            return PinnedDetector(detector, executor).also { instance = it }
        }
    }
}

/**
 * 委托 [detector]，并把所有调用钉到 [executor]（与创建/warm_up 同线程）。
 * 相机分析线程同步等待（FutureTask.get）——KEEP_ONLY_LATEST 单帧在途，无排队堆积。
 */
class PinnedDetector(
    private val detector: NcnnMoveNetDetector,
    private val executor: ExecutorService,
) : PoseDetector by detector {

    val device: String get() = detector.device

    private fun <T> pinned(block: () -> T): T {
        val task = FutureTask(block)
        executor.execute(task)
        return task.get()
    }

    override fun detect(bitmap: android.graphics.Bitmap, timestampMs: Long): PoseDetectionResult =
        pinned { detector.detect(bitmap, timestampMs) }

    override fun detectArgb(argb: IntArray, width: Int, height: Int, timestampMs: Long): PoseDetectionResult =
        pinned { detector.detectArgb(argb, width, height, timestampMs) }

    override fun detectImage(
        image: Image,
        rotationDegrees: Int,
        mirror: Boolean,
        timestampMs: Long,
    ): PoseDetectionResult? = pinned { detector.detectImage(image, rotationDegrees, mirror, timestampMs) }

    override fun detectImageRoi(
        image: Image,
        rotationDegrees: Int,
        mirror: Boolean,
        roi: Roi,
        timestampMs: Long,
    ): PoseDetectionResult? = pinned { detector.detectImageRoi(image, rotationDegrees, mirror, roi, timestampMs) }

    override fun close() {
        // 进程级单例不关闭：保持权重常驻与推理线程复用（见 PoseDetectorHolder）
    }
}
