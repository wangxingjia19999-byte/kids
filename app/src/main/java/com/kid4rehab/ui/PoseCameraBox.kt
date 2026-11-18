package com.kid4rehab.ui

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kid4rehab.drawLiveSkeleton
import com.kid4rehab.drawRoiBox
import com.kid4rehab.pose.CameraPoseSource
import com.kid4rehab.pose.KeypointSmoother
import com.kid4rehab.pose.NormFrame
import com.kid4rehab.pose.PoseDetectorHolder
import com.kid4rehab.pose.PoseFrame
import com.kid4rehab.pose.Roi

/** 一帧实时快照（分析线程产出） */
data class PoseSnap(
    val width: Int,
    val height: Int,
    val roi: Roi?,
    /** 原始关键点帧；smooth=true 时为 One-Euro 平滑帧 */
    val pose: PoseFrame?,
    val captureMs: Long,
    val inferenceMs: Long,
    /** 本帧是否来自镜像的前置相机（归一化层据此翻回真实空间 + 换左右标签） */
    val mirrored: Boolean = false,
)

/** 实时归一化帧持有器（打点/比对/ghost 绘制消费；跨线程 @Volatile） */
class NormHolder {
    @Volatile
    var norm: NormFrame? = null
}

/** 原始快照持有器（需要整帧数据的场合；跨线程 @Volatile） */
class PoseSnapHolder {
    @Volatile
    var snap: PoseSnap? = null
}

/**
 * 公共相机页组件：相机源（进程级检测器单例）+ 预览 + 实时骨架叠加 + 自定义叠加钩子。
 * 各页面（实时识别 / 录制 / 训练）复用，页面只叠自己的控制与 HUD。
 *
 * @param smooth 接入 One-Euro 关键点平滑（录制/训练用；纯识别页关闭保持原汁原味）
 * @param showRoiBox 显示识别取景框（送检 ROI 恒为居中 92%，仅控制是否画出；默认不画）
 * @param onSnap 逐帧回调（分析线程；写入普通持有器可避免整页重组）
 * @param overlay 额外叠加绘制（ghost 骨架等），与实时骨架同一画布坐标
 */
@Composable
fun PoseCameraBox(
    modifier: Modifier = Modifier,
    lensFacing: Int = CameraSelector.LENS_FACING_FRONT,
    smooth: Boolean = false,
    showRoiBox: Boolean = false,
    onEngine: (String) -> Unit = {},
    onSnap: (PoseSnap) -> Unit = {},
    onError: (String) -> Unit = {},
    overlay: DrawScope.(snap: PoseSnap?) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var snap by remember { mutableStateOf<PoseSnap?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    DisposableEffect(lensFacing) {
        var source: CameraPoseSource? = null
        val mainHandler = Handler(Looper.getMainLooper())
        val smoother = if (smooth) KeypointSmoother() else null
        // 检测器为进程级单例，创建（含 warm_up）与所有推理固定同一专用线程
        // （libomp 第二个 OMP master root 亲和性断言崩溃的根治，见 PoseDetectorHolder）；
        // 相机绑定与 PreviewView 访问要求主线程，就绪后 post 回主线程启动
        val thread = Thread {
            try {
                val d = PoseDetectorHolder.get(context)
                onEngine("MoveNet Lightning · ${d.device}")
                mainHandler.post {
                    try {
                        val src = CameraPoseSource(
                            detector = d,
                            wantLensFacing = lensFacing,
                            onFrame = { w, h, roi, captureMs, pose, inferenceMs, mirrored ->
                                val out = if (smoother != null && pose != null) smoother.smooth(pose) else pose
                                val s = PoseSnap(w, h, roi, out, captureMs, inferenceMs, mirrored)
                                snap = s
                                onSnap(s)
                            },
                            onError = { onError("相机错误：$it") },
                        )
                        source = src
                        previewView?.let { src.setPreviewSurfaceProvider(it.surfaceProvider) }
                        src.start(context, lifecycleOwner)
                    } catch (t: Throwable) {
                        Log.e("K4RPOSE", "相机启动失败", t)
                        onError("相机启动失败：${t.message}")
                    }
                }
            } catch (t: Throwable) {
                Log.e("K4RPOSE", "引擎初始化失败", t)
                onError("初始化失败：${t.message}")
            }
        }
        thread.start()
        onDispose {
            mainHandler.removeCallbacksAndMessages(null)
            source?.close()
            // 检测器进程级单例不关闭（PoseDetectorHolder）
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Color(0xFF0F1015)),
    ) {
        // 画幅适配：内容区与分析/推理帧同比例（4:3 分析流）；屏幕比画幅宽以高定尺寸、反之以宽
        val frameAspect = snap?.let { it.width.toFloat() / it.height } ?: (4f / 3f)
        Box(
            Modifier
                .align(Alignment.Center)
                .aspectRatio(
                    frameAspect,
                    matchHeightConstraintsFirst = maxWidth / maxHeight > frameAspect,
                ),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FIT_CENTER
                        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                        previewView = this
                    }
                },
            )
            Canvas(Modifier.fillMaxSize()) {
                val s = snap ?: return@Canvas
                if (showRoiBox) {
                    s.roi?.let {
                        drawRoiBox(
                            it,
                            s.width,
                            s.height,
                            if (s.pose == null) Color(0xE6FF9F0A) else Color(0x8030D158),
                        )
                    }
                }
                s.pose?.let { drawLiveSkeleton(it, s.width, s.height) }
                overlay(s)
            }
        }
    }
}
