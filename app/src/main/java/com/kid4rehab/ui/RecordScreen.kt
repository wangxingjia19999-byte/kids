package com.kid4rehab.ui

import android.os.VibrationEffect
import android.os.Vibrator
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.kid4rehab.pose.CheckPoint
import com.kid4rehab.pose.NormFail
import com.kid4rehab.pose.PoseNormalizer
import com.kid4rehab.pose.TemplateStore
import com.kid4rehab.pose.frame
import com.kid4rehab.pose.reason
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 录制动作模板：对镜头做动作，关键姿态点「打 CHECK 点」大按钮——
 * 捕获当前平滑帧 → 四锚点归一化 → 追加到 check 序列；最后一个 check 的
 * ghost 实时回显（确认打点效果）。≥2 点可命名保存。
 */
@Composable
fun RecordScreen(onExit: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_FRONT) }
    val normHolder = remember { NormHolder() }
    // 有状态归一化器：本会话持有朝向迟滞/尺度平滑/左右偏移缓存（录制与训练同一口径）
    val normalizer = remember { PoseNormalizer() }
    val checkpoints = remember { mutableStateListOf<CheckPoint>() }
    var hint by remember { mutableStateOf<String?>(null) }
    var normFail by remember { mutableStateOf<NormFail?>(null) }
    var hasFrame by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showSave by remember { mutableStateOf(false) }
    val vibrator = remember { context.getSystemService(Vibrator::class.java) }

    fun buzz(ms: Long) {
        runCatching { vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }

    Box(Modifier.fillMaxSize()) {
        PoseCameraBox(
            lensFacing = lensFacing,
            smooth = true,
            onSnap = { s ->
                hasFrame = s.pose != null
                val r = s.pose?.let { normalizer.normalize(it.keypoints, s.width, s.height, s.mirrored) }
                normHolder.norm = r?.frame
                normFail = r?.reason
            },
            onError = { error = it },
            overlay = { snap ->
                // 最近一个 check 点的 ghost 回显（经 live 逆变换跟随真人）
                val last = checkpoints.lastOrNull()
                if (last != null && snap != null) {
                    drawGhostSkeleton(last.keypoints, normHolder.norm, snap.width, snap.height, Color(0x9964D2FF))
                }
            },
        )

        // 顶部：返回 + 已打点数 + 切换镜头
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
                    "已打 ${checkpoints.size} 个 CHECK 点",
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

        if (!hasFrame && error == null) {
            Box(Modifier.align(Alignment.Center)) {
                CircularProgressIndicator(color = Color(0xFF388E3C))
            }
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
        hint?.let {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xD93D2E10),
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
            ) {
                Text(
                    it,
                    color = Color(0xFFFFD1A1),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }

        // 底部控制台：打点大按钮 + 放弃/保存
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                onClick = {
                    val norm = normHolder.norm
                    if (norm == null) {
                        hint = normFail?.displayText() ?: "无法识别姿态\n请正对镜头、全身入画后再打点"
                        buzz(80)
                    } else if (!norm.anchorsValid()) {
                        hint = "双肩 + 双髋四个锚点未全部可见\n请正对镜头、全身入画后再打点"
                        buzz(80)
                    } else {
                        hint = null
                        checkpoints.add(CheckPoint(checkpoints.size, norm.snapshot()))
                        buzz(30)
                    }
                },
                shape = CircleShape,
                color = Color(0xFF32D74B),
                modifier = Modifier.size(width = 220.dp, height = 64.dp),
            ) {
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Check, null, tint = Color(0xFF0F1015), modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "打 CHECK 点",
                        color = Color(0xFF0F1015),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    onClick = onExit,
                    shape = CircleShape,
                    color = Color(0x66FFFFFF),
                ) {
                    Text(
                        "放弃",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
                    )
                }
                Surface(
                    onClick = { if (checkpoints.size >= TemplateStore.MIN_CHECKPOINTS) showSave = true },
                    shape = CircleShape,
                    color = if (checkpoints.size >= TemplateStore.MIN_CHECKPOINTS) {
                        Color(0xFF64D2FF)
                    } else {
                        Color(0x33FFFFFF)
                    },
                ) {
                    Text(
                        if (checkpoints.size < TemplateStore.MIN_CHECKPOINTS) {
                            "保存（至少 ${TemplateStore.MIN_CHECKPOINTS} 点）"
                        } else {
                            "保存模板"
                        },
                        color = Color(0xFF0F1015),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }

    if (showSave) {
        var name by remember(showSave) {
            mutableStateOf("动作 " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date()))
        }
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text("保存动作模板") },
            text = {
                Column {
                    Text("共 ${checkpoints.size} 个 Check 点", fontSize = 12.sp, color = Color(0x88FFFFFF))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("模板名称") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    TemplateStore(context).save(
                        com.kid4rehab.pose.ActionTemplate(
                            id = UUID.randomUUID().toString(),
                            name = name.ifBlank { "未命名" },
                            createdAtMs = System.currentTimeMillis(),
                            checkpoints = checkpoints.toList(),
                        ),
                    )
                    showSave = false
                    onSaved()
                }) { Text("保存", color = Color(0xFF32D74B), style = MaterialTheme.typography.labelLarge) }
            },
            dismissButton = {
                TextButton(onClick = { showSave = false }) { Text("继续录") }
            },
        )
    }
}
