package com.kid4rehab.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PoseNormalize 纯函数单测（无 Android 依赖，JVM 直跑）。
 * 合成帧约定：320×240，正面姿态肩宽/躯干=40/60=0.67（判正面），
 * 侧面姿态=8/60=0.13（判侧面）。
 */
class PoseNormalizeTest {

    private val W = 320
    private val H = 240

    /** 正面站姿：肩中/髋中 (160,120)/(160,180)，躯干 60，肩宽 40，鼻 (160,95) */
    private fun frontalPose(noseX: Float = 160f, noseConf: Float = 0.9f): FloatArray =
        kp(
            Coco17.NOSE to floatArrayOf(noseX, 95f, noseConf),
            Coco17.LEFT_SHOULDER to floatArrayOf(180f, 120f),
            Coco17.RIGHT_SHOULDER to floatArrayOf(140f, 120f),
            Coco17.LEFT_HIP to floatArrayOf(172f, 180f),
            Coco17.RIGHT_HIP to floatArrayOf(148f, 180f),
        )

    /** 侧面站姿：肩宽 8（判侧面），鼻尖 x 可调（d = (noseX-160)/60） */
    private fun sidePose(noseX: Float = 190f, noseConf: Float = 0.9f): FloatArray =
        kp(
            Coco17.NOSE to floatArrayOf(noseX, 100f, noseConf),
            Coco17.LEFT_SHOULDER to floatArrayOf(164f, 120f),
            Coco17.RIGHT_SHOULDER to floatArrayOf(156f, 120f),
            Coco17.LEFT_HIP to floatArrayOf(164f, 180f),
            Coco17.RIGHT_HIP to floatArrayOf(156f, 180f),
        )

    /** 构造 17×3：未指定的点默认 (0,0) 高置信（不参与锚点计算） */
    private fun kp(vararg pts: Pair<Int, FloatArray>): FloatArray {
        val out = FloatArray(Coco17.COUNT * 3)
        for (i in 0 until Coco17.COUNT) out[i * 3 + 2] = 0.9f
        for ((i, p) in pts) {
            out[i * 3] = p[0]
            out[i * 3 + 1] = p[1]
            if (p.size > 2) out[i * 3 + 2] = p[2]
        }
        return out
    }

    private fun ok(result: NormResult): NormResult.Ok = result as NormResult.Ok

    private fun fail(result: NormResult): NormResult.Fail = result as NormResult.Fail

    // ---------- 基础：归一化 ↔ 像素往返 ----------

    @Test
    fun `pixel roundtrip`() {
        val r = ok(PoseNormalize.normalize(frontalPose(), W, H))
        val f = r.frame
        for (i in intArrayOf(Coco17.NOSE, Coco17.LEFT_SHOULDER, Coco17.RIGHT_HIP)) {
            val kp = frontalPose()
            val px = f.toPixelX(f.x(i), f.y(i))
            val py = f.toPixelY(f.x(i), f.y(i))
            assertEquals(kp[i * 3], px, 1e-3f)
            assertEquals(kp[i * 3 + 1], py, 1e-3f)
        }
    }

    @Test
    fun `origin and unit`() {
        // 正面直立：c=1 → 尺度=躯干长 60；髋中为原点；肩中在 +y 一个单位处
        val f = ok(PoseNormalize.normalize(frontalPose(), W, H)).frame
        assertEquals(60f, f.scalePx, 1e-3f)
        assertEquals(0f, f.x(Coco17.LEFT_HIP) + f.x(Coco17.RIGHT_HIP), 1e-3f)
        assertEquals(0f, f.y(Coco17.LEFT_HIP) + f.y(Coco17.RIGHT_HIP), 1e-3f)
        assertEquals(1f, (f.y(Coco17.LEFT_SHOULDER) + f.y(Coco17.RIGHT_SHOULDER)) / 2f, 1e-3f)
    }

    // ---------- 隐患 1：正面朝向不抖动 ----------

    @Test
    fun `frontal nose jitter does not flip facing`() {
        // 旧实现：鼻尖 x 在髋中附近 ±0.5px 抖动 → sgn 逐帧翻转、整帧 x 取反
        var state: NormState? = null
        var lastX = 0f
        repeat(10) { i ->
            val noseX = 160f + if (i % 2 == 0) 0.5f else -0.5f
            val r = ok(PoseNormalize.normalize(frontalPose(noseX), W, H, state = state))
            state = r.nextState
            assertTrue(r.frame.facingRight) // sgn 恒 +1
            val nx = r.frame.x(Coco17.LEFT_SHOULDER)
            if (i > 0) assertEquals(lastX, nx, 1e-4f) // 左肩归一化 x 不翻
            lastX = nx
        }
    }

    @Test
    fun `side facing dead zone holds previous sgn`() {
        var state: NormState? = null
        // d=+0.5 → +1
        var r = ok(PoseNormalize.normalize(sidePose(noseX = 190f), W, H, state = state))
        state = r.nextState
        assertTrue(r.frame.facingRight)
        // d=+0.05（死区内）→ 沿用 +1
        r = ok(PoseNormalize.normalize(sidePose(noseX = 163f), W, H, state = state))
        state = r.nextState
        assertTrue(r.frame.facingRight)
        // d=-0.5 → 翻 -1
        r = ok(PoseNormalize.normalize(sidePose(noseX = 130f), W, H, state = state))
        state = r.nextState
        assertTrue(!r.frame.facingRight)
        // d=-0.03（死区内）→ 沿用 -1
        r = ok(PoseNormalize.normalize(sidePose(noseX = 158f), W, H, state = state))
        assertTrue(!r.frame.facingRight)
    }

    // ---------- 隐患 2：尺度兜底（前倾时肩宽顶上） ----------

    @Test
    fun `scale falls back to shoulder width when torso shrinks`() {
        // 前倾：躯干投影 60→30，肩宽不变 40 → c=40/30，首帧无 EMA → 尺度=40
        val bent = kp(
            Coco17.NOSE to floatArrayOf(160f, 130f),
            Coco17.LEFT_SHOULDER to floatArrayOf(180f, 150f),
            Coco17.RIGHT_SHOULDER to floatArrayOf(140f, 150f),
            Coco17.LEFT_HIP to floatArrayOf(172f, 180f),
            Coco17.RIGHT_HIP to floatArrayOf(148f, 180f),
        )
        val r = ok(PoseNormalize.normalize(bent, W, H))
        assertEquals(40f, r.frame.scalePx, 1e-2f)
        assertEquals(40f / 30f, r.nextState.scaleCorr!!, 1e-3f)
    }

    @Test
    fun `upright pose keeps torso unit (template compat)`() {
        // 直立肩宽 40 < 躯干 60 → c=1，单位与旧模板一致
        val r = ok(PoseNormalize.normalize(frontalPose(), W, H))
        assertEquals(1f, r.nextState.scaleCorr!!, 1e-4f)
    }

    // ---------- 隐患 3：单侧退化中点连续 ----------

    @Test
    fun `single hip loss keeps midpoint via offset cache`() {
        val r1 = PoseNormalize.normalize(frontalPose(), W, H)
        val f1 = ok(r1).frame
        // 右髋掉到 0.1（< EXIT 0.25 退出置信门）→ 用左髋 − 缓存偏移/2 外推
        val one = kp(
            Coco17.NOSE to floatArrayOf(160f, 95f),
            Coco17.LEFT_SHOULDER to floatArrayOf(180f, 120f),
            Coco17.RIGHT_SHOULDER to floatArrayOf(140f, 120f),
            Coco17.LEFT_HIP to floatArrayOf(172f, 180f),
            Coco17.RIGHT_HIP to floatArrayOf(148f, 180f, 0.1f),
        )
        val f2 = ok(PoseNormalize.normalize(one, W, H, state = r1.nextState)).frame
        // 静止姿态：髋中被外推回原位 → 归一化坐标完全一致（conf 除外，右侧确实丢了）
        for (i in 0 until Coco17.COUNT) {
            assertEquals(f1.x(i), f2.x(i), 1e-4f)
            assertEquals(f1.y(i), f2.y(i), 1e-4f)
        }
    }

    @Test
    fun `stale offset falls back to visible side`() {
        var state: NormState? = null
        val one = kp(
            Coco17.LEFT_SHOULDER to floatArrayOf(180f, 120f),
            Coco17.RIGHT_SHOULDER to floatArrayOf(140f, 120f),
            Coco17.LEFT_HIP to floatArrayOf(172f, 180f),
            Coco17.RIGHT_HIP to floatArrayOf(148f, 180f, 0.1f),
        )
        // 先双侧可用一帧建立缓存，再单侧丢失超过有效期
        var r = PoseNormalize.normalize(frontalPose(), W, H, state = state)
        state = r.nextState
        repeat(PoseNormalize.OFFSET_MAX_AGE_FRAMES + 1) {
            r = PoseNormalize.normalize(one, W, H, state = state)
            state = r.nextState
        }
        // 过期后退化回可见侧：原点=左髋 172（帧仍成功，只是原点平移）
        val f = ok(r).frame
        assertEquals(0f, f.x(Coco17.LEFT_HIP), 1e-3f)
        assertTrue(f.x(Coco17.NOSE) < 0f) // 鼻(160) 在原点(172) 左侧
    }

    // ---------- 隐患 4：前置镜像在入口显式处理 ----------

    @Test
    fun `mirrored front camera frame equals unmirrored world frame`() {
        val world = frontalPose()
        // 前置模型按外观打标：真实左右互换 + x 镜像 → kp'[MIRROR_OF[i]] = mirror(kp[i])
        val mirroredKp = FloatArray(Coco17.COUNT * 3)
        for (i in 0 until Coco17.COUNT) {
            val j = Coco17.MIRROR_OF[i]
            mirroredKp[j * 3] = (W - 1) - world[i * 3]
            mirroredKp[j * 3 + 1] = world[i * 3 + 1]
            mirroredKp[j * 3 + 2] = world[i * 3 + 2]
        }
        val a = ok(PoseNormalize.normalize(world, W, H, mirrored = false)).frame.snapshot()
        val b = ok(PoseNormalize.normalize(mirroredKp, W, H, mirrored = true)).frame.snapshot()
        // 解剖学标签恢复：同名点归一化坐标一致（真实左手=LEFT_*）
        a.zip(b).forEach { (x, y) -> assertEquals(x, y, 1e-4f) }

        // 逆变换落回镜像（预览）像素空间：左肩应贴回模型标 RIGHT 的位置
        val f = ok(PoseNormalize.normalize(mirroredKp, W, H, mirrored = true)).frame
        val px = f.toPixelX(f.x(Coco17.LEFT_SHOULDER), f.y(Coco17.LEFT_SHOULDER))
        assertEquals(mirroredKp[Coco17.RIGHT_SHOULDER * 3], px, 1e-3f)
    }

    // ---------- 隐患 5（可选）：旋转归一化 ----------

    @Test
    fun `rotation aligns torso to +y and roundtrips`() {
        // 身体右倾：肩中 (180,120) 偏离髋中 (160,180) 正上方 20px
        val tilted = kp(
            Coco17.NOSE to floatArrayOf(185f, 100f),
            Coco17.LEFT_SHOULDER to floatArrayOf(190f, 125f),
            Coco17.RIGHT_SHOULDER to floatArrayOf(170f, 115f),
            Coco17.LEFT_HIP to floatArrayOf(164f, 180f),
            Coco17.RIGHT_HIP to floatArrayOf(156f, 180f),
        )
        val f = ok(PoseNormalize.normalize(tilted, W, H, rotate = true)).frame
        // 肩中转正到 +y 一个单位处（c=1：肩宽 20 < 躯干 hypot(20,60)≈63.2）
        val shMy = (f.y(Coco17.LEFT_SHOULDER) + f.y(Coco17.RIGHT_SHOULDER)) / 2f
        val shMx = (f.x(Coco17.LEFT_SHOULDER) + f.x(Coco17.RIGHT_SHOULDER)) / 2f
        assertEquals(0f, shMx, 1e-3f)
        assertEquals(1f, shMy, 1e-3f)
        // 仿射往返
        assertEquals(190f, f.toPixelX(f.x(Coco17.LEFT_SHOULDER), f.y(Coco17.LEFT_SHOULDER)), 1e-2f)
        assertEquals(125f, f.toPixelY(f.x(Coco17.LEFT_SHOULDER), f.y(Coco17.LEFT_SHOULDER)), 1e-2f)
        // 默认关闭时躯干倾角保留（肩中 x 偏移 20px/63.2 ≈ 0.32 个单位，不为 0）
        val f0 = ok(PoseNormalize.normalize(tilted, W, H)).frame
        val shMx0 = (f0.x(Coco17.LEFT_SHOULDER) + f0.x(Coco17.RIGHT_SHOULDER)) / 2f
        assertTrue(shMx0 > 0.3f)
    }

    // ---------- 失败原因 ----------

    @Test
    fun `fail reasons`() {
        // 全低置信 → 髋不可见
        val none = FloatArray(Coco17.COUNT * 3) { 0.1f }
        assertEquals(NormFail.HIPS_HIDDEN, fail(PoseNormalize.normalize(none, W, H)).reason)

        // 躯干 4px < 3%×240=7.2 → 退化
        val tiny = kp(
            Coco17.LEFT_SHOULDER to floatArrayOf(162f, 176f),
            Coco17.RIGHT_SHOULDER to floatArrayOf(158f, 176f),
            Coco17.LEFT_HIP to floatArrayOf(162f, 180f),
            Coco17.RIGHT_HIP to floatArrayOf(158f, 180f),
        )
        assertEquals(NormFail.TORSO_TOO_SMALL, fail(PoseNormalize.normalize(tiny, W, H)).reason)

        // 侧面 + 鼻眼全低置信 + 无上一帧 → 朝向不明
        val noFace = sidePose(noseConf = 0.1f)
        for (i in intArrayOf(Coco17.LEFT_EYE, Coco17.RIGHT_EYE)) noFace[i * 3 + 2] = 0.1f
        assertEquals(NormFail.FACING_UNKNOWN, fail(PoseNormalize.normalize(noFace, W, H)).reason)

        // 双肩低置信 → 肩不可见（髋在）
        val noSho = kp(
            Coco17.LEFT_HIP to floatArrayOf(172f, 180f),
            Coco17.RIGHT_HIP to floatArrayOf(148f, 180f),
        )
        for (i in intArrayOf(Coco17.LEFT_SHOULDER, Coco17.RIGHT_SHOULDER)) noSho[i * 3 + 2] = 0.1f
        assertEquals(NormFail.SHOULDERS_HIDDEN, fail(PoseNormalize.normalize(noSho, W, H)).reason)
    }

    @Test
    fun `stateless result carries nextState on failure`() {
        val none = FloatArray(Coco17.COUNT * 3) { 0.1f }
        val r = fail(PoseNormalize.normalize(none, W, H, state = NormState(gLHip = true)))
        // 失败帧也推进门限：右髋 0.1 不曾进入门 → false；左髋 0.1 < EXIT 退出 → false
        assertEquals(false, r.nextState.gLHip)
        assertEquals(false, r.nextState.gRHip)
    }

    // ---------- 有状态封装 ----------

    @Test
    fun `pose normalizer threads state and reset clears it`() {
        val n = PoseNormalizer()
        assertTrue(ok(n.normalize(frontalPose(), W, H, mirrored = false)).frame.facingRight)
        assertTrue(ok(n.normalize(frontalPose(noseX = 160.5f), W, H, mirrored = false)).frame.facingRight)

        // reset 清掉朝向状态：侧面 + 无面部 → 回到朝向不明
        n.reset()
        val noFace = sidePose(noseConf = 0.1f)
        for (i in intArrayOf(Coco17.LEFT_EYE, Coco17.RIGHT_EYE)) noFace[i * 3 + 2] = 0.1f
        val r = fail(n.normalize(noFace, W, H, mirrored = false))
        assertEquals(NormFail.FACING_UNKNOWN, r.reason)
        assertNotNull(r.nextState) // 失败也返回可继续的状态
    }
}
