# Android JNI 指南

[TOC]

JNI（Java Native Interface，Java 原生接口），是 Java 和 C++ 组件用以互相通信的接口。

Android 平台下的 JNI 支持由 Android NDK 提供，它是一套能将 C 或 C++（原生代码）嵌入到 Android 应用中的工具。

使用 JNI 在 Android 平台下进行编程的用处：

1. 在平台之间移植应用；
2. 重复使用现有库，或者提供自己的库供重复使用；
3. 在某些情况下提供高性能，特别是像游戏这种密集型应用；
4. 提供安全性保障，在二进制层面比字节码层面的逆向工作更加困难。



## JNI 优化原则

1. 尽可能减少跨 JNI 层的编组（Marshalling）数据资源的次数，因为跨 JNI 层进行编组的开销很大。尽可能设计一种接口，减少需要编组的数据量以及必须进行数据编组的频率；
2. 尽量避免在使用受管理的编程语言（在虚拟机中运行）中与 C/C++ 编写的代码之间进行异步通信（例如 C/C++ 中开启线程后直接回调 Java 语言），这样可以使 JNI 接口更容易维护。通常使用与编写界面的相同语言进行异步更新，以简化异步界面的更新，例如，使用 Java 语言创建线程，然后发出对 C++ 层的阻塞调用，然后再阻塞完成后通知界面线程；
3. 尽可能减少需要访问 JNI 或被 JNI 访问的线程数。如果确实需要以 Java 和 C++ 两种语言来利用线程池，请尝试在池所有者之间（而不是各个工作线程之间）保持 JNI 通信。
4. 将接口保存在少量的容易识别的 C++ 和 Java 源位置，以便于将来进行重构。



## 名词说明

下面叙述中使用到的名词说明。

- JNI 方法，在 Java 层使用 native 声明，使用 C/C++ 中实现的方法。

- JNI 函数，JNI 提供的与 Java 层交互的工具一系列函数，例如 `RegisterNatives`。

- 不透明，具体结构未知，由相应的虚拟机实现决定。



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

因此，不建议同时在这两种语言包含的头文件中添加 `JNIEnv` 参数（导致混乱）。或者当源文件中出现 `#ifdef __cplusplus` ，且该文件中所有的内容都引用了 `JNIEnv` 时，那么可能需要做额外的处理。



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

静态注册只需要按照 JNI 接口规范，在 C/C++ 代码中声明一个 `Java_[全类名中 的 . 替换为 _]_[方法名]` 函数，然后添加 `JNIEXPORT` 前缀即可。

当系统加载 so 文件后，将根据名字对应规则，自动注册 JNI 方法。

下面采用了 C++ 代码，需要使用 `extern "C"` 来声明（为了兼容 C 语言的符号签名规则，使 C 语言能够正常链接调用它）。

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

从 `JNI_OnLoad` 开始看。

1. 首先 `RegisterNatives` 这个函数由 `JNIEnv` 类型提供，而 `JNI_OnLoad` 第一个参数是 `JavaVM *`，所以，这里首先获取 `JNIEnv` 类型指针，使用 `JavaVM` 的 `GetEnv` 函数获取（由于系统默认已经附加到线程，所以这里才能直接 `GetEnv`）;
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

动态注册的好处是，可以只导出 `JNI_OnLoad`（注册的 C/C++ 函数可以进行符号优化，不导出），生成速度更快且更小的代码，且可避免与加载到应用中的其他库发生潜在冲突。



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

当需要访问静态成员时需要提供一个代表 Java 类型的 `jclass` 作为参数，访问类对象成员时则需要一个表示 Java 对象的 `jobject` 作为参数。

同时两者都需要首先提供目标 Java 类成员的 JNI 类型签名（符合上面的 JNI 签名表规则），用来获取一个不透明的 `jFieldID` 类型，传递给 JNI 函数，用于找到目标成员，之后才能使用上述 JNI 函数访问 Java 类成员。

```c++
jfieldID GetStaticFieldID(jclass clazz, const char* name, const char* sig);
```



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

当需要调用静态方法时需要提供一个代表 Java 类型的 `jclass` 作为参数，调用类成员方法时则需要一个表示 Java 对象的 `jobject` 作为参数。

同时两者都需要首先提供目标 Java 方法的 JNI 签名（符合上面的 JNI 签名表规则），用来获取一个不透明的 `jMethodID` 类型，传递给 JNI 函数，用于找到目标方法，之后才能使用上述 JNI 函数调用 Java 类方法。



### Java 层访问实例

下面对实际的 Java 类成员和方法进行访问和调用。

首先定义一个 Java 类，`JniCallExample`。

```java
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

首先在 `NativeHandler` 类里面，声明 JNI 方法 `void testAccessJava(JniCallExample jniCallExample)`，用于调用 C/C++ 代码启动测试。

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

`env->NewStringUTF("data")` 用于创建一个 Java 字符串（new String()），它的内存由 Java 虚拟机管理，它使用 `jstring` 类型来描述，是一个 JNI 提供的不透明类型，用于映射一个 Java 字符串，每种 Java 类型都有对应的映射类型（下面会提供映射表），这里用作 Java 变量来给 Java 变量赋值或者作为参数传递。

`env->GetStringUTFChars(jStr, nullptr);` 用于从 Java 字符串中取得 C 形式的标准 `UTF8` 字符串，它将会在本地分配内存，而不是由 Java 虚拟机管理，所以使用后需要手动使用 `ReleaseStringUTFChars` 释放。



### 访问优化

在对 Java 层进行访问时，不管是访问 Java 类成员还是调用 Java 方法，都需要首先使用 `FindClass` 找到目标 Java 类，然后获取对应的成员 ID 和方法 ID， 对于 `FindClass` 和查找相关 ID 的函数，每次调用它们可能都需要进行多次的字符串比较，而使用这些 ID 去访问对于的 Java 类成员和方法速度却是很快的。

那么如果需要多次访问相同的 Java 目标，那么考虑将这些 `jclass`（FindClass 的结果）和相关 ID 缓存起来。 这些变量在被访问的 Java 类被卸载之前保证是有效的。只有在与 ClassLoader 关联的所有类都满足垃圾回收条件时，系统才会卸载这些类，这种情况比较少见，但在 Android 中是有可能出现的。

Android 推荐的方法是，在 Java 类中声明一个名叫 `nativeInit` 的 JNI 方法，在类的静态块内调用，这个 JNI 方法就负责提前缓存要使用的 Java 类型，那么一个类被加载时，`nativeInit` 就会被调用。

可以在 Android 系统源码中看到许多名叫 `nativeInit` 的 JNI 方法，它们就是负责此用途的。

一般使用 `static` 结构来缓存这些 ID 和 `jclass`，`jclass` 作为 Java 引用，需要使用 `NewGlobalRef` 函数创建一个全局引用来保护它不被回收。

那么现在改进之前的 Java 访问实例，如下：

首先 `NativeHandler` 中增加 `nativeInit` 方法。


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



### JNI 类型

每种 Java 类型，在 JNI 中都有对应的本地数据类型，C/C++ 通过 JNI 方法与 Java 层进行交互时，均是使用这些类型进行参数传递，此时虚拟机再根据每种类型翻译为相应的 Java 类型传递给 Java 层方法.

还有一些特殊的数据类型用来存储 Java 方法 ID 和类成员 ID。



- 基本数据类型

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



- 引用类型

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



- 方法和类成员 ID

它们是不透明结构体指针类型：

```c++
// jni.h

struct _jfieldID;
typedef struct _jfieldID *jfieldID;
 
struct _jmethodID;
typedef struct _jmethodID *jmethodID;
```



- 数组元素

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



### 引用管理

Java 对象在 JNI 中有两种引用方式，一种是局部引用；一种是全局引用。

- 局部引用

Java 层通过 JNI 方法传递给 C/C++ 函数的每个对象参数，以及 C/C++ 通过 JNI 函数（`Call<type>Method`）调用接收的 Java 方法的对象返回值都属于局部引用。

局部引用仅在当前线程中的当前 C/C++ 函数运行期间有效。在 C/C++ 函数返回后，即使对象本身继续存在，该引用也无效。

局部引用适用于 `jobject` 的所有子类，包括 `jclass`、`jstring` 和 `jarray`。



- 全局引用

创建全局引用只能使用 `NewGlobalRef` 和 `NewWeakGlobalRef` 函数。

如果希望长时间的持有某个引用，那么必须使用全局引用，使用 `NewGlobalRef` 函数时将局部引用作为参数传入，换取全局引用。在调用 `DeleteGlobalRef` 删除全局引用之前，此引用保证有效。

通常用于缓存 `FindClass` 返回的 `jclass`，就像前面的 Java 访问优化中所做的措施一样。

```cpp
jclass localClass = env->FindClass("MyClass");
jclass globalClass = reinterpret_cast<jclass>(env->NewGlobalRef(localClass)); 
```



- 提示

对于同一个对象的引用可能存在多个不同的值，例如，对于同一个对象多次调用 `NewGlobalRef` 所返回的值可能不同。

如果需要比较两个引用是否指向同一个对象，必须使用 `IsSameObject` 函数，切勿在 C/C++ 代码中使用 `==` 比较各个引用。

在两次调用 `NewGlobalRef` 对同一个对象创建全局引用时，表示这个对象的 32 位值可能不同；而在多次调用 `NewGlobalRef` 创建不同对象的全局引用时，它们可能具有相同的 32 位值，所以不能将 `jobject` 用作 key 使用。

不要过度分配局部引用，如果需要创建大量引用，应该主动调用 `DeleteLocalRef` 删除它们，而不是期望 JNI 自动删除。JNI 默认实现只能保留 16 个局部引用，如果需要保存更多数量，可以按照需要删除，或使用 `EnsureLocalCapacity/PushLocalFrame` 申请保留更多引用数量。

`jfieldID` 和 `jmethodID` 为不透明类型，不属于对象引用，所以不能使用 `NewGlobalRef` 保护。`GetStringUTFChars` 和 `GetByteArrayElements` 返回的原始数据指针也不属于对象。

一种特殊情况是，如果使用 `AttachCurrentThread` 附加到 C/C++ 线程，那么在线程分离之前，运行中的代码一定不会自动释放局部引用。代码创建的任何局部引用都必须手动删除。通常，在循环中创建局部引用的任何 C/C++ 代码需要执行某些手动删除操作。

谨慎使用全局引用。全局引用不可避免，但它们很难调试，并且可能会导致难以诊断的内存（不良）行为。在所有其他条件相同的情况下，全局引用越少，解决方案的效果可能越好。



## Java 常用数据访问

对 Java 字符串和数组的访问方法。不会访问这些数据，就无法进行 JNI 开发。

### 访问字符串

当 Java 层已 jstring 的形式传入 


### 访问数组

todo


======== 分隔线 ==========


保存 JavaVM
-获取 JNIEnv

线程访问
-附加线程

## 处理 Java 异常

保存 Java 对象
- 全局引用
- 局部引用

JNI 规范文档 https://docs.oracle.com/javase/7/docs/technotes/guides/jni/spec/jniTOC.html