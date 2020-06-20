# Android NDK 指南

[TOC]

# NDK 工程构建

可采用三种方式进行 NDK 工程的构建。

1. 基于 Make 的 ndk-build，这是传统的 ndk-build 构建方式，使用 Makefile 形式进行构建，简洁高效；
2. CMake 是新型的构建方式，CMake 具有跨平台的特性，通过 CMake 生成 Makefile 后再进行构建，CMake 的配置文件可读性更高；
3. 其他编译系统，通过引入其他编译系统可对编译过程进行定制，例如引入 Obfuscator-LLVM 对源码进行混淆和压缩，增强源代码安全性。

下面是每种构建方式的指南，使用 Android Studio 4.0 和 NDK 21 进行如下构建。



## Android.mk

基于 Android.mk 的 libfoo.so 的 NDK 基本工程搭建。

在 Android 工程的 src/main 下建立 jni 目录（Android.mk 工程的默认文件目录为 jni，也可指定其他目录进行构建 ndk-build -C 目录），工程结构如下：

包含两个 .mk 文件用来描述 NDK 工程，和两个基本的 C++ 语言源文件。

```
-jni/
  +Android.mk
  +Application.mk
  +libfoo.h
  +libfoo.cpp
```

在 Android Studio 的当前 Module 配置中指明 Android.mk 文件路径:

```groovy
// app-build.gradle
android {
  ...
  externalNativeBuild {
    ndkBuild {
      path 'src/main/jni/Android.mk'
    }
  }
}
```

编写 Android.mk 文件用于向 NDK 构建系统描述工程的 C/C++ 源文件以及共享库的属性。

```makefile
# Android.mk
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := foo
LOCAL_SRC_FILES := main.cpp

include $(BUILD_SHARED_LIBRARY)
````

Application.mk 用于描述 NDK 工程概要设置。

```makefile
# Application.mk

APP_ABI := armeabi-v7a arm64-v8a
APP_OPTIM := debug
```

添加 Java 层代码，用于声明 JNI 方法。

```java
// class io.l0neman.mkexample.NativeHandler
public class NativeHandler {

  static {
    System.loadLibrary("foo");
  }

  public static native String getHello();
}
```

源代码：

```cpp
// libfoo.h

extern "C" {

#ifndef NDKTPROJECT_LIBFOO_H
#define NDKTPROJECT_LIBFOO_H

#include <jni.h>

JNIEXPORT jstring JNICALL
Java_io_l0neman_mkexample_NativeHandler_getHello(JNIEnv *env, jclass clazz);

};

#endif //NDKTPROJECT_LIBFOO_H
```

```cpp
// libfoo.cpp
#include "libfoo.h"

jstring Java_io_l0neman_mkexample_NativeHandler_getHello(JNIEnv *env, jclass clazz) {
  return env->NewStringUTF("Hello-jni");
}
```

todo 

这样的话就完成了一个基本的 NDK 工程搭建，编译后调用代码即可获取 java 字符串。

```java
String hello = NativeHandler.getHello();
```



## CMake

使用 CMake 和 Android.mk 在 Android Studio 中的构建步骤类似。

todo



## 独立工具链

## 构建技巧

在前面 Android.mk 的工程中，需要在 Module 级别的 gradle 配置如下 Android,.mk 路径：

```groovy
// app-build.gradle
android {
  ...
  externalNativeBuild {
    ndkBuild {
      path 'src/main/jni/Android.mk'
    }
  }
}
```

这样 Android Studio 就会在构建时主动调用 NDK 提供的 ndk-build 脚本，为工程生成 libfoo.so 文件。对于 NDK 工程来说，使用每次构建都需要先在 Studio 中点击 Clean，然后点击 Build 整个工程，否则 so 文件不能主动刷新。

而且这样必须依赖于 Android Studio 这个 IDE 所提供的集成环境，要避免以这种方式构建，如下：

首先把上面的路径 Android.mk 在 gradle 中的配置去除。

在默认的依赖配置里面可以看到，libs 目录已被加入依赖，就是说如果 libs 目录中有 so 文件，那么会被自动加入 apk 中。

```groovy
// app-build.gradle
dependencies {
    implementation fileTree(dir: 'libs', include: ['*.jar'])
    ...
}
```

那么就可以采用这种方式构建 libfoo.so 文件，首先确认 NDK 的环境变量（NDK 根目录加入系统 PATH 变量），然后直接 在 jni 目录下打开终端（Windows 为 CMD），输入 `ndk-build clean`，然后 `ndk-build` 即可在构建出所需要的 libfoo.so，此时可以直接运行 apk 工程，新的 libfoo.so 将自动被加入 apk 的 libs 目录中。

可以发现在构建过程中，和 jni 同级的目录中产生了 obj 目录，这是构建产生的一些临时目标文件，`ndk-build clean` 的作用是清里这些临时文件，同时清理上一次构建的 libfoo.so。



# Android.mk 变量参考

## NDK 定义的 include 变量

## 目标信息变量

## 模块描述变量



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
#ifndef NDKTPROJECT_LIBFOO_H
#define NDKTPROJECT_LIBFOO_H

#include <jni.h>

extern "C" {

JNIEXPORT void JNICALL
Java_io_l0neman_mkexample_NativeHandler_test(JNIEnv *env, jclass clazz);

};

#endif //NDKTPROJECT_LIBFOO_H
```

```cpp
// libfoo.cpp
#include "libbar.h"
#include "libfoo.h"

void Java_io_l0neman_mkexample_NativeHandler_test(JNIEnv *env, jclass clazz) {
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

  public static native void test();
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
