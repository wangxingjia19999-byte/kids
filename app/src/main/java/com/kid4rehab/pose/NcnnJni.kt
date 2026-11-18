package com.kid4rehab.pose

/**
 * NCNN JNI 统一声明点（符号绑定在类名上）。
 *
 * native 侧只有一个进程级网络：各入口互斥串行；同一模型重复 [nativeInit] 只加引用不重载，
 * [nativeClose] 引用归零才释放。
 */
internal object NcnnJni {
    init {
        System.loadLibrary("pose_ncnn")
    }

    /** 返回 1=Vulkan 0=CPU；失败抛 IllegalStateException */
    external fun nativeInit(paramPath: String, binPath: String, inputSize: Int, tryVulkan: Boolean): Int

    /** crop ARGB → RGB → mean/norm 归一化 → 推理 → 输出小 blob 展平写入 [out]（复用缓冲零分配），返回写入元素数 */
    external fun nativeRun(cropArgb: IntArray, size: Int, mean: Float, norm: Float, out: FloatArray): Int

    /** 各输出 blob 元素数（init/每次 run 后更新） */
    external fun nativeOutputLens(): IntArray

    /** 释放本实例的引用（每个实例至多调一次） */
    external fun nativeClose()
}
