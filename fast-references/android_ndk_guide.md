# Android NDK 指南

## Android.mk

基本项目搭建

```makefile
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := foo
LOCAL_SRC_FILES := main.cpp

include $(BUILD_SHARED_LIBRARY)
````

## cmake

## 独立工具链

## Android.mk 变量参考

- NDK 定义的 include 变量

- 目标信息变量

- 模块描述变量

# 引入预编译库

## 引入动态库

## 引入静态库

LOCAL_PATH := $(call my-dir) 返回当前 Android.mk 所在的路径

================ 分割线 ================

