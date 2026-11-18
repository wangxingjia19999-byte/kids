package com.kid4rehab.pose

/**
 * check 点比对（口径移植自 recover4kids Geometry.ghostDeviationTopK）。
 *
 * 偏差 = 模板点与实时点（归一化坐标系、双侧 conf ≥ MIN_CONF 的同名点）
 * 欧氏距离取**前 [TOP_K] 大的均值**——用最偏的几个点代表姿态差，避免
 * "一半点对了就算对"的宽松，也避免被个别抖动点主导。
 */
object PoseMatcher {

    /** 达成容差（躯干长单位）：topK 均值偏差低于此值视为姿态吻合 */
    const val TOLERANCE = 0.35f

    /** 参与均值的最大偏差点数 */
    const val TOP_K = 8

    /** 共同可用点下限（少于则返回 null，本帧不判定） */
    const val MIN_COMMON = 6

    /** 容差内的持续帧数（约 0.5s @15fps）才判定达成，防瞬时误过 */
    const val HOLD_FRAMES = 8

    /**
     * 模板 check 点（归一化 17×3）与实时归一化帧的偏差。
     * 共同可用点不足 [MIN_COMMON] 返回 null（遮挡/半身入画时不判定）。
     *
     * @param allowSideSwap 动作不区分左右时传 true：另算一份「实时左右互换 + x 取反」
     *   的镜像姿态分，取两者较低（分侧康复动作保持 false，避免左手模板匹配右手）
     */
    fun deviation(templateKp: FloatArray, live: NormFrame, allowSideSwap: Boolean = false): Float? {
        val direct = deviation(templateKp, live.snapshot())
        if (!allowSideSwap || direct == null) return direct
        return minOf(deviation(templateKp, mirrorSwap(live.snapshot())) ?: return direct, direct)
    }

    /** 模板 check 点与实时归一化 17×3（快照）的偏差 */
    fun deviation(templateKp: FloatArray, liveKp: FloatArray): Float? {
        val dists = FloatArray(Coco17.COUNT) { Float.MAX_VALUE }
        var common = 0
        for (i in 0 until Coco17.COUNT) {
            val tc = templateKp[i * 3 + 2]
            val lc = liveKp[i * 3 + 2]
            if (tc >= PoseNormalize.MIN_CONF && lc >= PoseNormalize.MIN_CONF) {
                val dx = templateKp[i * 3] - liveKp[i * 3]
                val dy = templateKp[i * 3 + 1] - liveKp[i * 3 + 1]
                dists[common++] = kotlin.math.hypot(dx, dy)
            }
        }
        if (common < MIN_COMMON) return null
        // 降序取前 TOP_K 大的均值
        dists.sort(0, common)
        val k = minOf(TOP_K, common)
        var sum = 0f
        for (i in 0 until k) sum += dists[common - 1 - i]
        return sum / k
    }

    /** 姿态左右互换（索引互换 + 归一化 x 取反）：镜像不变比对用 */
    private fun mirrorSwap(pts: FloatArray): FloatArray {
        val out = FloatArray(Coco17.COUNT * 3)
        for (i in 0 until Coco17.COUNT) {
            val j = Coco17.MIRROR_OF[i]
            out[i * 3] = -pts[j * 3]
            out[i * 3 + 1] = pts[j * 3 + 1]
            out[i * 3 + 2] = pts[j * 3 + 2]
        }
        return out
    }
}

/**
 * 单个 check 点的达成状态机：偏差 < [tolerance] 连续 [holdFrames] 帧 → 达成；
 * 偏差在容差 1.2×内保持不累积但不清零（滞回），更远则清零。
 * 每个 check 点一个实例；[passed] 后不再判定。
 */
class CheckTracker(
    private val tolerance: Float = PoseMatcher.TOLERANCE,
    private val holdFrames: Int = PoseMatcher.HOLD_FRAMES,
) {
    var passed = false
        private set

    var hold = 0
        private set

    /** 达成进度 0-1（UI 偏差条用） */
    fun progress(): Float = if (passed) 1f else (hold.toFloat() / holdFrames).coerceIn(0f, 1f)

    /**
     * 逐帧喂入偏差；返回 true = 本帧恰好达成（边沿触发，之后 passed=true）。
     * deviation=null（不可判定）按偏差过大处理但不立刻清零（容 1 帧缺口）。
     */
    fun update(deviation: Float?): Boolean {
        if (passed) return false
        when {
            deviation != null && deviation < tolerance -> {
                hold++
                if (hold >= holdFrames) {
                    passed = true
                    return true
                }
            }
            deviation != null && deviation < tolerance * 1.2f -> Unit // 滞回带：保持不清零
            hold > 0 && deviation == null && hold < holdFrames -> hold-- // 缺帧缓冲
            else -> hold = 0
        }
        return false
    }

    fun reset() {
        passed = false
        hold = 0
    }
}
