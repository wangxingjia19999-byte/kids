// NCNN 推理薄封装：单网络加载 + ARGB crop 直入推理，张量回传与解码全在 Kotlin。
// （移植自 recover4kids #33，已在真机验证；此处仅做包名适配与注释精简。）
//
// 承载 MoveNet Lightning（官方 tflite 切头转换：heatmap [1,48,48,17] + offset [1,48,48,34]，
// 192×192 letterbox 黑边、像素 0-255 原值——模型内部自带 Sub 预处理；头解码 argmax+offset 在 Kotlin）
//
// - 输入为 Kotlin 侧完成的 crop ARGB IntArray（YuvUprightArgb / letterbox），
//   from_pixels(PIXEL_BGRA2RGB)（Android IntArray 小端内存序 = B,G,R,A 字节序）。
// - Vulkan：init 尝试 + 预热，失败自动整网重载 CPU（个别机型 Vulkan 段错误，默认关，
//   见 NcnnMoveNetDetector.tryVulkan 注释）。
// - 输出收集：未被任何 layer 消费的 blob（≤131072 元素：MoveNet heatmap 39168 / offset 78336 在内）；
//   ncnn dims=1 的 Mat total() 含对齐填充，元素数取 w（elem_count）。
#include <jni.h>

#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "net.h"

// NDK 27 libc++ 兼容垫片：ncnn 20261020 预编译库按 NDK 28 构建，其 libc++ 新增
// std::__hash_memory（哈希容器内部使用）；本地 NDK 27 链接缺失，以等价 FNV 补齐。
#include <cstddef>
namespace std {
namespace __ndk1 {
size_t __hash_memory(const void* p, size_t len) {
    const unsigned char* s = static_cast<const unsigned char*>(p);
    size_t h = 1469598103934665603ull;
    for (size_t i = 0; i < len; ++i) {
        h ^= s[i];
        h *= 1099511628211ull;
    }
    return h;
}
}  // namespace __ndk1
}  // namespace std

namespace {

constexpr int kMaxBlobLen = 131072;  // MoveNet offset 78336 在内

ncnn::Net g_net;
bool g_loaded = false;
bool g_using_vulkan = false;
std::vector<int> g_out_lens;

// g_net 进程内唯一：所有 JNI 入口经 g_mutex 串行；同一模型重复 init 只加引用不重载，
// close 引用归零才释放。
std::mutex g_mutex;
std::string g_model_key;  // "param|bin"：判定重复 init 是否同一模型
int g_refs = 0;

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "K4RPOSE", __VA_ARGS__)

// ncnn dims=1 的 Mat total() 含 elemsize 对齐填充，真实元素数取 w
inline int elem_count(const ncnn::Mat& m) { return m.dims == 1 ? m.w : (int)m.total(); }

std::vector<std::string> find_output_blobs(const ncnn::Net& net) {
    std::vector<int> consumed;
    for (const auto* layer : net.layers()) {
        for (int b : layer->bottoms) consumed.push_back(b);
    }
    std::vector<std::string> outs;
    const auto& blobs = net.blobs();
    for (int i = 0; i < (int)blobs.size(); ++i) {
        if (std::find(consumed.begin(), consumed.end(), i) == consumed.end()) {
            outs.push_back(blobs[i].name);
        }
    }
    return outs;
}

bool load_net(ncnn::Net& net, const std::string& param, const std::string& bin, bool try_vulkan) {
    net.clear();
    net.opt.use_vulkan_compute = try_vulkan;
    net.opt.lightmode = true;
    const int rp = net.load_param(param.c_str());
    if (rp != 0) { LOGI("ncnn load_param FAIL %d: %s", rp, param.c_str()); return false; }
    const int rb = net.load_model(bin.c_str());
    if (rb != 0) { LOGI("ncnn load_model FAIL %d: %s", rb, bin.c_str()); return false; }
    return true;
}

bool run_collect(ncnn::Net& net, const ncnn::Mat& in, const std::vector<std::string>& out_names,
                 ncnn::Mat& flat, std::vector<int>& lens) {
    ncnn::Extractor ex = net.create_extractor();
    if (ex.input(net.input_names()[0], in) != 0) return false;
    lens.clear();
    int total = 0;
    std::vector<ncnn::Mat> outs;
    for (const auto& name : out_names) {
        ncnn::Mat m;
        if (ex.extract(name.c_str(), m) != 0) return false;
        const int n = elem_count(m);
        if (n > kMaxBlobLen) continue;
        outs.push_back(m);
        lens.push_back(n);
        total += n;
    }
    flat.create(total);
    if (flat.empty()) return false;
    float* dst = flat.channel(0);
    for (const auto& m : outs) {
        const int n = elem_count(m);
        memcpy(dst, (const float*)m.data, (size_t)n * sizeof(float));
        dst += n;
    }
    return true;
}

// 预热（Vulkan 首帧管线编译暴露在 init 阶段）：假输入跑通并登记输出长度
bool warm_up(int inputSize, std::vector<int>& lens) {
    ncnn::Mat dummy(inputSize, inputSize, 3);
    dummy.fill(0.f);
    ncnn::Mat flat;
    const auto names = find_output_blobs(g_net);
    LOGI("ncnn warm_up: in=%s outs=%d", g_net.input_names().empty() ? "(none)" : g_net.input_names()[0], (int)names.size());
    const bool ok = run_collect(g_net, dummy, names, flat, lens);
    if (!ok) LOGI("ncnn warm_up FAIL");
    return ok;
}

void throw_java(JNIEnv* env, const char* msg) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) env->ThrowNew(cls, msg);
}

}  // namespace

// libomp 亲和性断言崩溃规避（HyperOS 真机实测）：warm_up 所在线程首次并行区正常，
// 但第二个线程成为 OMP master（分析线程首帧推理）时 libomp 为新 root 重跑
// __kmp_affinity_initialize，在该设备核布局/cgroup 下断言失败 → __kmp_abort_process。
//
// libomp 静态链入本 so、其环境变量解析在 dlopen 构造期完成——setenv 必须更早：
// constructor(101) 显式优先级 < 默认优先级，保证本函数先于 libomp 的无优先级构造器
// 执行（跨目标 .init_array 按优先级排）。overwrite=0 不覆盖用户显式设置。
__attribute__((constructor(101)))
static void set_omp_env_early() {
    setenv("KMP_AFFINITY", "disabled", 0);
    setenv("OMP_PROC_BIND", "false", 0);
    // 大小核异构（如 8 Gen 3 的 1+5+2）：默认静态调度给小核等量任务，层耗时被小核拖尾；
    // dynamic 让快核多领任务。须与上同在 libomp 构造前生效（overwrite=0 不覆盖用户设置）
    setenv("OMP_SCHEDULE", "dynamic,1", 0);
}

extern "C" {

// 返回 1=Vulkan 生效 0=CPU；加载/预热失败抛 IllegalStateException
JNIEXPORT jint JNICALL
Java_com_kid4rehab_pose_NcnnJni_nativeInit(
    JNIEnv* env, jobject, jstring paramPath, jstring binPath, jint inputSize, jboolean tryVulkan) {
    std::lock_guard<std::mutex> guard(g_mutex);
    const char* param = env->GetStringUTFChars(paramPath, nullptr);
    const char* bin = env->GetStringUTFChars(binPath, nullptr);
    const std::string key = std::string(param) + "|" + bin;
    if (g_loaded && key == g_model_key) {
        // 同一模型已就绪（检测器实例重建/重入初始化）：只加引用，不重载
        env->ReleaseStringUTFChars(paramPath, param);
        env->ReleaseStringUTFChars(binPath, bin);
        ++g_refs;
        LOGI("ncnn net 复用（refs=%d）", g_refs);
        return g_using_vulkan ? 1 : 0;
    }
    if (g_loaded) LOGI("ncnn 换模型：替换 refs=%d 的旧网络", g_refs);
    std::vector<int> lens;
    bool ok = false;
    if (tryVulkan && ncnn::create_gpu_instance() == 0) {
        ok = load_net(g_net, param, bin, true);
        if (ok) ok = warm_up(inputSize, lens);
        g_using_vulkan = ok;
    }
    if (!ok) {
        g_using_vulkan = false;
        ok = load_net(g_net, param, bin, false);
        if (ok) ok = warm_up(inputSize, lens);
    }
    env->ReleaseStringUTFChars(paramPath, param);
    env->ReleaseStringUTFChars(binPath, bin);
    if (!ok) {
        g_loaded = false;
        g_model_key.clear();
        g_refs = 0;
        throw_java(env, "ncnn 模型加载/预热失败（param/bin 路径或格式）");
        return -1;
    }
    g_loaded = true;
    g_model_key = key;
    g_refs = 1;
    g_out_lens = lens;
    LOGI("ncnn net ready（vulkan=%d, outs=%d 个）", (int)g_using_vulkan, (int)lens.size());
    return g_using_vulkan ? 1 : 0;
}

// crop ARGB → RGB（PIXEL_BGRA2RGB）→ mean/norm 归一化 → 推理 → 输出小 blob 展平写入 out
// （调用方复用缓冲，免每帧 470KB Java 堆分配 → GC 尖峰）；返回写入元素数
JNIEXPORT jint JNICALL
Java_com_kid4rehab_pose_NcnnJni_nativeRun(
    JNIEnv* env, jobject, jintArray cropArgb, jint size, jfloat mean, jfloat norm, jfloatArray out) {
    std::lock_guard<std::mutex> guard(g_mutex);
    if (!g_loaded) {
        throw_java(env, "ncnn 网络未初始化");
        return -1;
    }
    jint* px = env->GetIntArrayElements(cropArgb, nullptr);
    if (px == nullptr) return -1;
    ncnn::Mat in = ncnn::Mat::from_pixels((const unsigned char*)px, ncnn::Mat::PIXEL_BGRA2RGB, size, size);
    env->ReleaseIntArrayElements(cropArgb, px, JNI_ABORT);
    if (mean != 0.f || norm != 1.f) {
        const float means[3] = {mean, mean, mean};
        const float norms[3] = {norm, norm, norm};
        in.substract_mean_normalize(mean != 0.f ? means : nullptr, norms);
    }

    static ncnn::Mat flat;
    std::vector<int> lens;
    if (!run_collect(g_net, in, find_output_blobs(g_net), flat, lens)) {
        throw_java(env, "ncnn 推理失败");
        return -1;
    }
    g_out_lens = lens;
    const int n_total = elem_count(flat);
    if (env->GetArrayLength(out) < n_total) {
        throw_java(env, "ncnn 输出超出 out 缓冲");
        return -1;
    }
    env->SetFloatArrayRegion(out, 0, (jsize)n_total, (const jfloat*)flat.data);
    return n_total;
}

// 各输出 blob 元素数（init/每次 run 后更新；Kotlin 按形状语义切片）
JNIEXPORT jintArray JNICALL
Java_com_kid4rehab_pose_NcnnJni_nativeOutputLens(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> guard(g_mutex);
    jintArray arr = env->NewIntArray((jsize)g_out_lens.size());
    if (arr == nullptr) return nullptr;
    if (!g_out_lens.empty()) env->SetIntArrayRegion(arr, 0, (jsize)g_out_lens.size(), g_out_lens.data());
    return arr;
}

// 释放一个检测器实例的引用；归零才清网络
JNIEXPORT void JNICALL
Java_com_kid4rehab_pose_NcnnJni_nativeClose(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> guard(g_mutex);
    if (g_refs > 1) {
        --g_refs;
        LOGI("ncnn net 释放一个引用（剩 refs=%d）", g_refs);
        return;
    }
    g_net.clear();
    g_loaded = false;
    g_model_key.clear();
    g_refs = 0;
}

}  // extern "C"
