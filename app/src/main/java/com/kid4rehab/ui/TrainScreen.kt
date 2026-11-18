package com.kid4rehab.ui

import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.camera.core.CameraSelector
import com.kid4rehab.drawGhostSkeleton
import com.kid4rehab.pose.ActionTemplate
import com.kid4rehab.pose.CheckTracker
import com.kid4rehab.pose.NormFail
import com.kid4rehab.pose.PoseMatcher
import com.kid4rehab.pose.PoseNormalizer
import com.kid4rehab.pose.TemplateStore
import com.kid4rehab.pose.frame
import com.kid4rehab.pose.reason
import kotlin.math.roundToInt

private enum class TrainPhase { ALIGN, RUNNING, DONE }

/**
 * 模板引导训练：四点对齐（左右肩+左右髋）→ 按 check 顺序闯关 → 最后一个达成即完成。
 *
 * - 对齐阶段：显示 check[0] ghost（虚线），双肩双髋可用且与 check[0] 偏差达标持续 ~1s → 开始
 * - 闯关阶段：当前 check ghost 高亮 + 偏差条；达成（topK 偏差 < 容差持续 8 帧）→ 震动 + 跳下一个
 * - 完成阶段：统计卡片 + 再来一轮 / 返回
 * ghost 经 live 归一化逆变换实时跟随真人（人移动目标跟着走）。
 */
@Composable
fun TrainScreen(templateId: String, onExit: () -> Unit) {
    val context = LocalContext.current
    var template by remember { mutableStateOf<ActionTemplate?>(null) }
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_FRONT) }
    val normHolder = remember { NormHolder() }
    var phase by remember { mutableStateOf(TrainPhase.ALIGN) }
    var curIdx by remember { mutableIntStateOf(0) }
    var deviation by remember { mutableStateOf<Float?>(null) }
    var holdProgress by remember { mutableFloatStateOf(0f) }
    var banner by remember { mutableStateOf<String?>(null) }
    var anchorsLost by remember { mutableStateOf(false) }
    var normFail by remember { mutableStateOf<NormFail?>(null) }
    var hasFrame by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var startedAtMs by remember { mutableStateOf(0L) }
    var finishedAtMs by remember { mutableStateOf(0L) }

    val alignTracker = remember(templateId) { CheckTracker(holdFrames = 12) }
    val checkTracker = remember(templateId, curIdx) { CheckTracker() }
    // 有状态归一化器：本会话持有朝向迟滞/尺度平滑/左右偏移缓存（模板与实时同一口径）
    val normalizer = remember { PoseNormalizer() }
    val vibrator = remember { context.getSystemService(Vibrator::class.java) }

    LaunchedEffect(templateId) { template = TemplateStore(context).load(templateId) }
    LaunchedEffect(banner) {
        if (banner != null) {
            kotlinx.coroutines.delay(2200)
            banner = null
        }
    }

    fun buzz(ms: Long) {
        runCatching { vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }

    fun onSnap(s: PoseSnap) {
        hasFrame = s.pose != null
        val t = template ?: return
        val result = s.pose?.let { normalizer.normalize(it.keypoints, s.width, s.height, s.mirrored) }
        val norm = result?.frame
        normHolder.norm = norm
        normFail = result?.reason
        anchorsLost = norm == null || !norm.anchorsValid()
        when (phase) {
            TrainPhase.ALIGN -> {
                val dev = if (!anchorsLost) PoseMatcher.deviation(t.checkpoints[0].keypoints, norm!!) else null
                deviation = dev
                holdProgress = alignTracker.progress()
                if (alignTracker.update(dev)) {
                    phase = TrainPhase.RUNNING
                    startedAtMs = SystemClock.elapsedRealtime()
                    banner = "✓ 已对齐，开始训练"
                    buzz(60)
                }
            }
            TrainPhase.RUNNING -> {
                val dev = norm?.let { PoseMatcher.deviation(t.checkpoints[curIdx].keypoints, it) }
                deviation = dev
                holdProgress = checkTracker.progress()
                if (checkTracker.update(dev)) {
                    buzz(45)
                    if (curIdx >= t.checkpoints.lastIndex) {
                        phase = TrainPhase.DONE
                        finishedAtMs = SystemClock.elapsedRealtime()
                    } else {
                        banner = "✓ Check ${curIdx + 1} 达成"
                        curIdx++
                    }
                }
            }
            TrainPhase.DONE -> Unit
        }
    }

    Box(Modifier.fillMaxSize()) {
        PoseCameraBox(
            lensFacing = lensFacing,
            smooth = true,
            onSnap = ::onSnap,
            onError = { error = it },
            overlay = { snap ->
                val t = template
                if (t != null && snap != null) {
                    val idx = when (phase) {
                        TrainPhase.ALIGN -> 0
                        else -> curIdx.coerceAtMost(t.checkpoints.lastIndex)
                    }
                    val color = when (phase) {
                        TrainPhase.ALIGN -> Color(0xB3FFD60A)          // 对齐：琥珀
                        TrainPhase.DONE -> Color(0x6630D158)
                        else -> {
                            val dev = deviation
                            if (dev != null && dev < PoseMatcher.TOLERANCE) {
                                Color(0xFF30D158)                      // 接近达标：绿
                            } else {
                                Color(0xE664D2FF)                      // 引导：青
                            }
                        }
                    }
                    drawGhostSkeleton(t.checkpoints[idx].keypoints, normHolder.norm, snap.width, snap.height, color)
                }
            },
        )

        // 顶部：返回 + 进度 + 切换镜头
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onExit) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = Color.White)
            }
            Surface(shape = CircleShape, color = Color(0x99181A20)) {
                Text(
                    when (phase) {
                        TrainPhase.ALIGN -> "对齐目标姿态"
                        TrainPhase.DONE -> "训练完成"
                        else -> "Check ${curIdx + 1} / ${template?.checkpoints?.size ?: 0}"
                    },
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            IconButton(onClick = {
                lensFacing =
                    if (lensFacing == CameraSelector.LENS_FACING_FRONT) CameraSelector.LENS_FACING_BACK
                    else CameraSelector.LENS_FACING_FRONT
            }) {
                Icon(Icons.Rounded.Cameraswitch, "切换镜头", tint = Color.White)
            }
        }

        // 偏差条（距当前 check 的接近程度；对齐阶段显示对齐进度）
        if (phase != TrainPhase.DONE && template != null) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 60.dp, start = 40.dp, end = 40.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val dev = deviation
                // 偏差 0 → 接近度 1；偏差 1.5×容差 → 0
                val closeness = if (dev == null) 0f else (1f - dev / (PoseMatcher.TOLERANCE * 1.5f)).coerceIn(0f, 1f)
                val barColor = when {
                    closeness >= 0.67f -> Color(0xFF32D74B)
                    closeness >= 0.34f -> Color(0xFFFFD60A)
                    else -> Color(0xFFFF9F0A)
                }
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0x26FFFFFF),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(closeness.coerceAtLeast(0.03f))
                            .height(8.dp)
                            .background(barColor),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        phase == TrainPhase.ALIGN && normHolder.norm == null ->
                            normFail?.displayText() ?: "无法识别姿态\n请正对镜头、全身入画"
                        phase == TrainPhase.ALIGN && anchorsLost -> "双肩+双髋未全部可见，正对镜头全身入画"
                        phase == TrainPhase.ALIGN -> "摆出高亮目标姿态，保持即可开始（${(holdProgress * 100).roundToInt()}%）"
                        deviation == null -> normFail?.displayText() ?: "姿态不可判定，请正对镜头"
                        else -> "跟随虚线骨架移动 · 接近度 ${(closeness * 100).roundToInt()}%"
                    },
                    fontSize = 11.sp,
                    color = Color(0xCCFFFFFF),
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (!hasFrame && error == null && template != null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color(0xFF388E3C))
        }
        if (template == null && error == null) {
            Text(
                "模板加载中…",
                color = Color(0x88FFFFFF),
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (template != null && template!!.checkpoints.size < TemplateStore.MIN_CHECKPOINTS) {
            Text(
                "模板 Check 点不足",
                color = Color(0xFFFF6961),
                modifier = Modifier.align(Alignment.Center),
            )
        }
        error?.let {
            Text(
                it,
                color = Color(0xFFFF6961),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }

        // 达成横幅
        banner?.let {
            Surface(
                shape = CircleShape,
                color = Color(0xCC1B3A25),
                modifier = Modifier.align(Alignment.Center).padding(bottom = 120.dp),
            ) {
                Text(
                    it,
                    color = Color(0xFF9BEFB4),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }
        }

        // 完成卡片
        if (phase == TrainPhase.DONE) {
            val t = template
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xF0161A22),
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            ) {
                Column(
                    Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Rounded.EmojiEvents,
                        null,
                        tint = Color(0xFFFFD60A),
                        modifier = Modifier.size(44.dp),
                    )
                    Text("训练完成！", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    if (t != null) {
                        val sec = (finishedAtMs - startedAtMs) / 1000f
                        Text(
                            "${t.name} · ${t.checkpoints.size} 个 Check 点 · 用时 ${"%.1f".format(sec)}s",
                            fontSize = 12.sp,
                            color = Color(0x99FFFFFF),
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(
                            onClick = {
                                curIdx = 0
                                alignTracker.reset()
                                checkTracker.reset()
                                deviation = null
                                holdProgress = 0f
                                phase = TrainPhase.ALIGN
                            },
                            shape = CircleShape,
                            color = Color(0xFF32D74B),
                        ) {
                            Text(
                                "再来一轮",
                                color = Color(0xFF0F1015),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                            )
                        }
                        Surface(
                            onClick = onExit,
                            shape = CircleShape,
                            color = Color(0x33FFFFFF),
                        ) {
                            Text(
                                "返回首页",
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
