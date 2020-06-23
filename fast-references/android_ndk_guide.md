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

添加 Application.mk 用于描述 NDK 工程概要设置。

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

这样的话就完成了一个基本的 NDK 工程搭建，编译后调用代码即可获取 java 字符串。

```java
String hello = NativeHandler.getHello();
```



## CMake

使用 CMake 和 Android.mk 在 Android Studio 中的构建步骤类似。

todo：暂未补充



## 独立工具链

有时编译 NDK 工程有一些特殊需求，例如对代码进行混淆，加入依赖于第三方编译器 Obfuscator-LLVM 对 NDK 工程代码进行混淆，这时就需要搭建第三方工具链的编译环境，将它加入 NDK 的一般构建过程中。

todo：暂未补充



## 构建技巧

### 独立构建

在前面 Android.mk 的工程中，需要在 Module 级别的 gradle 配置如下 Android.mk 路径：

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

而且这样必须依赖于 Android Studio 这个 IDE 所提供的集成环境，使用如下办法要避免以这种方式构建：

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

这样就可以单独构建 so，而不用考虑 android studio，可以结合自动化脚本搭建自动构建。



### 快速构建

对于一个主要由 native 代码构成的应用来说，修改 native 代码的动作较为频繁，如果每次都 clean 然后重新 build，再依赖于 android studio 的运行安装会比较麻烦和耗时。有时还需要依赖于其他 IDE 来构建 NDK 工程，那么可以采用如下方法：

首次构建 NDK 工程后安装运行到手机上，然后后面每次构建出 so，使用 adb 命令直接将 so 文件 push 到应用的沙盒目录下，重新启动应用进程即可使用新版的 so 文件。

```
adb push libfoo.so /data/data/io.l0neman.mkexample/lib/
```

注意 so 文件的架构应与当前应用对应。

不过这样做的前提是设备拥有 root 权限，也可直接使用官方的 Android 模拟器，下载带有 google api 的模拟器 ROM，输入如下命令即可获取 root 权限。

```
adb root
adb remount
```

之后 adb 将以 root 用户的身份运行。



# Android.mk 变量参考

## 变量命名规范

NDK 构建系统保留了如下变量名称，在定义自己的变量时尽量避免这些规则：

1. 以 `LOCAL_` 开头的名称，例如 `LOCAL_MODULE`；
2. 以 `PRIVATE_`、`NDK_` 或 `APP` 开头的名称，构建系统内部使用了这些变量名；
3. 小写名称，例如 `my-dir`，构建系统内部使用了这些变量名。

最好以 `MY_` 附加在自己的变量开头。



## NDK 定义的 include 变量

- CLEAR_VARS

此变量指向一个用于清理变量的脚本，当包含它时，会清理几乎所有的 `LOCAL_XXX` 变量，不包含 `LOCAL_PATH` 变量，一般在描述新模块之前包含。

```makefile
include $(CLEAR_VARS)
```

- BUILD_EXECUTABLE

指明构建的产出物是一个可执行文件（无文件后缀名），需要在源代码中包含一个 main 函数。通常构建可执行文件用来测试或用于其他调试工具。

```cpp
// foo.cpp
int main(int argv, char **args) {
  printf("Hello World!\n");
  return 0;
}
```

```makefile
include $(BUILD_EXECUTABLE)
```

- BUILD_SHARED_LIBRARY

指明构建的产出物是一个共享库（文件后缀为 .so），它会随着应用代码打包至 apk 中。

```
include $(BUILD_SHARED_LIBRARY)
```

- BUILD_STATIC_LIBRARY

指明构建的产出物是一个静态库（文件后缀为 .a），它不会被打包至 apk 中，只是为了被其他 native 模块引用。

- PREBUILT_SHARED_LIBRARY

用于描述预编译共享库的构建，此时 `LOCAL_SRC_FILES` 变量指向预编译库的路径。

```
LOCAL_SRC_FILES := $(TARGET_ARCH_ABI)/libbar.so
include $(PREBUILT_SHARED_LIBRARY)
```

- PREBUILT_STATIC_LIBRARY

用于描述预编译静态库的构建，此时 `LOCAL_SRC_FILES` 变量指向预编译库的路径。

```
LOCAL_SRC_FILES := $(TARGET_ARCH_ABI)/libbar.a
include $(PREBUILT_STATIC_LIBRARY)
```



## 目标信息变量

构建系统会根据 `APP_ABI` 变量（在 Application.mk 中定义）指定的每个 ABI 分别解析一次 Android.mk，如下变量将在构建系统每次解析时被重新定义值。

- TARGET_ARCH

对应 CPU 系列，为 `arm`、`arm64`、`x86`、`x86_64`。

- TARGET_PLATFORM

指向 Android API 级别号，例如 Android 5.1 对应 22。可以这样使用：

```makefile
ifeq ($(TARGET_PLATFORM),android-22)
    # ... do something ...
endif
```

- TARGET_ARCH_ABI

对应每种 CPU 对应架构的 ABI。

| CPU and architecture | Setting     |
| -------------------- | ----------- |
| ARMv7                | armeabi-v7a |
| ARMv8 AArch64        | arm64-v8a   |
| i6686                | x86         |
| x86-64               | x86_64|

检查 ABI：

```makefile
ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
  # ... do something ...
endif
``` 

- TARGET_ABI

目标 Android API 级别与 ABI 的串联值。检查在 Android API 级别 22 上运行的 64 位 ARM 设备：

```makefile
ifeq ($(TARGET_ABI),android-22-arm64-v8a)
  # ... do something ...
endif
```



## 模块描述变量

下面的变量用于向构建系统描述如可构建一个模块，每个模块都应遵守如下流程：

1. 使用 CLEAR_VARS 变量清理与上一个模块相关的变量；
2. 为用于描述模块的变量赋值；
3. 包含 BUILD_XXX 变量以适当的构建脚本用于该模块的构建。

- LOCAL_PATH

用于指定当前文件的路径，必须在 Android.mk 文件开头定义此变量。

`CLEAR_VARS` 指向的脚本不会清除此变量。

```makefile
# my-dir 是一个宏函数，返回当前 Android.mk 文件路径
LOCAL_PATH := $(call my-dir)
```

- LOCAL_MODULE

用于向构建系统描述模块名称，对于 .so 和 .a 文件，系统会自动给名称添加 `lib` 前缀和文件扩展名。

```makefile
# 产出 libfoo.so 或 libfoo.a
LOCAL_MODULE := foo
```

- LOCAL_MODULE_FILENAME

向构建系统描述模块的自定义名称，覆盖 `LOCAL_MODULE` 的名称。

```makefile
LOCAL_MODULE := foo
# 产出 libnewfoo.so，但无法改变扩展名
LOCAL_MODULE_FILENAME := libnewfoo
```

- LOCAL_SRC_FILES

向构建系统描述生成模块时所用的源文件列表，务必使用 Unix 样式的正斜杠 (/) 来描述路径，且避免使用绝对路径。

- LOCAL_CPP_EXTENSION

为 C++ 源文件指定除 .cpp 外的扩展名。

```makefile
LOCAL_CPP_EXTENSION := .cxx
```

或指定多个：

```makefile
LOCAL_CPP_EXTENSION := .cxx .cpp .cc
```

todo


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
