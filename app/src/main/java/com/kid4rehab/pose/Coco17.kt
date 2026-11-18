package com.kid4rehab.pose
//这个主要是提供了coco17个关键点的索引和定义后续方便实现渲染
/** COCO17 关键点索引、骨架连边与产出契约类型（推理层 → 消费方的 seam）。 */
object Coco17 {
    const val NOSE = 0
    const val LEFT_EYE = 1
    const val RIGHT_EYE = 2
    const val LEFT_EAR = 3
    const val RIGHT_EAR = 4
    const val LEFT_SHOULDER = 5
    const val RIGHT_SHOULDER = 6
    const val LEFT_ELBOW = 7
    const val RIGHT_ELBOW = 8
    const val LEFT_WRIST = 9
    const val RIGHT_WRIST = 10
    const val LEFT_HIP = 11
    const val RIGHT_HIP = 12
    const val LEFT_KNEE = 13
    const val RIGHT_KNEE = 14
    const val LEFT_ANKLE = 15
    const val RIGHT_ANKLE = 16

    const val COUNT = 17

    /** 左右成对点的互换索引（鼻=自身）：归一化的镜像翻回、比对的左右互换共用 */
    val MIRROR_OF: IntArray = IntArray(COUNT) { i ->
        when (i) {
            LEFT_EYE -> RIGHT_EYE; RIGHT_EYE -> LEFT_EYE
            LEFT_EAR -> RIGHT_EAR; RIGHT_EAR -> LEFT_EAR
            LEFT_SHOULDER -> RIGHT_SHOULDER; RIGHT_SHOULDER -> LEFT_SHOULDER
            LEFT_ELBOW -> RIGHT_ELBOW; RIGHT_ELBOW -> LEFT_ELBOW
            LEFT_WRIST -> RIGHT_WRIST; RIGHT_WRIST -> LEFT_WRIST
            LEFT_HIP -> RIGHT_HIP; RIGHT_HIP -> LEFT_HIP
            LEFT_KNEE -> RIGHT_KNEE; RIGHT_KNEE -> LEFT_KNEE
            LEFT_ANKLE -> RIGHT_ANKLE; RIGHT_ANKLE -> LEFT_ANKLE
            else -> i // NOSE
        }
    }

    /** COCO17 点名（按索引） */
    val NAMES: List<String> = listOf(
        "nose", "left_eye", "right_eye", "left_ear", "right_ear",
        "left_shoulder", "right_shoulder", "left_elbow", "right_elbow",
        "left_wrist", "right_wrist", "left_hip", "right_hip",
        "left_knee", "right_knee", "left_ankle", "right_ankle",
    )

    /** 骨架绘制连边（COCO 官方连边集） */
    val EDGES: List<Pair<Int, Int>> = listOf(
        NOSE to LEFT_EYE, NOSE to RIGHT_EYE,
        LEFT_EYE to LEFT_EAR, RIGHT_EYE to RIGHT_EAR,
        LEFT_SHOULDER to RIGHT_SHOULDER,
        LEFT_SHOULDER to LEFT_ELBOW, LEFT_ELBOW to LEFT_WRIST,
        RIGHT_SHOULDER to RIGHT_ELBOW, RIGHT_ELBOW to RIGHT_WRIST,
        LEFT_SHOULDER to LEFT_HIP, RIGHT_SHOULDER to RIGHT_HIP, LEFT_HIP to RIGHT_HIP,
        LEFT_HIP to LEFT_KNEE, LEFT_KNEE to LEFT_ANKLE,
        RIGHT_HIP to RIGHT_KNEE, RIGHT_KNEE to RIGHT_ANKLE,
    )
}

/**
 * 一帧的姿态识别结果：推理层的全部输出（「一帧 → 17 关键点 + 置信度 + 时间戳」）。
 *
 * @param timestampMs 帧时间戳（相机为采集时刻，开机单调时钟）
 * @param score 人体检测置信度
 * @param keypoints [17*3] 的 (x, y, conf)，坐标为来源帧像素坐标；低置信点由消费方按需剔除
 */
data class PoseFrame(
    val timestampMs: Long,
    val score: Float,
    val keypoints: FloatArray,
) {
    init {
        require(keypoints.size == Coco17.COUNT * 3) { "keypoints 必须是 17*3，实际 ${keypoints.size}" }
    }

    fun x(index: Int): Float = keypoints[index * 3]

    fun y(index: Int): Float = keypoints[index * 3 + 1]

    fun conf(index: Int): Float = keypoints[index * 3 + 2]
}
