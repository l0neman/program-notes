# Android NDK 指南

## Android.mk

基于 Android.mk 的 libfoo.so 的 ndk 基本工程搭建

在 src/main 下建立 jni 目录（Android.mk 工程的默认文件目录为 jni），工程结构如下：

```
-jni/
  +Android.mk
  +Application.mk
  +libfoo.h
  +libfoo.cpp
```

```makefile
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := foo
LOCAL_SRC_FILES := main.cpp

include $(BUILD_SHARED_LIBRARY)
````

todo



## cmake

## 独立工具链

## Android.mk 变量参考

- NDK 定义的 include 变量

- 目标信息变量

- 模块描述变量

# 引入预编译库

## 引入动态库

1. 首先在独立的 ndk 工程编译出一个共享库 libbar.so，提供给别人使用。

工程目录结构：

```
-jni/
  +Android.mk
  +Application.mk
  +libbar.h
  +libbar.cpp
```

测试代码：

```cpp
// libbar.h
#ifndef NDKTPROJECT_LIBBAR_H
#define NDKTPROJECT_LIBBAR_H

extern "C" {

int bar_add(int a, int b);

};

#endif //NDKTPROJECT_LIBBAR_H
```

```cpp
// libbar.cpp
#include "libbar.h"

int bar_add(int a, int b) {
  return a + b;
}
```

```makefile
# module-libbar Android.mk

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := bar
LOCAL_SRC_FILES := libbar.cpp

include $(BUILD_SHARED_LIBRARY)
```

```makefile
# module-libbar Application.mk

APP_ABI := armeabi-v7a arm64-v8a x86 x86_64
APP_OPTIM := debug
```

使用命令行进入 jni 目录下，然后执行 ndk-build 编译出 4 种架构的 libbar.so 文件，在和 jni 同级的 libs 目录下。

```
+jni/
-libs/
  -armeabi-v7a/
    +libbar.so
  -arm64-v8a/
    +libbar.so
  -x86/
    +libbar.so
  -x86_64/
    +libbar.so
```

2. 将每种架构目录复制到需要使用此库的 ndk 工程中（libfoo.so），在工程中新建 include 目录，将 libbar 的头文件复制过来，为了提供调用的接口。

工程目录结构：

```
-jni/
  -armeabi-v7a/
    +libbar.so
  -arm64-v8a/
    +libbar.so
  -x86/
    +libbar.so
  -x86_64/
    +libbar.so
  -include/
    +libbar.h
  +Android.mk
  +Application.mk
  +libfoo.h
  +libfoo.cpp
```

3. 编写 libfoo.so 的 Android.mk 文件，$(TARGET_ARCH_ABI) 为 ndk 编译时每种架构的名字。

```makefile
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := libbar-pre
LOCAL_SRC_FILES := $(TARGET_ARCH_ABI)/libbar.so
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/include
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := foo
LOCAL_SRC_FILES := main.cpp
LOCAL_SHARED_LIBRARIES := libbar-pre
include $(BUILD_SHARED_LIBRARY)
```

此时当工程编译时，对应的 libbar.so 将会自动被加入到 apk 包中。

4. 代码调用

```cpp
// libfoo.h

extern "C" {

#ifndef NDKTPROJECT_LIBFOO_H
#define NDKTPROJECT_LIBFOO_H

#include <jni.h>

JNIEXPORT jstring JNICALL
Java_io_l0neman_mkexample_NativeHandler_test(JNIEnv *env, jclass clazz);

};

#endif //NDKTPROJECT_LIBFOO_H
```

```cpp
// libfoo.cpp

#include <libbar.h>

void Java_io_l0neman_mkexample_NativeHandler_test() {
  int a = bar_add(1, 4);
  printf("%d\n", a);
}
```

5. Java 层调用测试

```java
// class io.l0neman.mkexample.NativeHandler

public class NativeHandler {

  static {
    // 加载 libfoo.so 时，libbar 会被自动加载。
    System.loadLibrary("foo");
  }

  public static native String test();
}
```

```java
// MainActivity.java
NativeHandler.test();
```

## 引入静态库

1. 首先编译出 .a 后缀的静态库 libbar.a。

工程结构和上面引入动态库中的 libbar 工程一致，只需要将 Android.mk 文件中引入的 BUILD_SHARED_LIBRARY 变量修改为 BUILD_STATIC_LIBRARY 即可指定编译出静态库。

```makefile
# module-libbar Android.mk

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := bar
LOCAL_SRC_FILES := libbar.cpp

include $(BUILD_SHARED_LIBRARY)
```

使用 ndk-build 编译后，不会产生和 jni 同级的 libs 目录，每种架构的 libbar.a 文件在和 jni 同级的 obj 目录中。

目录结构如下：

```
+jni/
-obj/
  -armeabi-v7a/
    +libbar.a
  -arm64-v8a/
    +libbar.a
  -x86/
    +libbar.a
  -x86_64/
    +libbar.a
```

2. 在 libfoo.so 工程中引入静态库，步骤和引入动态库大同小异，把 obj 目录

todo

================ 分割线 ================

LOCAL_PATH := $(call my-dir) 返回当前 Android.mk 所在的路径