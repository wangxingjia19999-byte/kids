package com.kid4rehab.pose

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 关键点归一化（四点对齐的核心，移植自 recover4kids judge/Points.kt 并精简）。
 *
 * 归一化坐标系：原点 = 髋中点，单位 = 尺度（躯干长为主、肩宽/髋宽兜底），
 * +y 向上（图像 y 翻转），朝向统一 +x（鼻尖在 +x 侧）；可选再把躯干转正到 +y
 * （体前屈等躯干倾角本身是动作信息的场合不要开）。前置相机的镜像差异由此被吸收，
 * 模板与实时骨架的比对因此镜像不变、身材/距离/站位无关。
 *
 * 结构分两层（迟滞和平滑都需要上一帧信息，纯函数拿不到）：
 * - [PoseNormalize]：无状态纯函数，[normalize] 收 [NormState]、返回新 state，
 *   单测与离线模板处理直接调；
 * - [PoseNormalizer]：有状态封装，每个录制/训练会话一个实例，逐帧推进 state。
 *
 * 稳定性设计（对应旧实现的五个隐患）：
 * 1. 朝向：用归一化鼻尖偏移 d 判定 + 死区/迟滞（死区内沿用上一帧）；正面视角
 *    （肩宽/躯干长比值大）固定 sgn=+1 不翻转——鼻尖在髋中正上方抖动不再整帧翻x。
 * 2. 尺度：max(躯干长, 肩宽×k, 髋宽×k) 兜底前倾/坐姿的躯干投影缩短；EMA 只平滑
 *    无量纲修正因子 c（像素尺度随人远近实时变，不能平滑）；退化阈值是相对量
 *    （躯干长 < 帧高 3%）。
 * 3. 中点：双侧置信门带迟滞（≥ENTER 进入、<EXIT 退出），单侧丢失时用缓存的
 *    左右偏移外推另一侧——退化瞬间中点连续，不跳半个髋宽。
 * 4. 镜像：入口显式处理（mirrored=true 先 x 翻回 + 左右索引互换），左右标签
 *    恢复解剖学含义（真实左手=LEFT_*），前后置摄像头、录/训两侧口径一致。
 */
object PoseNormalize {

    const val MIN_CONF = 0.3f      // 低置信点不参与归一化（朝向不可判等）

    /** 双侧置信门迟滞：≥ ENTER 视为可用，已可用则 < EXIT 才退出 */
    const val CONF_ENTER = 0.35f
    const val CONF_EXIT = 0.25f

    /** 朝向死区：|d|（鼻尖水平偏移/尺度）小于此值沿用上一帧朝向 */
    const val FACING_DEAD_ZONE = 0.15f

    /** 正面/侧面判别（肩宽/躯干长）迟滞：≥ ENTER 判正面（固定 sgn=+1），< EXIT 判侧面 */
    const val FRONTAL_RATIO_ENTER = 0.5f
    const val FRONTAL_RATIO_EXIT = 0.45f

    /** 尺度兜底系数：直立时躯干长应仍是三者最大（与旧模板单位兼容），前倾时肩/髋顶上 */
    const val K_SHOULDER = 1.0f
    const val K_HIP = 1.15f

    /** 尺度修正因子 c 与旋转角 φ 的 EMA 新帧权重 */
    const val SCALE_EMA_ALPHA = 0.25f
    const val ROTATION_EMA_ALPHA = 0.3f

    /** 躯干长低于帧高的此比例视为退化（人太远/太小），返回失败 */
    const val MIN_TORSO_OF_HEIGHT = 0.03f

    /** 单侧丢失后左右偏移缓存的有效帧数（约 1s @15fps），过期不再外推另一侧 */
    const val OFFSET_MAX_AGE_FRAMES = 15

    /** 四锚点索引：左右肩 + 左右髋（模板对齐/训练门控用） */
    val ANCHORS = intArrayOf(
        Coco17.LEFT_SHOULDER, Coco17.RIGHT_SHOULDER,
        Coco17.LEFT_HIP, Coco17.RIGHT_HIP,
    )

    /**
     * 归一化一帧（17×3 像素坐标，预览像素空间）。
     *
     * @param frameWidth/frameHeight 来源帧尺寸（退化判别用相对量；mirrored 翻 x 需要 width）
     * @param mirrored 前置镜像帧传 true：先翻回真实空间并互换左右索引
     * @param rotate 可选旋转归一化（躯干转正到 +y）；躯干倾角是动作信息时保持 false
     * @param state 上一帧返回的 nextState；null 等价全新会话
     * @return [NormResult.Fail] 带原因（髋不可见/肩不可见/躯干退化/朝向不明），UI 可据此提示
     */
    fun normalize(
        keypoints: FloatArray,
        frameWidth: Int,
        frameHeight: Int,
        mirrored: Boolean = false,
        rotate: Boolean = false,
        state: NormState? = null,
    ): NormResult {
        val st = state ?: NormState()
        // ① 镜像显式处理：翻回真实空间 + 左右索引恢复解剖学标签
        val kp = if (mirrored) unmirror(keypoints, frameWidth) else keypoints

        // ② 门限迟滞推进 + 偏移缓存老化（失败帧也推进，迟滞/外推跨失效帧仍有效）
        var next = st.copy(
            gLSho = gate(st.gLSho, kp, Coco17.LEFT_SHOULDER),
            gRSho = gate(st.gRSho, kp, Coco17.RIGHT_SHOULDER),
            gLHip = gate(st.gLHip, kp, Coco17.LEFT_HIP),
            gRHip = gate(st.gRHip, kp, Coco17.RIGHT_HIP),
            shOffAge = if (st.hasShOffset) st.shOffAge + 1 else NormState.NO_OFFSET,
            hipOffAge = if (st.hasHipOffset) st.hipOffAge + 1 else NormState.NO_OFFSET,
        )

        // ③ 髋中/肩中：双侧置信门 + 单侧丢失时偏移缓存外推（连续退化，不跳变）
        val hip = pairMid(
            kp, Coco17.LEFT_HIP, Coco17.RIGHT_HIP,
            next.gLHip, next.gRHip,
            next.hasHipOffset, next.hipOffX, next.hipOffY, next.hipOffAge,
        ) ?: return NormResult.Fail(NormFail.HIPS_HIDDEN, next)
        val sho = pairMid(
            kp, Coco17.LEFT_SHOULDER, Coco17.RIGHT_SHOULDER,
            next.gLSho, next.gRSho,
            next.hasShOffset, next.shOffX, next.shOffY, next.shOffAge,
        ) ?: return NormResult.Fail(NormFail.SHOULDERS_HIDDEN, next)

        // 双侧可用时刷新左右偏移缓存（下一帧单侧丢失外推用）
        if (hip.both) {
            next = next.copy(
                hasHipOffset = true, hipOffAge = 0,
                hipOffX = kp[Coco17.LEFT_HIP * 3] - kp[Coco17.RIGHT_HIP * 3],
                hipOffY = kp[Coco17.LEFT_HIP * 3 + 1] - kp[Coco17.RIGHT_HIP * 3 + 1],
            )
        }
        if (sho.both) {
            next = next.copy(
                hasShOffset = true, shOffAge = 0,
                shOffX = kp[Coco17.LEFT_SHOULDER * 3] - kp[Coco17.RIGHT_SHOULDER * 3],
                shOffY = kp[Coco17.LEFT_SHOULDER * 3 + 1] - kp[Coco17.RIGHT_SHOULDER * 3 + 1],
            )
        }

        // ④ 躯干长（相对量退化判别：人太小/太远时关键点质量差，别硬归一化）
        val torso = hypot(sho.x - hip.x, sho.y - hip.y)
        if (!torso.isFinite() || torso < MIN_TORSO_OF_HEIGHT * frameHeight) {
            return NormResult.Fail(NormFail.TORSO_TOO_SMALL, next)
        }

        // ⑤ 尺度：c = max(1, k·肩宽/躯干, k·髋宽/躯干) ≥ 1，scale = 躯干长 × c。
        // EMA 只平滑 c：像素尺度随人走近走远实时变（平滑它会有滞后假差），
        // c 只随体型/视角变化（秒级不变），平滑安全。直立时 c=1 → 单位与旧模板一致。
        val shW = pairWidth(kp, Coco17.LEFT_SHOULDER, Coco17.RIGHT_SHOULDER, next.gLSho, next.gRSho, next.hasShOffset, next.shOffX, next.shOffY, next.shOffAge)
        val hipW = pairWidth(kp, Coco17.LEFT_HIP, Coco17.RIGHT_HIP, next.gLHip, next.gRHip, next.hasHipOffset, next.hipOffX, next.hipOffY, next.hipOffAge)
        val c = maxOf(
            1f,
            shW?.let { K_SHOULDER * it / torso } ?: 0f,
            hipW?.let { K_HIP * it / torso } ?: 0f,
        )
        val cSmooth = st.scaleCorr?.let { it + SCALE_EMA_ALPHA * (c - it) } ?: c
        val scale = torso * cSmooth

        // ⑥ 朝向：正面（肩宽/躯干比值大）固定 sgn=+1；侧面用 d，死区内沿用上一帧
        val frontal = frontalHysteresis(shW?.let { it / torso }, st.frontal)
        next = next.copy(frontal = frontal, scaleCorr = cSmooth)
        val sgn = facingSgn(kp, hip, scale, frontal, st.sgn)
            ?: return NormResult.Fail(NormFail.FACING_UNKNOWN, next)

        // ⑦ 旋转角（可选）：肩中→髋中向量在 y 翻转空间里与 +y 的夹角，EMA 平滑
        val phiRaw = atan2(sho.x - hip.x, hip.y - sho.y)
        val prevPhi = st.phi
        val phi = when {
            !rotate -> 0f
            prevPhi == null -> phiRaw
            else -> prevPhi + ROTATION_EMA_ALPHA * wrapAngle(phiRaw - prevPhi)
        }

        // ⑧ 归一化 17 点：n = F·R(φ)·Y·(p−o)/scale（Y=y 翻转、F=朝向 x 翻转）
        val cosP = cos(phi)
        val sinP = sin(phi)
        val out = FloatArray(Coco17.COUNT * 3)
        for (i in 0 until Coco17.COUNT) {
            val dx = kp[i * 3] - hip.x
            val dy = kp[i * 3 + 1] - hip.y
            out[i * 3] = sgn * (cosP * dx + sinP * dy) / scale
            out[i * 3 + 1] = (sinP * dx - cosP * dy) / scale
            out[i * 3 + 2] = kp[i * 3 + 2]
        }

        // 逆仿射（归一化→本帧像素）：p = o + scale·Y·R(−φ)·F·n；镜像帧再复合 x 翻回
        val aff = floatArrayOf(
            scale * cosP * sgn, scale * sinP, hip.x,
            scale * sinP * sgn, -scale * cosP, hip.y,
        )
        if (mirrored) {
            aff[0] = -aff[0]
            aff[1] = -aff[1]
            aff[2] = (frameWidth - 1) - aff[2]
        }

        return NormResult.Ok(
            NormFrame(out, sgn > 0, scale, aff),
            next.copy(sgn = sgn, phi = phi),
        )
    }

    /** 前置镜像帧 → 真实空间：x 翻回 + 左右索引互换（模型按外观打标，镜像帧里左右是反的） */
    private fun unmirror(kp: FloatArray, frameWidth: Int): FloatArray {
        val out = FloatArray(Coco17.COUNT * 3)
        for (i in 0 until Coco17.COUNT) {
            val j = Coco17.MIRROR_OF[i]
            out[i * 3] = (frameWidth - 1) - kp[j * 3]
            out[i * 3 + 1] = kp[j * 3 + 1]
            out[i * 3 + 2] = kp[j * 3 + 2]
        }
        return out
    }

    /** 置信门（迟滞）：≥ENTER 进入；已可用则 <EXIT 才退出 */
    private fun gate(active: Boolean, kp: FloatArray, idx: Int): Boolean {
        val c = kp[idx * 3 + 2]
        return c >= CONF_ENTER || (active && c >= CONF_EXIT)
    }

    /**
     * 左右点对中点：双侧可用取真中点；单侧可用时用缓存的左右偏移外推
     * （off = 左−右，中点 = 左 − off/2 = 右 + off/2，与上一帧真中点无缝衔接）；
     * 缓存过期退化为该侧；双侧退出返回 null。
     */
    private fun pairMid(
        kp: FloatArray, l: Int, r: Int,
        gateL: Boolean, gateR: Boolean,
        hasOff: Boolean, offX: Float, offY: Float, offAge: Int,
    ): Mid? {
        val fresh = hasOff && offAge < OFFSET_MAX_AGE_FRAMES
        return when {
            gateL && gateR -> Mid(
                (kp[l * 3] + kp[r * 3]) / 2f,
                (kp[l * 3 + 1] + kp[r * 3 + 1]) / 2f,
                both = true,
            )
            gateL && fresh -> Mid(kp[l * 3] - offX / 2f, kp[l * 3 + 1] - offY / 2f, both = false)
            gateR && fresh -> Mid(kp[r * 3] + offX / 2f, kp[r * 3 + 1] + offY / 2f, both = false)
            gateL -> Mid(kp[l * 3], kp[l * 3 + 1], both = false)
            gateR -> Mid(kp[r * 3], kp[r * 3 + 1], both = false)
            else -> null
        }
    }

    /** 左右点对间距（尺度兜底用）：双侧取真距离；单侧用缓存的左右偏移模长；都不可用返回 null */
    private fun pairWidth(
        kp: FloatArray, l: Int, r: Int,
        gateL: Boolean, gateR: Boolean,
        hasOff: Boolean, offX: Float, offY: Float, offAge: Int,
    ): Float? = when {
        gateL && gateR -> hypot(kp[l * 3] - kp[r * 3], kp[l * 3 + 1] - kp[r * 3 + 1])
        hasOff && offAge < OFFSET_MAX_AGE_FRAMES -> hypot(offX, offY)
        else -> null
    }

    /** 正面/侧面判别迟滞：比值在 [EXIT, ENTER) 带内沿用上一帧判定 */
    private fun frontalHysteresis(ratio: Float?, prev: Boolean?): Boolean? {
        val r = ratio ?: return prev
        return when {
            r >= FRONTAL_RATIO_ENTER -> true
            r < FRONTAL_RATIO_EXIT -> false
            else -> prev
        }
    }

    /**
     * 朝向 sgn：正面固定 +1；侧面用 d = 鼻尖水平偏移/尺度，|d| 小于死区沿用上一帧；
     * 鼻/眼均低置信且无上一帧 → null（朝向不明）。
     */
    private fun facingSgn(kp: FloatArray, hip: Mid, scale: Float, frontal: Boolean?, prev: Int?): Int? {
        if (frontal == true) return 1
        val faceX = faceX(kp) ?: return prev
        val d = (faceX - hip.x) / scale
        if (abs(d) < FACING_DEAD_ZONE) return prev
        return if (d > 0f) 1 else -1
    }

    /** 鼻尖（低置信退到眼中点/单眼）的 x；均低置信返回 null */
    private fun faceX(kp: FloatArray): Float? {
        if (kp[Coco17.NOSE * 3 + 2] >= MIN_CONF) return kp[Coco17.NOSE * 3]
        val le = kp[Coco17.LEFT_EYE * 3 + 2]
        val re = kp[Coco17.RIGHT_EYE * 3 + 2]
        return when {
            le >= MIN_CONF && re >= MIN_CONF ->
                (kp[Coco17.LEFT_EYE * 3] + kp[Coco17.RIGHT_EYE * 3]) / 2f
            le >= MIN_CONF -> kp[Coco17.LEFT_EYE * 3]
            re >= MIN_CONF -> kp[Coco17.RIGHT_EYE * 3]
            else -> null
        }
    }

    /** 角度差归一到 (−π, π]（旋转 EMA 跨 ±π 用） */
    internal fun wrapAngle(diff: Float): Float {
        val twoPi = 2f * PI.toFloat()
        return ((diff + PI.toFloat()) % twoPi + twoPi) % twoPi - PI.toFloat()
    }

    /** 点对中点（both = 本帧双侧都可用） */
    private class Mid(val x: Float, val y: Float, val both: Boolean)
}

/** 归一化失败原因（UI 可据此给出针对性提示） */
enum class NormFail { HIPS_HIDDEN, SHOULDERS_HIDDEN, TORSO_TOO_SMALL, FACING_UNKNOWN }

/**
 * 归一化结果：成功带 [NormFrame]，失败带原因；两者都带 nextState
 * （失败帧也推进门限/老化，迟滞与偏移缓存跨失效帧仍有效）。
 */
sealed interface NormResult {
    val nextState: NormState

    data class Ok(val frame: NormFrame, override val nextState: NormState) : NormResult

    data class Fail(val reason: NormFail, override val nextState: NormState) : NormResult
}

/** 成功帧；失败为 null */
val NormResult.frame: NormFrame? get() = (this as? NormResult.Ok)?.frame

/** 失败原因；成功为 null */
val NormResult.reason: NormFail? get() = (this as? NormResult.Fail)?.reason

/**
 * 归一化会话状态（帧间迟滞与平滑，[PoseNormalize.normalize] 显式传入/返回）：
 * 上一帧朝向与正面判定、尺度修正 EMA、旋转角 EMA、左右偏移缓存与双侧置信门。
 * 全标量、不可变，data class copy 语义安全。
 */
data class NormState(
    /** 上一帧朝向（+1/-1）；死区内沿用 */
    val sgn: Int? = null,
    /** 上一帧正面/侧面判定；判别带内沿用 */
    val frontal: Boolean? = null,
    /** 平滑后的尺度修正因子 c（≥1） */
    val scaleCorr: Float? = null,
    /** 平滑后的躯干转正角（弧度）；rotate=false 时恒 0。null=尚未初始化 */
    val phi: Float? = null,
    /** 肩/髋左右偏移缓存（像素，真实空间，左−右）；hasX=false 表示无缓存 */
    val hasShOffset: Boolean = false,
    val shOffX: Float = 0f,
    val shOffY: Float = 0f,
    val shOffAge: Int = NO_OFFSET,
    val hasHipOffset: Boolean = false,
    val hipOffX: Float = 0f,
    val hipOffY: Float = 0f,
    val hipOffAge: Int = NO_OFFSET,
    /** 四锚点双侧置信门（迟滞后的可用状态） */
    val gLSho: Boolean = false,
    val gRSho: Boolean = false,
    val gLHip: Boolean = false,
    val gRHip: Boolean = false,
) {
    companion object {
        /** 无偏移缓存时的 age（≥ OFFSET_MAX_AGE_FRAMES 即视为过期） */
        const val NO_OFFSET = PoseNormalize.OFFSET_MAX_AGE_FRAMES
    }
}

/**
 * 有状态归一化器：一个录制/训练会话一个实例，持有上一帧朝向、平滑尺度、
 * 左右偏移缓存。切换镜头不必 reset——镜像在入口已翻回，状态空间与镜头无关。
 */
class PoseNormalizer(private val rotate: Boolean = false) {
    private var state = NormState()

    fun normalize(
        keypoints: FloatArray,
        frameWidth: Int,
        frameHeight: Int,
        mirrored: Boolean,
    ): NormResult {
        val r = PoseNormalize.normalize(keypoints, frameWidth, frameHeight, mirrored, rotate, state)
        state = r.nextState
        return r
    }

    fun reset() {
        state = NormState()
    }
}

/**
 * 归一化帧：17 点（[x,y,conf]，单位=尺度）+ 归一化→本帧像素的 2×3 仿射
 * （含镜像/朝向/旋转/尺度/原点全部信息，ghost 绘制逆变换用：
 * 模板姿态贴到真人身上、实时跟随；加旋转时接口不变，只换矩阵内容）。
 */
class NormFrame internal constructor(
    private val pts: FloatArray,
    val facingRight: Boolean,
    /** 本帧尺度（像素）＝躯干长×修正因子（调试/诊断用） */
    val scalePx: Float,
    private val aff: FloatArray,
) {
    fun x(index: Int): Float = pts[index * 3]

    fun y(index: Int): Float = pts[index * 3 + 1]

    fun conf(index: Int): Float = pts[index * 3 + 2]

    /** 归一化 17 点快照（模板存储 / 比对用） */
    fun snapshot(): FloatArray = pts.copyOf()

    /** 四锚点是否全部可用（对齐门控） */
    fun anchorsValid(): Boolean = PoseNormalize.ANCHORS.all { conf(it) >= PoseNormalize.MIN_CONF }

    /** 归一化 → 本帧像素 x（旋转开启时与 ny 有关；接口留双参数，加旋转不再改签名） */
    fun toPixelX(nx: Float, ny: Float): Float = aff[0] * nx + aff[1] * ny + aff[2]

    /** 归一化 → 本帧像素 y */
    fun toPixelY(nx: Float, ny: Float): Float = aff[3] * nx + aff[4] * ny + aff[5]
}
