# Android JNI 指南

JNI（Java Native Interface，Java 原生接口），是 Java 和 C++ 组件用以互相通信的接口。

Android 平台下的 JNI 支持由 Android NDK 提供，它是一套能将 C 或 C++（原生代码）嵌入到 Android 应用中的工具。

使用 JNI 在 Android 平台下进行编程有具有如下优点：

1. 在平台之间移植应用；
2. 重复使用现有库，或者提供自己的库供重复使用；
3. 在某些情况下提供高性能，特别是像游戏这种密集型应用；
4. 提供安全性保障，在二进制层面比字节码层面的逆向工作更加困难。



## JNI 优化原则

1. 尽可能减少跨 JNI 层的编组（Marshalling）数据资源的次数，因为跨 JNI 层进行编组的开销很大。尽可能设计一种接口，减少需要编组的数据量以及必须进行数据编组的频率；
2. 尽量避免在使用受管理的编程语言（在虚拟机中运行）中与 C/C++ 编写的代码之间进行异步通信（例如 C/C++ 中开启线程后直接回调 Java 语言），这样可以使 JNI 接口更容易维护。通常使用与编写界面的相同语言进行异步更新，以简化异步界面的更新，例如，使用 Java 语言创建线程，然后发出对 C++ 层的阻塞调用，然后再阻塞完成后通知界面线程；
3. 尽可能减少需要访问 JNI 或被 JNI 访问的线程数。如果确实需要以 Java 和 C++ 两种语言来利用线程池，请尝试在池所有者之间（而不是各个工作线程之间）保持 JNI 通信。
4. 将接口保存在少量的容易识别的 C++ 和 Java 源位置，以便于将来进行重构。



## JavaVM 和 JNIEnv

JNI 定义了两个关键的数据结构，`JavaVM` 和 `JNIEnv`，它们的本质都是指向函数表的二级指针（在 C++ 版本中，两者都是类，类中都有一个指向函数表的指针，它们的成员函数封装了通过函数表进行访问的 JNI 函数），可以使用 `JavaVM` 类进行创建和销毁 JavaVM 的操作。理论上，每个进程可以有多个 JavaVM，但 Android 只允许有一个。

`JNIEnv` 的指针将在每个 JNI 函数的第一个参数中。

这个 `JNIEnv` 只能用于线程本地存储（Thread Local），所以无法在线程之间共享 `JNIEnv`，如果需要在其他线程中访问 `JNIEnv`，可以通过 `JavaVM` 调用 `GetEnv` 函数获得相应的 `JNIEnv` 指针（需要在之前使用过 `AttachCurrentThread` 对此线程进行附加后调用）。

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

因此，不建议同时在这两种语言包含的标头文件中添加 `JNIEnv` 参数（导致混乱）。或者当源文件中出现 `#ifdef __cplusplus` ，且该文件中所有的内容都引用了 `JNIEnv` 时，那么可能需要做额外的处理。



## JNI 方法注册

JNI 方法是 Java 与 C/C++ 代码沟通的桥梁，使用它时必须首先注册。JNI 方法的声明在 Java 类中，实现在 C/C++ 代码中，在 Java 层的方法声明必须添加 `native` 关键字，然后才能注册。

注册方式分为静态注册（根据 JNI 命令规范直接定义对应名字的 C/C++ 函数）和动态注册（使用 `RegisterNatives` 函数注册到 C/C++ 函数上）。

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

建立 NDK 工程描述如下：

```
-jni
  Android.mk
  Application.mk
  hello.cpp
  hello.h
```

```makefile
# Android.mk

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE    := hello
LOCAL_SRC_FILES := hello.cpp

include $(BUILD_SHARED_LIBRARY)
```

那么下面针对上面搭建的 NDK 工程，采用两种方式在 C/C++ 代码中实现 Java 类 `NativeHandler` 中的 `getString` 方法并注册。



### 静态注册

静态注册只需要按照 JNI 接口规范，在 C/C++ 代码中声明一个 `Java_[全类名中 的 . 替换为 _]` 函数，然后添加 `JNIEXPORT` 前缀即可。

下面采用了 C++ 代码，需要使用 `extern "C"` 来声明（为了兼容 C 语言函数签名规则，使 C 语言能够正常链接调用它）。

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
return (*env)->NewStringUTF(env, "hello");
```

此时就注册完成了，Java 层可以直接调用 `textView.setText(NativeHandler.getString())` 进行测试了。

这种注册方式简单直接，但是所有 C/C++ 中实现的 JNI 函数符号都需要被导出，对于逆向人员来说，使用 IDA Pro 可以直接看到注册 JNI 方法的名字，快速定位到对应的 Java 代码。



### 动态注册

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

从 JNI_OnLoad 开始看。

1. 首先 `registerNatives` 这个函数由 `JNIEnv` 类型提供，而 `JNI_OnLoad` 第一个参数是 `JavaVM *`，所以，这里首先获取 `JNIEnv` 类型指针，使用 `JavaVM` 的 `GetEnv` 函数获取（由于系统默认已经附加到线程，所以这里才能直接 `GetEnv`）;
2. 下面需要使用 `RegisterNatives` 注册 JNI 函数了，看一下它的用法：

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

那么上面的代码，即表明了要把 `NativeHandler` 中的 `getString` 注册到 C++ 中的 `getString` 函数上。

5. 最后直接调用 `env->RegisterNatives` 函数就可以了，一般情况下，注册成功，返回 `JNI_OK`。

可以允许在 `JNI_OnLoad` 中绑定多个 Java 类中的 native 方法，但是建议不要这样做，会导致难以维护，一般一个 Java 类中包含多个 native 方法，这个 Java 类管理的 native 方法对应一个 so，在静态块中直接调用 `System.loadLibrary`，如果一个 so 包含多个 Java 类的 JNI 方法，那么 `System.loadLibrary` 将放在 `Application` 中初始化，造成代码分散。

动态注册的好处是，可以只导出 `JNI_OnLoad`，生成速度更快且更小的代码，且可避免与加载到应用中的其他库发生潜在冲突。



### 类静态方法和类成员方法

注册 Java 中的静态 native 方法和类成员 native 方法的区别是，对应的 C/C++ 函数的回调参数不同。

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

静态方法传递的是代码 Java 类的 `jclass`，而类方法传递的是表示 Java `this` 对象的 `jobject`，那么就可以使用它来访问 `this` 对象内的成员变量和相关方法。如果需要访问 `jclass`，使用 JNI 提供的 `GetObjectClass` 函数获取。

在注册工作完成后，就可以从 Java 层调用 JNI 方法，使用 C/C++ 语言处理逻辑了。



## Java 层访问

在 C/C++ 代码中，需要对 Java 层进行访问，最基本的两种访问操作就是读写 Java 类型的成员和调用 Java 类的方法。



### Java 成员变量访问

JNI 提供了一系列访问 Java 类的静态成员和对象成员的函数，例如。

```c++
env->GetStaticIntField(...);     // 读取 Java 类型为 int 的类静态成员
env->SetStaticIntField(...);     // 写入 Java 类型为 int 的类静态成员
env->GetStaticObjectField(...);  // 读取类型为 Java 引用的类静态成员
env->SetStaticObjectField(...);  // 写入类型为 Java 引用的类静态成员

env->GetIntField(...);           // 读取 Java 类型为 int 的类对象成员
env->SetIntField(...);           // 写入 Java 类型为 int 的类对象成员
env->GetObjectField(...);        // 读取类型为 Java 引用的类对象成员
env->SetObjectField(...);        // 写入类型为 Java 引用的类对象成员
```

总结为：

```c++
env->GetStatic<type>Field();    // 读取 Java 类型为 type 的类静态成员
env->SetStatic<type>Field();    // 写入 Java 类型为 type 的类静态成员
env->Get<type>Field();          // 读取 Java 类型为 type 的类对象成员
env->Set<type>Field();          // 写入 Java 类型为 type 的类对象成员
```

访问静态成员时需要代表 Java 类型的 `jclass` 作为参数，访问类对象成员时需要表示 Java 对象的 `jobject` 作为参数。



### Java 类方法访问

JNI 同时也提供了一系列调用 Java 类的静态方法和对象方法的函数，例如：

```c++
env->CallStaticVoidMethod(...); // 调用返回值类型为 void 的静态方法
env->CallStaticIntMethod(...);  // 调用返回值类型为 int 的静态方法
env->CallObjectMethod(...);     // 调用返回值类型为 Java 引用的静态方法
// ...

env->CallVoidMethod(...);       // 调用返回值类型为 void 的对象方法
env->CallIntMethod(...);        // 调用返回值类型为 int 的对象方法
env->CallObjectMethod(...);     // 调用返回值类型为 Java 引用的成员方法
// ...
```

总结为：

```c++
env->CallStatic<type>Method(...) // 调用返回值类型为 type 的静态方法
env->Call<type>Method(...);      // 调用返回值类型为 type 的成员方法
```

调用静态方法时需要代表 Java 类型的 `jclass` 作为参数，调用类成员方法时需要表示 Java 对象的 `jobject` 作为参数。



### 测试实例


### 最佳实践


### 引用管理


======== 分隔线 ==========



## Java 数据访问

访问字符串
访问数组

保存 JavaVM
-获取 JNIEnv

线程访问
-附加线程

## 处理 Java 异常

保存 Java 对象
- 全局引用
- 局部引用

JNI 规范文档 https://docs.oracle.com/javase/7/docs/technotes/guides/jni/spec/jniTOC.html