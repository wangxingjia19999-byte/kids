package com.kid4rehab

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.kid4rehab.ui.HomeScreen
import com.kid4rehab.ui.PoseCameraBox
import com.kid4rehab.ui.PoseSnap
import com.kid4rehab.ui.RecordScreen
import com.kid4rehab.ui.TrainScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                App()
            }
        }
    }
}

/** 页面路由（无导航库，状态驱动） */
private sealed interface Screen {
    data object Home : Screen
    data object Live : Screen
    data object Record : Screen
    data class Train(val templateId: String) : Screen
}

@Composable
private fun App() {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }

    // 动态运行时权限拦截与申请页面
    if (!hasPermission) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF0F1015)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "相机权限用于实时姿态识别\n画面仅在本机处理，不上传",
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Surface(
                onClick = { launcher.launch(Manifest.permission.CAMERA) },
                shape = CircleShape,
                color = Color(0xFF32D74B),
            ) {
                Text(
                    "授予相机权限",
                    color = Color(0xFF0F1015),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }
    } else {
        var screen by remember { mutableStateOf<Screen>(Screen.Home) }
        BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
        when (val s = screen) {
            Screen.Home -> HomeScreen(
                onLive = { screen = Screen.Live },
                onRecord = { screen = Screen.Record },
                onTrain = { screen = Screen.Train(it.id) },
            )
            Screen.Live -> LiveScreen()
            Screen.Record -> RecordScreen(
                onExit = { screen = Screen.Home },
                onSaved = { screen = Screen.Home },
            )
            is Screen.Train -> TrainScreen(s.templateId, onExit = { screen = Screen.Home })
        }
    }
}

/** 实时识别页：相机与骨架由 PoseCameraBox 承载，页面只保留 HUD chrome */
@Composable
private fun LiveScreen() {
    var snap by remember { mutableStateOf<PoseSnap?>(null) }
    var engine by remember { mutableStateOf("引擎加载中…") }
    var error by remember { mutableStateOf<String?>(null) }
    var fps by remember { mutableStateOf(0f) }
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_FRONT) }
    // FPS EMA 状态（逐帧回调里更新，避免重组丢失）：[0]=lastMs [1]=ema
    val fpsState = remember { floatArrayOf(0f, 0f) }

    Box(Modifier.fillMaxSize()) {
        PoseCameraBox(
            lensFacing = lensFacing,
            smooth = false,
            onEngine = { engine = it },
            onError = { error = it },
            onSnap = { s ->
                if (fpsState[0] > 0 && s.captureMs > fpsState[0]) {
                    val dt = (s.captureMs - fpsState[0]).toFloat()
                    fpsState[1] = if (fpsState[1] == 0f) dt else 0.85f * fpsState[1] + 0.15f * dt
                    fps = 1000f / fpsState[1]
                }
                fpsState[0] = s.captureMs.toFloat()
                snap = s
            },
        )

        // 加载占位
        AnimatedVisibility(
            visible = snap == null && error == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xCC1A1C24),
                border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                modifier = Modifier.padding(24.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = Color(0xFF388E3C),
                        strokeWidth = 3.dp,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "正在启动视觉识别引擎...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
            }
        }

        // 顶部 HUD 栏（引擎状态徽章 + 镜头切换按钮）
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左上角：引擎运行指示
            Surface(
                shape = CircleShape,
                color = Color(0x99181A20),
                border = BorderStroke(1.dp, Color(0x22FFFFFF)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 动态指示灯
                    val statusDotColor = when {
                        error != null -> Color(0xFFFF453A)
                        snap != null -> Color(0xFF32D74B)
                        else -> Color(0xFFFF9F0A)
                    }
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(statusDotColor, CircleShape),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = engine,
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            // 右上角：镜头切换胶囊
            Surface(
                onClick = {
                    fps = 0f
                    snap = null
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                        CameraSelector.LENS_FACING_BACK
                    } else {
                        CameraSelector.LENS_FACING_FRONT
                    }
                },
                shape = CircleShape,
                color = Color(0x99181A20),
                border = BorderStroke(1.dp, Color(0x22FFFFFF)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Cameraswitch,
                        contentDescription = "切换镜头",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = if (lensFacing == CameraSelector.LENS_FACING_FRONT) "前置" else "后置",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // 底部多指标悬浮控制台
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 错误告警胶囊（若存在）
            error?.let { errText ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xD93D1414),
                    border = BorderStroke(1.dp, Color(0x66FF453A)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = Color(0xFFFF6961),
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = errText,
                            color = Color(0xFFFFD1D1),
                            fontSize = 11.sp,
                        )
                    }
                }
            }

            // 实时性能仪表胶囊
            val s = snap
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Color(0xAA14161D),
                border = BorderStroke(1.dp, Color(0x26FFFFFF)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MetricChip(
                        label = "FPS",
                        value = if (fps > 0f) "%.1f".format(fps) else "--",
                        valueColor = if (fps >= 25f) Color(0xFF32D74B) else Color(0xFFFF9F0A),
                    )

                    Box(Modifier.width(1.dp).height(12.dp).background(Color(0x33FFFFFF)))

                    MetricChip(
                        label = "延迟",
                        value = s?.let { "${it.inferenceMs}ms" } ?: "--",
                        valueColor = Color.White,
                    )

                    Box(Modifier.width(1.dp).height(12.dp).background(Color(0x33FFFFFF)))

                    MetricChip(
                        label = "置信度",
                        value = s?.pose?.let { "%.2f".format(it.score) } ?: "未锁定",
                        valueColor = if (s?.pose != null) Color(0xFF64D2FF) else Color(0x88FFFFFF),
                    )
                }
            }
        }
    }
}

/** 底部微型指标组件 */
@Composable
private fun MetricChip(
    label: String,
    value: String,
    valueColor: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$label ",
            fontSize = 10.sp,
            color = Color(0x88FFFFFF),
            fontWeight = FontWeight.Normal,
        )
        Text(
            text = value,
            fontSize = 12.sp,
            color = valueColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
    }
}
