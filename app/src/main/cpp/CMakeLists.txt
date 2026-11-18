# MoveNet Lightning NCNN 推理薄封装
# ncnn 预编译包（android-vulkan，arm64-v8a + armeabi-v7a）由 app/downloadNcnn 任务
# 下载解压到 app/build/generated/ncnn/，不入库（版本固定 20261020）。
cmake_minimum_required(VERSION 3.22.1)
project(kid4rehab_pose_ncnn LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(ncnn_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../build/generated/ncnn/ncnn-20261020-android-vulkan/${ANDROID_ABI}/lib/cmake/ncnn")
find_package(ncnn REQUIRED)

add_library(pose_ncnn SHARED pose_ncnn.cpp)
target_link_libraries(pose_ncnn ncnn)
