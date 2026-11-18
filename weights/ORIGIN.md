# weights 溯源

| 文件 | 来源 | 许可 |
|---|---|---|
| ncnn/movenet_lightning.param / .bin | MoveNet SinglePose Lightning（官方 tflite 切头转换：卷积主干进 ncnn，heatmap+offset 头解码在 Kotlin，与完整官方模型输出数值对拍） | Apache-2.0 |

- 拷贝自 recover4kids `algorithm/weights/ncnn/`（commit 38ffc84，该目录入 git，App 随包分发的默认引擎）。
- 原始权重：Google `lite-model_movenet_singlepose_lightning_4.tflite`（kaggle google/movenet，
  fp32 版）。
- 输入 192×192 letterbox 黑边、像素 0-255 原值（模型内部自带 Sub 预处理）；
  输出 heatmap [48,48,17] + offset [48,48,34]（CHW）。
