package com.kid4rehab.pose

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一个 check 点：录制时手动打点的归一化姿态（17×3，躯干长单位，见 [PoseNormalize]） */
class CheckPoint(
    val index: Int,
    /** 归一化 17×3：x,y,conf——conf 为录制时该点置信度（绘制/比对时过滤低置信点） */
    val keypoints: FloatArray,
) {
    init {
        require(keypoints.size == Coco17.COUNT * 3) { "keypoints 必须是 17*3，实际 ${keypoints.size}" }
    }
}

/** 动作模板：有序 check 点序列（存储即归一化，跨身材/距离/镜头通用） */
class ActionTemplate(
    val id: String,
    val name: String,
    val createdAtMs: Long,
    val checkpoints: List<CheckPoint>,
)

/**
 * 模板本地存储：`filesDir/templates/<id>.json`（org.json 手写序列化，无额外依赖）。
 * 列表按创建时间倒序。
 */
class TemplateStore(private val context: Context) {

    private fun dir(): File = File(context.filesDir, "templates").also { it.mkdirs() }

    private fun fileOf(id: String): File = File(dir(), "$id.json")

    fun list(): List<ActionTemplate> =
        dir().listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { runCatching { fromJson(it.readText()) }.getOrNull() }
            ?.sortedByDescending { it.createdAtMs }
            .orEmpty()

    fun save(template: ActionTemplate) {
        fileOf(template.id).writeText(toJson(template))
    }

    fun load(id: String): ActionTemplate? =
        fileOf(id).takeIf { it.exists() }?.let { runCatching { fromJson(it.readText()) }.getOrNull() }

    fun delete(id: String) {
        fileOf(id).delete()
    }

    companion object {
        fun toJson(t: ActionTemplate): String {
            val root = JSONObject()
            root.put("schema", "kid4rehab.action-template")
            root.put("schemaVersion", 1)
            root.put("id", t.id)
            root.put("name", t.name)
            root.put("createdAt", t.createdAtMs)
            val cps = JSONArray()
            t.checkpoints.forEach { cp ->
                val o = JSONObject()
                o.put("index", cp.index)
                o.put("k", JSONArray().apply { cp.keypoints.forEach { put(it.toDouble()) } })
                cps.put(o)
            }
            root.put("checkpoints", cps)
            return root.toString(2)
        }

        fun fromJson(text: String): ActionTemplate? = runCatching {
            val root = JSONObject(text)
            val cps = root.getJSONArray("checkpoints")
            val list = (0 until cps.length()).map { i ->
                val o = cps.getJSONObject(i)
                val k = o.getJSONArray("k")
                CheckPoint(o.getInt("index"), FloatArray(Coco17.COUNT * 3) { j -> k.getDouble(j).toFloat() })
            }
            ActionTemplate(
                id = root.getString("id"),
                name = root.optString("name", "未命名"),
                createdAtMs = root.optLong("createdAt", 0L),
                checkpoints = list,
            )
        }.getOrNull()

        /** 录制最少 check 点数（单点无序列意义） */
        const val MIN_CHECKPOINTS = 2
    }
}
