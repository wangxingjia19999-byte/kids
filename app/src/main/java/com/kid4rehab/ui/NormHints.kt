package com.kid4rehab.ui

import com.kid4rehab.pose.NormFail

/**
 * 归一化失败原因 → 用户可理解的提示（录制/训练两页共用；
 * 代替旧版"统一返回 null"，UI 能针对性引导"靠近一点"而不是笼统"请正对镜头"）。
 */
fun NormFail.displayText(): String = when (this) {
    NormFail.HIPS_HIDDEN -> "髋部不可见\n请全身入画、避免衣物遮挡"
    NormFail.SHOULDERS_HIDDEN -> "肩部不可见\n请正对镜头、避免遮挡"
    NormFail.TORSO_TOO_SMALL -> "人太小或太远\n请靠近镜头一些"
    NormFail.FACING_UNKNOWN -> "朝向不明\n请让面部朝向镜头"
}
