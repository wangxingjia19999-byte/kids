package com.kid4rehab

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.kid4rehab.pose.Coco17
import com.kid4rehab.pose.NormFrame
import com.kid4rehab.pose.PoseFrame
import com.kid4rehab.pose.Roi

/**
 * 实时完整骨架连线绘制：17 关键点与骨骼连线，按置信度着色（高置信绿，低置信青/橙）。
 * 分析帧像素坐标 → 画布坐标（内容盒与分析帧同比例，双向拉伸即等比）。
 */
fun DrawScope.drawLiveSkeleton(
    pose: PoseFrame,
    frameWidth: Int,
    frameHeight: Int,
) {
    val scaleX = size.width / frameWidth
    val scaleY = size.height / frameHeight
    fun offset(i: Int) = Offset(pose.x(i) * scaleX, pose.y(i) * scaleY)

    for ((a, b) in Coco17.EDGES) {
        val confA = pose.conf(a)
        val confB = pose.conf(b)
        if (confA >= 0.25f && confB >= 0.25f) {
            val bothHigh = confA >= 0.5f && confB >= 0.5f
            drawLine(
                color = if (bothHigh) Color(0xFF30D158) else Color(0xAA00E5FF),
                start = offset(a),
                end = offset(b),
                strokeWidth = 3.dp.toPx(),
            )
        }
    }
    for (i in 0 until Coco17.COUNT) {
        val c = pose.conf(i)
        if (c >= 0.25f) {
            drawCircle(
                color = if (c >= 0.5f) Color(0xFF30D158) else Color(0xFFFF9500),
                radius = 5.dp.toPx(),
                center = offset(i),
            )
        }
    }
}

/**
 * 识别框绘制（框模式）：程序绘制的取景框 = 送检区域（长方形、比例与采集画幅一致、
 * 占画面 92%）；圆角描边 + 四角刻线。无人时琥珀色提示进框，有人后淡绿。
 */
fun DrawScope.drawRoiBox(roi: Roi, frameWidth: Int, frameHeight: Int, color: Color) {
    val scaleX = size.width / frameWidth
    val scaleY = size.height / frameHeight
    val left = roi.x * scaleX
    val top = roi.y * scaleY
    val rectW = roi.w * scaleX
    val rectH = roi.h * scaleY
    val stroke = 3.dp.toPx()

    drawRoundRect(
        color = color,
        topLeft = Offset(left, top),
        size = Size(rectW, rectH),
        cornerRadius = CornerRadius(12.dp.toPx()),
        style = Stroke(width = stroke),
    )
    // 四角刻线（取景器样式）
    val t = 16.dp.toPx()
    val right = left + rectW
    val bottom = top + rectH
    fun tick(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = stroke)
    tick(left, top, left + t, top); tick(right, top, right - t, top)
    tick(left, bottom, left + t, bottom); tick(right, bottom, right - t, bottom)
    tick(left, top, left, top + t); tick(right, top, right, top + t)
    tick(left, bottom, left, bottom - t); tick(right, bottom, right, bottom - t)
}

/**
 * 目标姿态 ghost 骨架（模板 check 点）：虚线 + 半透明，与实时骨架区分。
 * 模板点为归一化坐标（尺度单位），经 [live] 实时帧逆变换贴到真人身上——
 * 人移动时 ghost 跟随（原点=live 髋中、尺度/朝向/镜像随 live 仿射）。
 * 低置信模板点（conf < 0.3）不绘制（录制时被遮挡的点无引导意义）。
 */
fun DrawScope.drawGhostSkeleton(
    ghostKp: FloatArray,
    live: NormFrame?,
    frameWidth: Int,
    frameHeight: Int,
    color: Color = Color(0xE6FFD60A),
) {
    if (live == null) return
    val scaleX = size.width / frameWidth
    val scaleY = size.height / frameHeight

    fun offset(i: Int): Offset? {
        if (ghostKp[i * 3 + 2] < 0.3f) return null
        return Offset(
            live.toPixelX(ghostKp[i * 3], ghostKp[i * 3 + 1]) * scaleX,
            live.toPixelY(ghostKp[i * 3], ghostKp[i * 3 + 1]) * scaleY,
        )
    }

    val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
    for ((a, b) in Coco17.EDGES) {
        val oa = offset(a)
        val ob = offset(b)
        if (oa != null && ob != null) {
            drawLine(color, oa, ob, strokeWidth = 3.dp.toPx(), pathEffect = dash)
        }
    }
    for (i in 0 until Coco17.COUNT) {
        offset(i)?.let { drawCircle(color, 4.dp.toPx(), it) }
    }
}
