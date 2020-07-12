# Android JNI 指南

- [Android JNI 指南](#android-jni-指南)
- [前言](#前言)
- [JNI 优化原则](#jni-优化原则)
- [名词说明](#名词说明)
- [JavaVM 和 JNIEnv](#javavm-和-jnienv)
- [JNI 方法注册](#jni-方法注册)
  - [静态注册](#静态注册)
  - [动态注册](#动态注册)
  - [类静态方法和类成员方法](#类静态方法和类成员方法)
- [Java 层访问](#java-层访问)
  - [Java 成员变量访问](#java-成员变量访问)
  - [Java 类方法访问](#java-类方法访问)
  - [Java 层访问实例](#java-层访问实例)
  - [访问优化](#访问优化)
  - [JNI 类型](#jni-类型)
    - [基本数据类型](#基本数据类型)
    - [引用类型](#引用类型)
    - [方法和类成员 ID](#方法和类成员-id)
    - [数组元素](#数组元素)
  - [引用管理](#引用管理)
    - [局部引用](#局部引用)
    - [全局引用](#全局引用)
    - [提示](#提示)
- [Java 常用数据访问](#java-常用数据访问)
  - [访问字符串](#访问字符串)
    - [获取字符串](#获取字符串)
    - [提示](#提示-1)
    - [返回字符串](#返回字符串)
  - [访问数组](#访问数组)
    - [提示](#提示-2)
    - [注意](#注意)
    - [数组区域调用](#数组区域调用)
- [线程](#线程)
    - [附加到本地线程](#附加到本地线程)
- [JNI 异常](#jni-异常)
    - [检查异常](#检查异常)
    - [抛出异常](#抛出异常)
- [参考](#参考)



# 前言

编写此文档的用意：

作为 Android NDK 项目开发的参考手册。

对于 NDK 工程的搭建可参考 Android NDK 指南



# JNI 简介

JNI（Java Native Interface，Java 原生接口），是 Java 和 C++ 组件用以互相通信的接口。

Android 平台下的 JNI 支持由 Android NDK 提供，它是一套能将 C 或 C++（原生代码）嵌入到 Android 应用中的工具。

为什么要使用 JNI 在 Android 平台下进行编程：

1. 在平台之间移植应用；
2. 重复使用现有库，或者提供自己的库供重复使用；
3. 在某些情况下提供高性能，特别是像游戏这种计算密集型应用；
4. 提供安全性保障，在二进制层面比字节码层面的逆向工作更加困难。



# JNI 优化原则

1. 尽可能减少跨 JNI 层的编组（Marshalling）数据资源的次数，因为跨 JNI 层进行编组的开销很大。尽可能设计一种接口，减少需要编组的数据量以及必须进行数据编组的频率；
2. 尽量避免在使用受管理的编程语言（在虚拟机中运行）中与 C/C++ 编写的代码之间进行异步通信（例如 C/C++ 中开启线程后直接回调 Java 语言），这样可以使 JNI 接口更容易维护。通常使用与编写界面的相同语言进行异步更新，以简化异步界面的更新，例如，使用 Java 语言创建线程，然后发出对 C++ 层的阻塞调用，然后在阻塞完成后通知界面线程；
3. 尽可能减少需要访问 JNI 或被 JNI 访问的线程数。如果确实需要以 Java 和 C++ 两种语言来利用线程池，请尝试在池所有者之间（而不是各个工作线程之间）保持 JNI 通信；
4. 将接口保存在少量的容易识别的 C++ 和 Java 源位置，以便于将来进行重构。



# 名词说明

下文叙述中使用到的名词说明：

- JNI 方法，在 Java 层使用 native 声明，使用 C/C++ 中实现的方法。

- JNI 函数，JNI 提供的与 Java 层交互的工具一系列函数，例如 `RegisterNatives`。

- 不透明，具体结构未知，由具体的虚拟机实现决定。



# JavaVM 和 JNIEnv

JNI 定义了两个关键的数据结构，`JavaVM` 和 `JNIEnv`，它们的本质都是指向函数表的二级指针（在 C++ 版本中，两者都是类，类中都有一个指向函数表的指针，它们的成员函数封装了通过函数表进行访问的 JNI 函数），可以使用 `JavaVM` 类进行创建和销毁 JavaVM 的操作。理论上，每个进程可以有多个 JavaVM，但 Android 只允许有一个。

`JNIEnv` 的指针将在每个 JNI 函数的第一个参数中。

这个 `JNIEnv` 只能用于线程本地存储（Thread Local），所以无法在线程之间共享 `JNIEnv`，如果需要在其他线程中访问 `JNIEnv`，可以通过 `JavaVM` 调用 `GetEnv` 函数获得相应的 `JNIEnv` 指针（需要在之前使用过 `AttachCurrentThread` 对此线程进行附加后调用）。

`JavaVM` 指针是全局的，可以在线程之间共享，通过保存 `JavaVM` 用于在其他线程中获取 `JNIEnv`。

`JNIEnv` 和 `JavaVM` 在 C 源文件和 C++ 源文件中的声明不同，使用 C 文件和 C++ 文件包含 `jni.h` 时，会有不同的类型定义。

```c++
// jni.h

#if defined(__cplusplus)
typedef _JNIEnv JNIEnv;
typedef _JavaVM JavaVM;
#else
typedef const struct JNINativeInterface* JNIEnv;
typedef const struct JNIInvokeInterface* JavaVM;
#endif
```

因此，不建议同时在这两种语言包含的头文件中添加 `JNIEnv` 参数（容易导致混乱）。或者当源文件中出现 `#ifdef __cplusplus` ，且该文件中所有的内容都引用了 `JNIEnv` 时，那么可能需要做额外的处理。



# JNI 方法注册

JNI 方法是 Java 代码与 C/C++ 代码沟通的桥梁，使用它时必须首先注册。JNI 方法的声明在 Java 类中，实现在 C/C++ 代码中，在 Java 层的方法声明前面必须添加 `native` 关键字，然后才能进行注册。

注册方式分为静态注册（根据 JNI 命令规范直接定义对应名字的 C/C++ 函数）和动态注册（使用 `RegisterNatives` 函数注册到 C/C++ 函数上）两种方式。

例如，Java 声明了如下 JNI 方法：

```java
// io.l0neman.jniexample.NativeHandler

public class NativeHandler {

  static {
    System.loadLibrary("hello");
  }

  // 期望 JNI 返回一个字符串。
  public static native String getString();
}
```

NDK 工程描述如下：

```
src/main/
 |
 +-- java
 +-- jni
      |
      +-- Android.mk
      +-- Application.mk
      +-- hello.cpp
      +-- hello.h
```



```makefile
# Android.mk

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE    := hello
LOCAL_SRC_FILES := hello.cpp

include $(BUILD_SHARED_LIBRARY)
```

下面将针对上面搭建的 NDK 工程，采用两种方式在 C/C++ 代码中实现 Java 类 `NativeHandler` 中的 `getString` 方法并注册。



## 静态注册

静态注册只需要按照 JNI 接口规范，在 C/C++ 代码中声明一个 `Java_[全类名中 的 . 替换为 _]_[方法名]` 函数，然后添加 `JNIEXPORT` 前缀即可。

当系统加载 so 文件后，将根据名字对应规则，自动注册 JNI 方法。

下面采用了 C++ 代码描述，其中的函数需要使用 `extern "C"` 来包括（为了兼容 C 语言的符号签名规则，让 C 语言能够正常链接调用它）。

```c++
// hello.h

#ifndef NDKTPROJECT_MAIN_H
#define NDKTPROJECT_MAIN_H

#include <jni.h>

extern "C" {
JNIEXPORT jstring JNICALL
Java_io_l0neman_jniexample_NativeHandler_getString(JNIEnv *env, jclass clazz);
}

#endif //NDKTPROJECT_MAIN_H
```



```c++
// hello.cpp

#include "main.h"

extern "C" {
jstring Java_io_l0neman_jniexample_NativeHandler_getString(JNIEnv *env, jclass clazz) {
  return env->NewStringUTF("hello");
}
}
```

如果是 C 语言代码的实现，那么可以去除 `extern "C"` 的声明，且返回字符串的代码要改为：

```c
// 此时 C 语言中的 env 不是类，只是一个指向函数表的指针
return (*env)->NewStringUTF(env, "hello");
```

此时就注册完成了，Java 层可以直接调用 `textView.setText(NativeHandler.getString())` 进行测试了。

这种注册方式简单直接，但是所有 C/C++ 中实现的 JNI 函数符号都需要被导出，对于逆向人员来说，使用 IDA Pro 可以直接看到注册 JNI 方法的名字，快速定位到对应的 Java 代码。



## 动态注册

动态注册与静态注册不同，它是用 `JNIEnv` 类型提供的 `registerNatives` 方法来将 JNI 方法动态绑定到指定的 C/C++ 函数上。

首先需要实现 JNI 提供的标准入口函数，`JNI_OnLoad`，它将会在调用 `System.loadLibrary("hello")` 后，由 Java 虚拟机进行回调，同时可以实现可选的 `JNI_OnUnload` 函数，用于虚拟机将动态库卸载时回收资源。

```c++
// hello.cpp

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
  return JNI_VERSION_1_6;
}
```

返回值表示要使用的 JNI 版本，返回低版本，将不能使用高版本提供的一些 JNI 函数，这里返回当前最高版本 `JNI_VERSION_1_6`，如果返回其它非版本数值，将导致加载 so 库失败。

完整注册代码如下：

```c++
// hello.cpp

static const char *CLASS_NAME = "io/l0neman/jniexample/NativeHandler";

static jstring getString(JNIEnv *env, jclass nativeHandler) {
  return env->NewStringUTF("hello");
}

static JNINativeMethod gMethods[] = {
    {"getString", "()Ljava/lang/String;", (void *) getString},
};

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
  JNIEnv *env = nullptr;
  if (vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
    return JNI_ERR;
  }

  jclass nativeHandlerClass = env->FindClass(CLASS_NAME);
  if (nativeHandlerClass == nullptr) {
    return JNI_ERR;
  }

  jint methods = sizeof(gMethods) / sizeof(JNINativeMethod);
  jint ret = env->RegisterNatives(nativeHandlerClass, gMethods, methods);
  if (ret != JNI_OK) {
    return ret;
  }

  return JNI_VERSION_1_6;
}

JNIEXPORT void JNI_OnUnload(JavaVM *vm, void *reserved) {
  // 回收工作
}
```

从 `JNI_OnLoad` 开始看。

1. 首先 `RegisterNatives` 这个函数由 `JNIEnv` 类型提供，而 `JNI_OnLoad` 第一个参数是 `JavaVM *`，所以，这里首先获取 `JNIEnv` 类型指针，使用 `JavaVM` 的 `GetEnv` 函数获取（由于系统默认已经附加到线程，所以这里才能直接 `GetEnv`）;
2. 下面需要使用 `RegisterNatives` 注册 JNI 函数，看一下它的声明：

```c++
// jni.h

jint RegisterNatives(jclass clazz, const JNINativeMethod* methods, jint nMethods);
```

第 1 个参数是 JNI 方法所在的 Java 类，第 2 个是包含需要注册的 JNI 方法对应关系的数组，第 3 个是要注册的 JNI 方法数量或者说前面的数组大小。

那么，就根据要求填充相关参数。

3. 使用 `JNIEnv` 的 `FindClass` 来获得表示 `NativeHandler` 类型的 `jclass`，可以看到描述全类名的方法，将 `.` 替换为路径符号 `/` 即可，这样得到了第一个参数；
4. 定义一个 `JNINativeMethod` 的数组，每个 `JNINativeMethod` 都用于描述一个 JNI 方法的 Java 方法声明和 C/C++ 函数的一对一关系。

`JNINativeMethod` 定义如下：

```c++
// jni.h

typedef struct {
    const char* name;
    const char* signature;
    void*       fnPtr;
} JNINativeMethod;
```

分别是 Java 层 JNI 方法的名字，方法签名，和要注册的 C/C++ 函数地址。

在方法签名中，每种 Java 基本类型都有对应的签名字符串，引用类型则为 `L[全类型名中的 . 替换为 /];`。

JNI 类型签名如下表：

| 签名 | Java 类型 |
| ---- | --------- |
| Z    | boolean   |
| B    | byte      |
| C    | char      |
| S    | short     |
| I    | int       |
| J    | long      |
| F    | float     |
| D    | double    |
| L    | 引用类型  |
| [    | 数组前缀  |

示例：

```java
long f (int n, String s, int[] arr); 
```

签名为：

```
(ILjava/lang/String;[I)J 
```

那么前面的代码中的 `gMethods` 数组，即表明了要把 `NativeHandler` 中的 `getString` 注册绑定到 C++ 中的 `getString` 函数上。

5. 最后调用 `env->RegisterNatives` 函数就可以了，一般情况下，注册成功，那么返回 `JNI_OK`。

可以允许在 `JNI_OnLoad` 中绑定多个 Java 类中的 native 方法，建议不要这样做，会导致难以维护。

动态注册的好处是，可以只导出 `JNI_OnLoad`（注册的 C/C++ 函数可以进行符号优化，不导出），生成速度更快且更小的代码，且可避免与加载到应用中的其他库发生潜在冲突。



## 类静态方法和类成员方法

注册 Java 中的静态 JNI 方法和类成员 JNI 方法的区别是，对应的 C/C++ 函数的回调参数不同。

```c++
// io.l0neman.jniexample.NativeHander

public class NativeHandler {

  static {
    System.loadLibrary("hello");
  }

  public static native String getString();

  public native String getHello();
}
```

对应的 C++ 函数：

```c++
jstring getString(JNIEnv *env, jclass nativeHandler) {
  return env->NewStringUTF("hello");
}

jstring getHello(JNIEnv *env, jobject thiz) {
  jclass nativeHandlerClass = env->GetObjectClass(thiz);
  return env->NewStringUTF("hello");
}
```

静态方法传递的是代码 Java 类的 `jclass`，而类方法传递的是表示 Java `this` 对象的 `jobject`，可以使用它来访问对应的 `this` 对象内的成员变量和相关方法。如果需要访问 `jclass`，使用 JNI 提供的 `GetObjectClass` 函数获取。

在注册工作完成后，就可以从 Java 层调用 JNI 方法，使用 C/C++ 语言处理逻辑了。



# Java 层访问

在 C/C++ 代码中，需要对 Java 层进行访问，最基本的两种访问操作就是读写 Java 类成员和调用 Java 类方法。



## Java 成员变量访问

JNI 提供了一系列访问 Java 类的静态成员和对象成员的函数，例如。

```c++
GetStaticIntField();     // 读取 Java 类型为 int 的类静态成员
SetStaticIntField();     // 写入 Java 类型为 int 的类静态成员
GetStaticObjectField();  // 读取类型为 Java 引用的类静态成员
SetStaticObjectField();  // 写入类型为 Java 引用的类静态成员

GetIntField();           // 读取 Java 类型为 int 的类对象成员
SetIntField();           // 写入 Java 类型为 int 的类对象成员
GetObjectField();        // 读取类型为 Java 引用的类对象成员
SetObjectField();        // 写入类型为 Java 引用的类对象成员
```

总结为：

```c++
GetStatic<type>Field();    // 读取 Java 类型为 type 的类静态成员
SetStatic<type>Field();    // 写入 Java 类型为 type 的类静态成员
Get<type>Field();          // 读取 Java 类型为 type 的类对象成员
Set<type>Field();          // 写入 Java 类型为 type 的类对象成员
```

当需要访问静态成员时需要提供一个代表 Java 类型的 `jclass` 作为参数，访问类对象成员时则需要一个表示 Java 对象的 `jobject` 作为参数。

同时两者都需要首先提供目标 Java 类成员的 JNI 类型签名（符合上面的 JNI 签名表规则），用来获取一个不透明的 `jfieldID` 类型，传递给 JNI 函数，用于找到目标成员，之后才能使用上述 JNI 函数访问 Java 类成员。

```c++
jfieldID GetStaticFieldID(jclass clazz, const char* name, const char* sig);
```



## Java 类方法访问

JNI 同时也提供了一系列调用 Java 类的静态方法和对象方法的函数，例如：

```c++
CallStaticVoidMethod(); // 调用返回值类型为 void 的静态方法
CallStaticIntMethod();  // 调用返回值类型为 int 的静态方法
CallObjectMethod();     // 调用返回值类型为 Java 引用的静态方法
// ...

CallVoidMethod();       // 调用返回值类型为 void 的对象方法
CallIntMethod();        // 调用返回值类型为 int 的对象方法
CallObjectMethod();     // 调用返回值类型为 Java 引用的成员方法
// ...
```

总结为：

```c++
env->CallStatic<type>Method(); // 调用返回值类型为 type 的静态方法
env->Call<type>Method();       // 调用返回值类型为 type 的成员方法
```

当需要调用静态方法时需要提供一个代表 Java 类型的 `jclass` 作为参数，调用类成员方法时则需要一个表示 Java 对象的 `jobject` 作为参数。

同时两者都需要首先提供目标 Java 方法的 JNI 签名（符合上面的 JNI 签名表规则），用来获取一个不透明的 `jMethodID` 类型，传递给 JNI 函数，用于找到目标方法，之后才能使用上述 JNI 函数调用 Java 类方法。



## Java 层访问实例

下面对实际的 Java 类成员和方法进行访问和调用。

首先定义一个 Java 类，`JniCallExample`。

```java
// io.hexman.jniexample.JniCallExample

public class JniCallExample {
  private static int sFlag = 256;

  private String mData = "info";

  public String getData() {
    return mData;
  }

  public static boolean setHello(String hello) {
    return "hello".equals(hello);
  }
}
```

`JniCallExample` 类具有一个静态成员 `sFlag`，和成员变量 `mData`，还包含一个 `getData` 成员方法和一个静态方法。

那么下面将进行如下操作：

1. 读取 `sFlag` 的值并打印；
2. 改变 `mData` 的值，然后调用 Java 层的 `getData` 方法，获得修改后的值；
3. 调用 Java 层的 `sayHello` 方法，传递 `hello` 字符串，获得方法返回值。

这里需要在 C/C++ 代码中打印变量，所以需要使用 NDK 提供的 `liblog` 库，Android.mk 如下：

```makefile
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE    := hello
LOCAL_SRC_FILES := hello.cpp

# 此行表示依赖 liblog 库
LOCAL_LDLIBS := -llog

include $(BUILD_SHARED_LIBRARY)
```

下面开始编写源代码。

首先在 `NativeHandler` 类里面，声明 JNI 方法 `void testAccessJava(JniCallExample jniCallExample)`，用于调用 C/C++ 代码来启动测试。

其中提供一个 `JniCallExample` 对象，是因为需要访问它的成员值。

```java
// io.hexman.jniexample.NativeHandler

public class NativeHandler {

  static {
    System.loadLibrary("hello");
  }

  public static native void testAccessJava(JniCallExample jniCallExample);
}
```

然后在 C++ 代码中定义对应的 JNI 方法的实现函数，并在 `JNI_OnLoad` 中注册函数。

```c++
// hello.cpp

static const char *CLASS_NAME = "io/l0neman/jniexample/NativeHandler";

static JNINativeMethod gMethods[] = {
    {"testAccessJava", "(Lio/l0neman/jniexample/JniCallExample;)V", (void *) testAccessJava},
};

void testAccessJava(JNIEnv *env, jobject nativeHandler) {
  // ...
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
  JNIEnv *env = nullptr;
  if (vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
    return JNI_ERR;
  }

  jclass nativeHandlerClass = env->FindClass(CLASS_NAME);
  if (nativeHandlerClass == nullptr) {
    return JNI_ERR;
  }

  jint methods = sizeof(gMethods) / sizeof(JNINativeMethod);
  jint ret = env->RegisterNatives(nativeHandlerClass, gMethods, methods);
  if (ret != JNI_OK) {
    return ret;
  }

  return JNI_VERSION_1_6;
}
```

下面填充 `testAccessJava` 的逻辑：

```c++
// hello.cpp

static const char *TAG = "TAJ";

// 用于输出 Java 字符串（mData）的工具函数
void utilPrintJavaStr(JNIEnv *env, jstring jStr) {
  const char *mDataCChar = env->GetStringUTFChars(jStr, nullptr);  // str+
  // 这里需要把 Java 字符串转为 C 字符串才能输出
  __android_log_print(ANDROID_LOG_DEBUG, TAG, "jniCallExample.mData: %s", mDataCChar);
  env->ReleaseStringUTFChars(jStr, mDataCChar);                    // str-
}

void testAccessJava(JNIEnv *env, jclass nativeHandler, jobject jniCallExample) {
  jclass jniCallExampleClass = env->FindClass("io/l0neman/jniexample/JniCallExample");

  jfieldID sFlagStaticFieldId = env->GetStaticFieldID(jniCallExampleClass, "sFlag", "I");
  // Java: int sFlag = JniCallExample.sFlag;
  jint sFlag = env->GetStaticIntField(jniCallExampleClass, sFlagStaticFieldId);
  __android_log_print(ANDROID_LOG_DEBUG, TAG, "JniCallExample.sFlag: %d", sFlag);

  jfieldID mDataFieldId = env->GetFieldID(jniCallExampleClass, "mData", "Ljava/lang/String;");
  // Java: newData = "data;
  jstring newData = env->NewStringUTF("data");
  // Java: jniCallExample.mData = newData;
  env->SetObjectField(jniCallExample, mDataFieldId, newData);

  jmethodID getDataMethodId = env->GetMethodID(jniCallExampleClass, "getData", "()Ljava/lang/String;");
  // Java: String newMData = jniCallExample.getData();
  jstring newMData = (jstring) env->CallObjectMethod(jniCallExample, getDataMethodId);
  utilPrintJavaStr(env, newMData);

  jmethodID setHelloStaticMethodId = env->GetStaticMethodID(jniCallExampleClass, "setHello", "(Ljava/lang/String;)Z");
  // JavaL helloParam = "hello";
  jstring helloParam = env->NewStringUTF("hello");
  // Java: JniCallExample.setHello(helloParam);
  jboolean isSetHello = (jboolean) env->CallStaticBooleanMethod(jniCallExampleClass, setHelloStaticMethodId, helloParam);
  __android_log_print(ANDROID_LOG_DEBUG, TAG, "isSetHello %d", isSetHello);
}
```

打印出如下结果：

```
JniCallExample.sFlag: 256
jniCallExample.mData: data
isSetHello 1
```



其中注释 `Java: xxx` 表示与 Java 代码有相同作用。

其中包含一部分对于字符串的操作：

`env->NewStringUTF("data")` 用于创建一个 Java 字符串（new String()），它的内存由 Java 虚拟机管理，它使用 `jstring` 类型来描述，是一个 JNI 提供的不透明类型，用于映射一个 Java 字符串。每种 Java 类型都有对应的映射类型（下面会提供映射表），这里用作 Java 变量来给 Java 变量赋值或者作为参数传递。

`env->GetStringUTFChars(jStr, nullptr);` 用于从 Java 字符串中取得 C 形式的 Modified_UTF-8（下文介绍）字符串，它将会在 native 层分配内存，而不是由 Java 虚拟机管理，所以使用后需要手动使用 `ReleaseStringUTFChars` 释放。



## 访问优化

在对 Java 层进行访问时，不管是访问 Java 类成员还是调用 Java 方法，都需要首先使用 `FindClass` 找到目标 Java 类，然后获取对应的成员 ID 和方法 ID， 对于 `FindClass` 和查找相关 ID 的函数，每次调用它们可能都需要进行多次的字符串比较，而使用这些 ID 去访问对于的 Java 类成员和方法速度却是很快的。

那么如果需要多次访问相同的 Java 目标，那么考虑将这些 `jclass`（FindClass 的结果）和相关 ID 缓存起来。 这些变量在被访问的 Java 类被卸载之前保证是有效的。只有在与 ClassLoader 关联的所有类都满足垃圾回收条件时，系统才会卸载这些类，这种情况比较少见，但在 Android 中是有可能出现的。

Android 推荐的方法是，在 Java 类中声明一个名叫 `nativeInit` 的 JNI 方法，在类的静态块内调用，这个 JNI 方法就负责提前缓存要使用的 Java 类型，那么一个类被加载时，`nativeInit` 就会被调用。

可以在 Android 系统源码中看到许多名叫 `nativeInit` 的 JNI 方法，它们就是负责此用途的。

一般使用 `static` 结构来缓存这些 ID 和 `jclass`，`jclass` 作为 Java 引用，需要使用 `NewGlobalRef` 函数创建一个全局引用来保护它不被回收。



那么现在改进之前的 Java 访问实例，如下：

首先在 `NativeHandler` 中增加 `nativeInit` 方法。


```java
// io.hexman.jniexample.NativeHandler

public class NativeHandler {

  static {
    System.loadLibrary("hello");
    nativeInit();
  }
  
  public static native void nativeInit();

  public static native void testAccessJava(JniCallExample jniCallExample);
}
```

然后是源代码，注册部分只修改 `JNINativeMethod` 数组即可：

```cpp
static JNINativeMethod gMethods[] = {
    {"testAccessJava", "(Lio/l0neman/jniexample/JniCallExample;)V", (void *) testAccessJava},
    {"nativeInit",     "()V",                                       (void *) nativeInit}
};
```

然后是 `nativeInit` 的逻辑和修改过的 `testAccessJava` 函数的实现。

```cpp
// hello.cpp

// 缓存结构体
struct JniCallExampleHolder {
    jclass jniCallExampleClass;
    jfieldID sFlagStaticFieldId;
    jfieldID mDataFieldId;
    jmethodID getDataMethodId;
    jmethodID setHelloStaticMethodId;
};

static JniCallExampleHolder gJniCallExampleHolder;

// 提前缓存 jclass 和访问 ID
void nativeInit(JNIEnv *env, jclass clazz) {
  jclass jniCallExampleClass = env->FindClass("io/l0neman/jniexample/JniCallExample");
  gJniCallExampleHolder.jniCallExampleClass = (jclass) env->NewGlobalRef(jniCallExampleClass);   // gr+
  gJniCallExampleHolder.sFlagStaticFieldId = env->GetStaticFieldID(jniCallExampleClass, "sFlag", "I");;
  gJniCallExampleHolder.mDataFieldId = env->GetFieldID(jniCallExampleClass, "mData", "Ljava/lang/String;");
  gJniCallExampleHolder.getDataMethodId = env->GetMethodID(jniCallExampleClass, "getData", "()Ljava/lang/String;");;
  gJniCallExampleHolder.setHelloStaticMethodId = env->GetStaticMethodID(jniCallExampleClass, "setHello", "(Ljava/lang/String;)Z");
}

void testAccessJava(JNIEnv *env, jclass nativeHandler, jobject jniCallExample) {

  // Java: int sFlag = JniCallExample.sFlag;
  jint sFlag = env->GetStaticIntField(gJniCallExampleHolder.jniCallExampleClass,
                                      gJniCallExampleHolder.sFlagStaticFieldId);
  __android_log_print(ANDROID_LOG_DEBUG, TAG, "JniCallExample.sFlag: %d", sFlag);

  // Java: newData = "data;
  jstring newData = env->NewStringUTF("data");
  // Java: jniCallExample.mData = newData;
  env->SetObjectField(jniCallExample, gJniCallExampleHolder.mDataFieldId, newData);

  // Java: String newMData = jniCallExample.getData();
  jstring newMData = (jstring) env->CallObjectMethod(jniCallExample, gJniCallExampleHolder.getDataMethodId);
  utilPrintJavaStr(env, newMData);

  // JavaL helloParam = "hello";
  jstring helloParam = env->NewStringUTF("hello");
  // Java: JniCallExample.setHello(helloParam);
  jboolean isSetHello = (jboolean) env->CallStaticBooleanMethod(gJniCallExampleHolder.jniCallExampleClass,
                                                                gJniCallExampleHolder.setHelloStaticMethodId,
                                                                helloParam);
  __android_log_print(ANDROID_LOG_DEBUG, TAG, "isSetHello %d", isSetHello);
}
```

其中有一个地方使用了 `env->NewGlobalRef` 建立了一个全局引用，它会保护这个 `jclass` 不会在 JNI 函数执行完之后被回收，注意需要在不使用的时候使用 `env->DeleteGlobalRef` 释放引用，例如 `JNI_OnUnload` 中。



## JNI 类型

每种 Java 类型在 JNI 中都有对应的本地数据类型，C/C++ 通过 JNI 方法与 Java 层进行交互时，均是使用这些类型进行参数传递，此时虚拟机再根据每种类型翻译为相应的 Java 类型传递给 Java 层方法.

还有一些特殊的数据类型用来存储 Java 方法 ID 和类成员 ID。



### 基本数据类型

| Java 类型 | 本地类型 | 说明            |
| --------- | -------- | --------------- |
| boolean   | jboolean | unsigned 8 bits |
| byte      | jbyte    | signed 8 bits   |
| char      | jchar    | signed 16 bits  |
| short     | jshort   | signed 16 bits  |
| int       | jint     | signed 32 bits  |
| long      | jlong    | signed 64 bits  |
| float     | jfloat   | 32 bits         |
| double    | jdouble  | 64 bits         |
| void      | void     | 无              |

`jboolean` 的两种取值：

```c++
#define JNI_FALSE  0 
#define JNI_TRUE   1 
```

`jsize` 类型用于描述数组大小或者索引。



从 `jni.h` 中看它们和真实 C/C++ 数据类型的对应关系：

```c++
// jni.h

/* Primitive types that match up with Java equivalents. */
typedef uint8_t  jboolean; /* unsigned 8 bits */
typedef int8_t   jbyte;    /* signed 8 bits */
typedef uint16_t jchar;    /* unsigned 16 bits */
typedef int16_t  jshort;   /* signed 16 bits */
typedef int32_t  jint;     /* signed 32 bits */
typedef int64_t  jlong;    /* signed 64 bits */
typedef float    jfloat;   /* 32-bit IEEE 754 */
typedef double   jdouble;  /* 64-bit IEEE 754 */

/* "cardinal indices and sizes" */
typedef jint     jsize;
```



### 引用类型

在 C++ 中，Java 引用类型使用一些类表示，它们的继承关系如下：

```c++
jobject                     (所有 Java 对象)
 |
 +-- jclass                (java.lang.Class 对象)
 +-- jstring               (java.lang.String 对象)
 +-- jarray                (数组)
 |    |
 |    +-- jobjectArray     (object 数组)
 |    +-- jbooleanArray    (boolean 数组)
 |    +-- jbyteArray       (byte 数组)
 |    +-- jcharArray       (char 数组)
 |    +-- jshortArray      (short 数组)
 |    +-- jintArray        (int 数组)
 |    +-- jlongArray       (long 数组)
 |    +-- jfloatArray      (float 数组)
 |    +-- jdoubleArray     (double 数组)
 |
 +- jthrowable             (java.lang.Throwable 对象)
```



源码中定义如下：

```c++
// jni.h

class _jobject {};
class _jclass : public _jobject {};
class _jstring : public _jobject {};
class _jarray : public _jobject {};
class _jobjectArray : public _jarray {};
class _jbooleanArray : public _jarray {};
class _jbyteArray : public _jarray {};
class _jcharArray : public _jarray {};
class _jshortArray : public _jarray {};
class _jintArray : public _jarray {};
class _jlongArray : public _jarray {};
class _jfloatArray : public _jarray {};
class _jdoubleArray : public _jarray {};
class _jthrowable : public _jobject {};

typedef _jobject*       jobject;
typedef _jclass*        jclass;
typedef _jstring*       jstring;
typedef _jarray*        jarray;
typedef _jobjectArray*  jobjectArray;
typedef _jbooleanArray* jbooleanArray;
typedef _jbyteArray*    jbyteArray;
typedef _jcharArray*    jcharArray;
typedef _jshortArray*   jshortArray;
typedef _jintArray*     jintArray;
typedef _jlongArray*    jlongArray;
typedef _jfloatArray*   jfloatArray;
typedef _jdoubleArray*  jdoubleArray;
typedef _jthrowable*    jthrowable;
typedef _jobject*       jweak;
```



在 C 语言中，所有 JNI 引用类型都与 jobject 的定义相同。

```c
// jni.h

typedef void*           jobject;
typedef jobject         jclass;
typedef jobject         jstring;
typedef jobject         jarray;
typedef jarray          jobjectArray;
typedef jarray          jbooleanArray;
typedef jarray          jbyteArray;
typedef jarray          jcharArray;
typedef jarray          jshortArray;
typedef jarray          jintArray;
typedef jarray          jlongArray;
typedef jarray          jfloatArray;
typedef jarray          jdoubleArray;
typedef jobject         jthrowable;
typedef jobject         jweak;
```



### 方法和类成员 ID

它们是不透明结构体指针类型：

```c++
// jni.h

struct _jfieldID;
typedef struct _jfieldID *jfieldID;
 
struct _jmethodID;
typedef struct _jmethodID *jmethodID;
```



### 数组元素

`jvalue` 用于作为参数数组中的元素类型：

```c++
// jni.h

typedef union jvalue {
    jboolean    z;
    jbyte       b;
    jchar       c;
    jshort      s;
    jint        i;
    jlong       j;
    jfloat      f;
    jdouble     d;
    jobject     l;
} jvalue;
```



## 引用管理

Java 对象在 JNI 中有两种引用方式，一种是局部引用；一种是全局引用。

### 局部引用

Java 层通过 JNI 方法传递给 C/C++ 函数的每个对象参数，以及 C/C++ 通过 JNI 函数（`Call<type>Method`）调用接收的 Java 方法的对象返回值都属于局部引用。

局部引用仅在当前线程中的当前 C/C++ 函数运行期间有效。在 C/C++ 函数返回后，即使对象本身继续存在，该引用也无效。

局部引用适用于 `jobject` 的所有子类，包括 `jclass`、`jstring` 和 `jarray`。



### 全局引用

创建全局引用只能使用 `NewGlobalRef` 和 `NewWeakGlobalRef` 函数。

如果希望长时间的持有某个引用，那么必须使用全局引用，使用 `NewGlobalRef` 函数时将局部引用作为参数传入，换取全局引用。在调用 `DeleteGlobalRef` 删除全局引用之前，此引用保证有效。

通常用于缓存 `FindClass` 返回的 `jclass`，就像前面的 Java 访问优化中所做的措施一样。

```cpp
jclass localClass = env->FindClass("MyClass");
jclass globalClass = reinterpret_cast<jclass>(env->NewGlobalRef(localClass)); 
```



### 提示

对于同一个对象的引用可能存在多个不同的值，例如，对于同一个对象多次调用 `NewGlobalRef` 所返回的值可能不同。

如果需要比较两个引用是否指向同一个对象，必须使用 `IsSameObject` 函数，切勿在 C/C++ 代码中使用 `==` 比较各个引用。

在两次调用 `NewGlobalRef` 对同一个对象创建全局引用时，表示这个对象的 32 位值可能不同；而在多次调用 `NewGlobalRef` 创建不同对象的全局引用时，它们可能具有相同的 32 位值，所以不能将 `jobject` 用作 key 使用。

不要过度分配局部引用，如果需要创建大量引用，应该主动调用 `DeleteLocalRef` 删除它们，而不是期望 JNI 自动删除。JNI 默认实现只能保留 16 个局部引用，如果需要保存更多数量，可以按照需要删除，或使用 `EnsureLocalCapacity/PushLocalFrame` 申请保留更多引用数量。

`jfieldID` 和 `jmethodID` 为不透明类型，不属于对象引用，所以不能使用 `NewGlobalRef` 保护。`GetStringUTFChars` 和 `GetByteArrayElements` 返回的原始数据指针也不属于对象。

一种特殊情况是，如果使用 `AttachCurrentThread` 附加到 C/C++ 线程，那么在线程分离之前，运行中的代码一定不会自动释放局部引用。代码创建的任何局部引用都必须手动删除。通常，在循环中创建局部引用的任何 C/C++ 代码需要执行某些手动删除操作。

谨慎使用全局引用。全局引用不可避免，但它们很难调试，并且可能会导致难以诊断的内存（不良）行为。在所有其他条件相同的情况下，全局引用越少，解决方案的效果可能越好。



# Java 常用数据访问

对 Java 字符串和数组的访问方法。访问这些数据是 JNI 开发的基础。

## 访问字符串

访问字符串有如下两种情况：

1. Java 层调用 JNI 方法，String 对象以 `jstring` 的形式传入 JNI 方法，此时 C/C++ 语言接收使用；
2. C/C++ 产生字符串数据，返回给 Java 层使用。

代码如下：

```java
// Java Code

// hello = "result"
final String hello = NativeHandler.testAccessString("hello");
```



```c++
// C++ Code

jstring testAccessString(JNIEnv *env, jclass clazz, jstring hello) {
  const char *stringChars = env->GetStringUTFChars(hello, nullptr);  // str+
  __android_log_print(ANDROID_LOG_DEBUG, TAG, "java string: %s", stringChars);
  env->ReleaseStringUTFChars(hello, stringChars);                    // str-

  return env->NewStringUTF("result");
}
```



### 获取字符串

`GetStringUTFChars` 将返回 C/C++ 语言可以直接使用的 [Modified_UTF-8](https://docs.oracle.com/javase/7/docs/technotes/guides/jni/spec/types.html#wp16542) 格式字符串（Modified_UTF-8 格式是 JNI 提供的优化后的 UTF-8 格式字符串，优化后的编码对 C 代码友好，因为它将 `\u0000` 编码为 `0xc0 0x80`，而不是 `0x00`。这样做的好处是，可以依靠以 `\0` 终止的 C 样式字符串，非常适合与标准 libc 字符串函数配合使用。但缺点是，无法将任意 UTF-8 的数据传递给 JNI 函数）。

在使用 `GetStringUTFChars` 获取字符串后，JavaVM 为字符串在 native 层分配了内存，在字符串使用完毕后，必须使用 `ReleaseStringUTFChars` 释放内存，否则将会造成内存泄漏。

从 C/C++ 获取 Java 字符串的长度有两种方式，可直接使用 `GetStringUTFLength` 对 `jstring` 计算长度：

```c++
// Java Code

jstring hello;
jsize utfLength = env->GetStringUTFLength(hello);
```

或者使用 C/C++ 的 `strlen` 计算：

```c++
// C++ Code

const char *stringChars = env->GetStringUTFChars(hello, nullptr);
size_t utfLength = strlen(stringChars);
```

`GetStringUTFChars` 函数的第 2 个参数是一个 `jboolean` 类型的指针，表示关心是否创建了字符串的副本，如果创建了字符串的副本它会返回 `JNI_TRUE`，否则为 `JNI_FALSE`，不管是否创建，都需要 Release 操作，所以一般不会关心它的结果，传递 `nullptr` 即可（C 语言传递 `NULL`）。

```c++
// C++ Code

jboolean isCopy;
const char *stringChars = env->GetStringUTFChars(hello,);  // str+
if (isCopy == JNI_TRUE) {
  // 创建了字符串副本
} else if (isCopy == JNI_FALSE) {
  // 未创建字符串副本
}
```



### 提示

JNI 还提供了 `GetStringChars` 函数，它返回的是 UTF-16 字符串，使用 UTF-16 字符串执行操作通常会更快，但是 UTF-16 字符串不是以零终止的，并且允许使用 `\u0000`，因此需要保留字符串长度和返回的 `jchar` 指针。

一般的开发中几乎都使用 `GetStringUTFChars` 获取字符串。



### 返回字符串

如果需要返回给 Java 层字符串，使用 `env->NewStringUTF("result")` 即可，JavaVM 将会基于 C 字符串创建一个新的 `String` 的对象，它的内存由虚拟机管理。

注意传递给 `NewStringUTF` 的数据必须采用 Modified_UTF-8 格式。一种常见的错误是从文件或网络数据流中读取字符数据，在未过滤的情况下将其传递给 `NewStringUTF`。除非确定数据是有效的 Modified_UTF-8 格式（或 7 位 ASCII，这是一个兼容子集），否则需要剔除无效字符或将它们转换为适当的 Modified_UTF-8 格式。如果不这样做，UTF-16 转换可能会产生意外的结果（Java 语言使用的是 UTF-16）。默认状态下 CheckJNI 会为模拟器启用，它会扫描并在收到无效字符串输入时中止虚拟机。



## 访问数组

和访问 Java 成员类似，JNI 提供了一系列访问数组的函数：

```c++
GetIntArrayElements();
GetBooleanArrayElements();
GetDoubleArrayElements();
// ...
```

总结为：

```c++
Get<type>ArrayElements();
```

其中 `<type>` 中只能是 Java 的基本类型，不包含 `String` 以及其他引用类型。

下面分别使用 C/C++ 获取 Java 传递的 `int` 类型和 `String` 的数组，作为获取 Java 基本类型和引用类型数组的典型示例：

```java
// Java Code

int[] array0 = {1, 2, 3, 4, 5};
String[] array1 = {"a", "b", "c", "d", "e"};
NativeHandler.testAccessArray(array0, array1);
```



```c++
// C++ Code

void testAccessArray(JNIEnv *env, jclass clazz, jintArray array0, jobjectArray array1) {
  // 访问原始数组
  jint *elements0 = env->GetIntArrayElements(array0, nullptr);
  if(elements0 != nullptr) {
    jsize array0Length = env->GetArrayLength(array0);
    for (jint i = 0; i < array0Length; i++) {
      __android_log_print(ANDROID_LOG_DEBUG, TAG, "array0[%d] = %d", i, elements0[i]);
    }

    env->ReleaseIntArrayElements(array0, elements0, 0);
  }

  // 访问对象数组
  jsize array1Length = env->GetArrayLength(array1);
  for (jint i = 0; i < array1Length; i++) {
    jstring element = (jstring) env->GetObjectArrayElement(array1, i);
    const char *chars = env->GetStringUTFChars(element, nullptr);  // str+
    __android_log_print(ANDROID_LOG_DEBUG, TAG, "array1[%d] = %s", i, chars);
    env->ReleaseStringUTFChars(element, chars);                    // str-
  }
}
```

输出如下：

```
array0[0] = 1
array0[1] = 2
array0[2] = 3
array0[3] = 4
array0[4] = 5
array1[0] = a
array1[1] = b
array1[2] = c
array1[3] = d
array1[4] = e
```

代码比较清晰，可以看到基本类型的数组，直接可以使用 `Get<type>ArrayElements(...)` 获得一个数组的首地址，使用 `GetArrayLength` 获取数组长度后，即可像 C/C++ 原生数组一样使用指针遍历每一个元素。

在对原生类型的数组访问之后，需要调用 `Release<type>ArrayElements` 请求释放内存。

对象数组则没有提供 `Get<type>ArrayElements(...)` 的方法，但是它提供了获取单个元素的 `GetObjectArrayElement` 方法，那么也可以使用循环获取每个 `jobject` 元素，然后转换为原本的类型。

如果需要更改原生类型的数组元素值，直接修改获取 C/C++ 数组元素的值，JNI 将会把值复制回原始数据区中。

如果需要更改引用类型的数组元素值，JNI 提供了 `SetObjectArrayElement` 函数，可直接修改原始元素对象。

```c++
env->SetObjectArrayElement(array1, 1, env->NewStringUTF("hello"));
```



### 提示

JNI 为了在不限制虚拟机实现的情况下使接口尽可能高效，允许 `Get<type>ArrayElements(...)` 函数的调用在运行时直接返回指向实际数据元素的指针，或者分配一些内存创建数据的副本。

在调用 Release 之前，返回的原生数组指针保证可用，如果没有创建数据的副本，那么原生数组将被固定，在虚拟机整理内存碎片时不会调整原生数组的位置，Release 的时候需要进行判空操作，防止在 Get 数组失败时 Release 空指针。

`ReleaseIntArrayElements` 函数的最后一个函数的 `mode` 参数有三个，运行时执行的操作取决于返回的指针指向实际数据还是指向数据副本。

`mode` 以及对应的 Release 行为：

1. `0`

实际数据：取消数组元素固定。
数据副本：将数据复制回原始数据，释放包含副本的缓冲区。

2. `JNI_COMMIT`

实际数据：不执行任何操作。
数据副本：将数据复制回原始数据，不释放包含副本的缓冲区。

3. `JNI_ABORT`

实际数据：取消数组元素固定，不中止早期的写入数据。
数据副本：释放包含相应副本的缓冲区；对该副本所做的任何更改都会丢失。



通常传递 `0` 来保持固定和复制数组的行为一致，其他选项可以用来更好地控制内存，需要谨慎传递。


其中 `GetIntArrayElements` 的第 2 个参数，它类似于 `GetStringUTFChars` 的第 2 个参数，也是 `isCopy`，表示获取数组时是否创建了数据副本。

通常检查 `isCopy` 标志的原因有两个：

1. 了解是否需要在对数组进行更改后使用 `JNI_COMMIT` 调用 Release 函数，如果需要在对数组进行更改和仅使用数组内容的代码之间切换，则可以跳过释放缓冲区提交（更改数组数据后需要继续访问数组）；
2. 有效处理 `JNI_ABORT`，考虑可能需要获取一个数组，然后进行适当修改后，将数组的一部分传递给其他函数使用，最后舍弃对数组的修改。如果知道 JNI 为数组创建了副本，那么就不需要自己创建一个可被修改的副本，如果 JNI 传递的是实际数据的指针，那么就需要自己创建数组的副本。



### 注意

不能认为 `*isCopy` 为 `JNI_FALSE` 时就不需要调用 Release，这是一种常见误区。

如果 JNI 没有分配任何副本缓冲区，返回指向实际数据的指针，那么虚拟机必须固定实际数组的内存，此时垃圾回收器将不能移动内存，造成内存不能释放。

`JNI_COMMIT` 标记不会释放数组，最终还需要使用其他标记再次调用 Release。



### 数组区域调用

如果只想复制 Java 数组，使用 `Get<type>ArrayRegion` 更好。

通常使用 `Get<type>ArrayElements` 时，如果需要复制数组数据到外部的缓冲区中，代码如下：

```c++
jbyte* data = env->GetByteArrayElements(array, NULL);
if (data != nullptr) {
  memcpy(buffer, data, len);
  env->ReleaseByteArrayElements(array, data, JNI_ABORT);
}
```

这样会复制数组 `len` 长度的字节到 `buffer` 中，然后释放数组内存。其中 Get 调用可能会返回实际数组或者实际数组的副本，取决于运行时的情况，代码复制数据（那么上面的代码可能是第 2 次复制），那么这种情况下，使用 `JNI_ABORT` 确保不会再出现第 3 次复制。

使用 `Get<type>ArrayRegion` 函数不仅可以完成相同操作，而且不必考虑 Release 调用：

```c++
// 复制数组 len 长度的字节到缓冲区 buffer 中
env->GetByteArrayRegion(array, 0, len, buffer);
```

区域调用优点：

1. 只需要一个 JNI 调用，而不是两个，减少开销；
2. 不需要固定实际数组或额外复制数据；
3. 降低风险，不存在操作失败后忘记调用 Release 的风险。



除此之外，JNI 还提供了针对于字符串的区域调用函数，`GetStringUTFRegion` 或 `GetStringRegion` 将字符数据复制到 `String` 对象之外。



# 线程

所有线程都是 Linux 线程，由内核调度。线程通常从受虚拟机管理的代码启动（使用 `Thread#start()` 方法），但也可以在 native 层创建，然后通过 JNI 函数附加到 JavaVM。在 C/C++ 代码中例如使用 `pthread_create` 启动本地线程，然后调用 JNI 提供的 `AttachCurrentThread` 或 `AttachCurrentThreadAsDeamon` 函数，在附加之前，这个线程不会包含任何 `JNIEnv`，所以无法调用 `JNI`（`JNIEnv` 指针不能在多个线程中共享，只能分别附加，主线程默认已被附加）。

被附加成功的本地线程会构建 `java.lang.Thread` 对象并被添加到 Main ThreadGroup，从而使调试程序能够看到它。在已附加的线程上调用 `AttachCurrentThread` 属于空操作。

通过 JNI 附加的线程在退出之前必须调用 `DetachCurrentThread` 分离附加。如果直接对此进行编写代码会很麻烦，可以使用 `pthread_key_create` 定义在线程退出之前调用的析构函数，之后再调用 `DetachCurrentThread`。（将该 key 与 `pthread_setspecific` 配合使用，以将 `JNIEnv` 存储在线程本地存储中；这样一来，该 key 将作为参数传递到线程的析构函数中。）



### 附加到本地线程

下面是一个附加到线程的示例，使用 `pthread_create` 创建一个线程，并在线程执行代码中附加：

```java
// Java Code:

NativeHandler.testThread();
```



```c++
// C++ Code:

// 线程函数
static void *threadTest(void *arg) {
  JNIEnv *env = nullptr;
  // 尝试获得已附加的 JNIEnv
  jint ret = gJavaVM->GetEnv((void **) &env, JNI_VERSION_1_6);
  switch (ret) {
    case JNI_OK:
      __android_log_print(ANDROID_LOG_DEBUG, TAG, "获得了 JNIEnv.");
      break;

    case JNI_EDETACHED:
      ret = gJavaVM->AttachCurrentThread(&env, nullptr);
      if (ret == JNI_OK) {
        __android_log_print(ANDROID_LOG_DEBUG, TAG, "线程已附加. %ld", (long) pthread_getspecific(gKey));
      } else {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "线程附加失败，code: %d.", ret);
      }
      break;

    case JNI_EVERSION:
      __android_log_print(ANDROID_LOG_DEBUG, TAG, "错误 JNI_EVERSION.");
      break;

    default:
      __android_log_print(ANDROID_LOG_ERROR, TAG, "未知错误：%d", ret);
      break;
  }

  return nullptr;
}

// 线程销毁函数
static void threadDestroy(void *arg) {
  JNIEnv *env = nullptr;
  jint ret = gJavaVM->GetEnv((void **) &env, JNI_VERSION_1_6);

  if (ret == JNI_OK) {
    __android_log_print(ANDROID_LOG_DEBUG, TAG, "线程分离.");
    gJavaVM->DetachCurrentThread();
  }
}

void testThread(JNIEnv *env, jclass clazz) {
  // 获取 JavaVM 指针
  env->GetJavaVM(&gJavaVM);
  // 创建线程本地存储，指定线程析构函数
  pthread_key_create(&gKey, &threadDestroy);
  // 创建线程
  pthread_t tid;
  int ret = pthread_create(&tid, nullptr, &threadTest, nullptr);
  if (ret != 0) {
    __android_log_print(ANDROID_LOG_DEBUG, TAG, "thread [%ld] create err", tid);
    return;
  }

  // 等待线程结束
  pthread_join(tid, nullptr);
  // 移除线程本地存储
  pthread_key_delete(gKey);
}
```

上述代码，首先保存 `JavaVM`，然后启动线程，在线程中使用 `GetEnv` 函数尝试从线程获得 `JNIEnv`，返回值将有 3 种结果：

1. `JNI_OK`，说明此线程已附加，可直接使用获得的 `JNIEnv`；
2. `JNI_EDETACHED`，说明此线程未附加，那么需要使用 `AttachCurrentThread` 进行附加；
3. `JNI_EVERSION`，说明不支持指定的版本。

在获得 `JNIEnv` 之后线程就执行完毕了，那么 `pthread_create` 中指定的线程析构函数 `threadDestroy` 将被回调，在这里确认线程已被附加后，使用 `DetachCurrentThread` 分离线程。

`AttachCurrentThread` 的第 2 个参数一般可以指定为空，它是一个 `JavaVMAttachArgs` 结构指针，用于指定格外信息。

```c++
// jni.h

struct JavaVMAttachArgs {
    jint        version;    /* must be >= JNI_VERSION_1_2 */
    const char* name;       /* NULL or name of thread as modified UTF-8 str */
    jobject     group;      /* global ref of a ThreadGroup object, or NULL */
};
```



# JNI 异常

当原生代码出现异常挂起时，大多数 JNI 函数无法被调用。通过 C/C++ 代码可以检查到是否出现了异常（通过 `ExceptionCheck` 或者 `ExceptionOccurred` 的返回值）；或者直接清除异常。

在异常挂起时，只能调用如下 JNI 函数：

```
DeleteGlobalRef
DeleteLocalRef
DeleteWeakGlobalRef
ExceptionCheck
ExceptionClear
ExceptionDescribe
ExceptionOccurred
MonitorExit
PopLocalFrame
PushLocalFrame
Release<PrimitiveType>ArrayElements
ReleasePrimitiveArrayCritical
ReleaseStringChars
ReleaseStringCritical
ReleaseStringUTFChars
```

许多 JNI 调用都会抛出异常，但通常可以使用一种更简单的方法来检查失败调用，例如 `NewString` 函数返回非空，则表示不需要检查异常。如果使用 `CallObjectMethod` 函数，则始终必须检查异常，如果系统抛出异常，那么函数返回值无效。



### 检查异常

使用 `ExceptionCheck` 函数可检查上一次代码调用是否出现了异常，如果出现异常，`ExceptionCheck` 将返回 `JNI_TRUE`，否则为 `JNI_FALSE`；或使用 `ExceptionOccurred` 函数，如果出现异常，它会返回一个 `jthrowable` 对象，否则为空。

通常使用 `ExceptionCheck` 函数，因为它不需要创建局部引用（`jthrowable`）。

在捕获到异常之后，使用 `ExceptionDescribe` 打印异常信息，如果调用 `ExceptionClear` 清除异常，那么异常将被忽略（不过在未处理的情况下盲目地忽略异常可能会出现问题）。

```c++
// 检查异常
bool checkException(JNIEnv *env) {
  if (env->ExceptionCheck() == JNI_TRUE) {
    env->ExceptionDescribe();
    env->ExceptionClear();
    return true;
  }

  return false;
}
```



### 抛出异常

目前 Android 并不支持 C++ 异常。

JNI 提供了 `Throw` 和 `ThrowNew` 用来抛出 Java 异常，但不会在调用后就抛出异常，只是在当前线程中设置了异常指针。从本地代码返回到受虚拟机管理的代码后，会观察到这些异常指针并进行相应处理（抛出异常）。

JNI 没有提供直接操作 Java `Throwable` 对象本身的内置函数（直接创建对象或者获取异常信息）。

如果想要抛出指定异常，则需要自己找到 `Throwable` 类后，调用 `ThrowNew` 函数产生异常：

```c++
// 抛出 NullPointerException
env->ThrowNew(env->FindClass("java/lang/NullPointerException"), msg);
// 抛出 RuntimeException
env->ThrowNew(env->FindClass(env, "java/lang/RuntimeException"), msg);
```

如果需要获取异常信息，那么需要查找 `Throwable#getMessage()` 的方法 ID 并调用。



# 参考

- [https://docs.oracle.com/javase/7/docs/technotes/guides/jni/spec/jniTOC.html](https://docs.oracle.com/javase/7/docs/technotes/guides/jni/spec/jniTOC.html)

- [https://developer.android.google.cn/training/articles/perf-jni](https://developer.android.google.cn/training/articles/perf-jni)