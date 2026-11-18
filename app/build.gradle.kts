plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 权重单一来源：根 weights/ncnn 构建时拷入 build/generated/poseAssets 并并入 assets
val poseAssetsDir = layout.buildDirectory.dir("generated/poseAssets")

val copyPoseModel by tasks.registering(Copy::class) {
    into(poseAssetsDir.map { it.dir("pose/ncnn") })
    from(rootProject.rootDir.resolve("weights/ncnn")) {
        include("*.param", "*.bin")
    }
}

// NCNN 推理后端：预编译包（android-vulkan，版本固定）下载解压到 build/generated/ncnn，
// 不入库；CMake（src/main/cpp/CMakeLists.txt）按 ABI 链接 libncnn.a。
// 本地已有缓存时可先手工放入 build/generated/ncnn/（含 .extracted-<版本> 标记）免下载。
val ncnnVersion = "20261020"
val ncnnExtractDir = layout.buildDirectory.dir("generated/ncnn")

val downloadNcnn by tasks.registering {
    val zipFile = layout.buildDirectory.file("generated/ncnn-$ncnnVersion-android-vulkan.zip")
    outputs.dir(ncnnExtractDir)
    doLast {
        val zip = file(zipFile)
        val marker = file(ncnnExtractDir.map { it.file(".extracted-$ncnnVersion") })
        if (!marker.exists()) {
            var ok = false
            var lastErr: Exception? = null
            repeat(3) { attempt ->
                try {
                    if (!ok) {
                        zip.parentFile.mkdirs()
                        ant.invokeMethod(
                            "get",
                            mapOf(
                                "src" to "https://github.com/Tencent/ncnn/releases/download/$ncnnVersion/ncnn-$ncnnVersion-android-vulkan.zip",
                                "dest" to zip,
                            ),
                        )
                        copy {
                            from(zipTree(zip))
                            into(ncnnExtractDir)
                        }
                        ok = true
                    }
                } catch (e: Exception) {
                    lastErr = e
                    zip.delete()
                    Thread.sleep(3_000L * (attempt + 1))
                }
            }
            if (!ok) throw IllegalStateException("ncnn 预编译包下载/解压失败（3 次重试）", lastErr)
            marker.writeText("ok")
        }
    }
}

android {
    namespace = "com.kid4rehab"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kid4rehab"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // NCNN native 库：arm64 主流 + v7a 廉价儿童平板
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    // 模型不入 app 源码树，weights/ 为单一来源，构建时拷入并随 App 打包（约 9MB）
    sourceSets {
        getByName("main") {
            assets.srcDir(poseAssetsDir)
        }
    }

    // NCNN 推理薄封装（pose_ncnn.cpp）
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // 相机实时取流（CameraX：预览 + 分析双流）
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation(libs.junit)
}

tasks.preBuild {
    dependsOn(copyPoseModel)
    dependsOn(downloadNcnn)
}
