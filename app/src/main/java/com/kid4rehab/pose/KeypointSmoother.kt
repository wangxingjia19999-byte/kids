package com.kid4rehab.pose

import kotlin.math.PI
import kotlin.math.abs

/**
 * 关键点平滑（移植自 recover4kids judge/Smoothing.kt）：One-Euro 滤波 + 低置信剔除。
 *
 * 像素坐标逐点滤波后再归一化（躯干长同样会抖，先平滑可同时稳定分母）。
 * 低置信点（conf < [minConf]）不更新滤波器并保持上一稳定值（置信沿用旧值），
 * 桥接短暂检测抖落；连续缺失超过 [maxHoldFrames] 帧则透传原始值（由归一化层判为不可用）。
 * 录制打点与训练比对都消费平滑帧。
 */
class KeypointSmoother(
    private val minConf: Float = PoseNormalize.MIN_CONF,
    private val maxHoldFrames: Int = 10,
    minCutoff: Double = 1.0,
    beta: Double = 1.2,
    dCutoff: Double = 1.0,
) {
    private val filters = Array(Coco17.COUNT * 2) { OneEuro(minCutoff, beta, dCutoff) }
    private val last = FloatArray(Coco17.COUNT * 3) { Float.NaN }
    private val holdSince = IntArray(Coco17.COUNT) { -1 }

    fun smooth(frame: PoseFrame): PoseFrame {
        // 整帧无人（所有点都低于置信门槛）：不做保持桥接，原样透传——
        // 由归一化层判为不可用帧。保持桥接只用于「人还在、个别点抖落」的场景。
        val anyFresh = (0 until Coco17.COUNT).any { frame.conf(it) >= minConf }
        if (!anyFresh) return frame

        val out = frame.keypoints.copyOf()
        for (i in 0 until Coco17.COUNT) {
            val conf = frame.conf(i)
            if (conf >= minConf) {
                out[i * 3] = filters[i * 2].filter(frame.timestampMs, frame.x(i))
                out[i * 3 + 1] = filters[i * 2 + 1].filter(frame.timestampMs, frame.y(i))
                out[i * 3 + 2] = conf
                last[i * 3] = out[i * 3]
                last[i * 3 + 1] = out[i * 3 + 1]
                last[i * 3 + 2] = conf
                holdSince[i] = -1
            } else {
                val held = holdSince[i]
                if (held >= 0 && held < maxHoldFrames) {
                    // 保持上一稳定值（含旧置信，让归一化层继续可用）
                    out[i * 3] = last[i * 3]
                    out[i * 3 + 1] = last[i * 3 + 1]
                    out[i * 3 + 2] = last[i * 3 + 2]
                    holdSince[i] = held + 1
                } else if (held < 0) {
                    holdSince[i] = 0
                    out[i * 3] = last[i * 3].takeIf { !it.isNaN() } ?: frame.x(i)
                    out[i * 3 + 1] = last[i * 3 + 1].takeIf { !it.isNaN() } ?: frame.y(i)
                    out[i * 3 + 2] = if (last[i * 3 + 2].isNaN()) conf else last[i * 3 + 2]
                } else {
                    out[i * 3 + 2] = conf // 透传低置信：本点该帧不可用
                }
            }
        }
        return PoseFrame(frame.timestampMs, frame.score, out)
    }
}

/**
 * One-Euro Filter（Casiez et al. 2012）：低频截止随速度自适应——
 * 静止时强平滑（不抖）、快速移动时弱平滑（不拖影）。
 * beta=1.2 提升运动自适应响应速，解决肢体快速到位时关键点滞后。
 */
class OneEuro(
    private val minCutoff: Double,
    private val beta: Double,
    private val dCutoff: Double,
) {
    private var xPrev = Double.NaN
    private var dxPrev = 0.0
    private var tPrevMs = Long.MIN_VALUE

    fun filter(tMs: Long, x: Float): Float {
        if (xPrev.isNaN()) {
            xPrev = x.toDouble()
            tPrevMs = tMs
            return x
        }
        val dt = ((tMs - tPrevMs) / 1000.0).coerceIn(1.0 / 240.0, 1.0).also { tPrevMs = tMs }
        val dx = (x - xPrev) / dt
        val edx = alpha(dCutoff, dt) * dx + (1 - alpha(dCutoff, dt)) * dxPrev
        val cutoff = minCutoff + beta * abs(edx)
        val a = alpha(cutoff, dt)
        val xf = a * x + (1 - a) * xPrev
        dxPrev = edx
        xPrev = xf.toDouble()
        return xf.toFloat()
    }

    private fun alpha(cutoff: Double, dt: Double): Double {
        val tau = 1.0 / (2 * PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }
}
