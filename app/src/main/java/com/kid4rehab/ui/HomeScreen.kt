package com.kid4rehab.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kid4rehab.pose.ActionTemplate
import com.kid4rehab.pose.TemplateStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 首页：功能入口 + 本地模板列表（点按训练 / 删除） */
@Composable
fun HomeScreen(
    onLive: () -> Unit,
    onRecord: () -> Unit,
    onTrain: (ActionTemplate) -> Unit,
) {
    val context = LocalContext.current
    val store = remember { TemplateStore(context) }
    var templates by remember { mutableStateOf<List<ActionTemplate>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var deleteTarget by remember { mutableStateOf<ActionTemplate?>(null) }

    LaunchedEffect(refresh) { templates = store.list() }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(Modifier.padding(top = 18.dp)) {
                Text("kid4rehab", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    "动作模板录入 · 四点对齐引导训练",
                    fontSize = 13.sp,
                    color = Color(0x99FFFFFF),
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EntryCard(
                    icon = Icons.Rounded.Videocam,
                    title = "实时识别",
                    subtitle = "MoveNet × NCNN 骨架",
                    modifier = Modifier.weight(1f),
                    onClick = onLive,
                )
                EntryCard(
                    icon = Icons.Rounded.FiberManualRecord,
                    title = "录制模板",
                    subtitle = "手动打 CHECK 点",
                    modifier = Modifier.weight(1f),
                    onClick = onRecord,
                )
            }
        }

        item {
            Text(
                "我的模板",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (templates.isEmpty()) {
            item {
                Text(
                    "还没有模板——去「录制模板」录一个动作\n（对镜头做动作，关键姿态点 CHECK 打点）",
                    fontSize = 12.sp,
                    color = Color(0x66FFFFFF),
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }

        items(templates, key = { it.id }) { t ->
            Surface(
                onClick = { onTrain(t) },
                shape = CircleShape,
                color = Color(0xFF1A1C24),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.PlayCircle,
                        contentDescription = null,
                        tint = Color(0xFF32D74B),
                        modifier = Modifier.size(30.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "${t.checkpoints.size} 个 Check 点 · ${
                                SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(t.createdAtMs))
                            }",
                            fontSize = 11.sp,
                            color = Color(0x88FFFFFF),
                        )
                    }
                    IconButton(onClick = { deleteTarget = t }) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = "删除",
                            tint = Color(0x66FFFFFF),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除模板「${target.name}」？") },
            text = { Text("删除后不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    store.delete(target.id)
                    deleteTarget = null
                    refresh++
                }) { Text("删除", color = Color(0xFFFF453A)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun EntryCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color(0xFF1A1C24),
        modifier = modifier,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = Color(0xFF64D2FF), modifier = Modifier.size(26.dp))
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 11.sp, color = Color(0x88FFFFFF))
        }
    }
}
