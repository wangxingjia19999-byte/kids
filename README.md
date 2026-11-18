# kid4rehab · MoveNet-NCNN 实时姿态识别（Android）

摄像头 → MoveNet Lightning × NCNN 端侧推理 → 实时骨架渲染的最小可运行工程。
移植自 [recover4kids](../recover4kids)（已在真机验证的 #33 档位），识别链路零改动，仅做包名适配与 UI 精简。

## 技术路线

```
CameraX（前置/后置可切换；1280×960 预览 + 320×240 分析流，4:3，KEEP_ONLY_LATEST）
  → 框模式 ROI（居中 92% 长方形）YUV 直入（免整帧 YUV→ARGB→Bitmap 三趟搬运）
  → 192×192 letterbox（像素 0-255 原值，模型内自带预处理）
  → ncnn 推理（heatmap [48,48,17] + offset [48,48,34]，JNI 薄封装，默认 CPU）
  → Kotlin 头解码 argmax+offset → COCO17 帧像素坐标
  → Compose Canvas 骨架叠加（按置信度着色）+ HUD（耗时/FPS/人体置信度）
```

- 模型：MoveNet SinglePose Lightning，官方 tflite 切头自转换（卷积主干进 ncnn，头解码在 Kotlin，
  与完整官方模型输出数值对拍）。溯源见 `weights/ORIGIN.md`。
- ncnn：预编译 android-vulkan 包（版本固定 20261020），构建时自动下载（或手工放入
  `app/build/generated/ncnn/`），CMake 按 ABI 链接静态库；ABI 为 arm64-v8a + armeabi-v7a。
- Vulkan 默认关（个别机型段错误，见 `NcnnMoveNetDetector.tryVulkan`），CPU 档已够实时。

## 目录

```
weights/ncnn/        # 模型单一来源（构建时拷入 assets/pose/ncnn/）
app/src/main/cpp/    # JNI 薄封装 + CMake
app/src/main/java/com/kid4rehab/pose/   # 检测器 / 解码 / 像素变换 / 相机源
```

## 构建

```bash
./gradlew :app:assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

要求：JDK 17+、Android SDK platform 35、NDK 27+（`local.properties` 配 `sdk.dir`）。
首次构建需联网下载 Gradle 分发与依赖；ncnn 预编译包若已手工放入 `app/build/generated/ncnn/`
（含 `.extracted-20261020` 标记）则免下载。

## 真机运行

安装后授予相机权限，人进入画面中央取景框即出骨架；底部 HUD 显示推理设备、单帧耗时、FPS、人体置信度。
