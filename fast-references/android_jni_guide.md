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

JNI 定义了两个关键的数据结构，JavaVM 和 JNIEnv，它们的本质都是指向函数表的二级指针（在 C++ 版本中，两者都是类，类中都有一个指向函数表的指针，它们的成员函数封装了通过函数表进行访问的 JNI 函数），可以使用 JavaVM 类进行创建和销毁 JavaVM 的操作。理论上，每个进程可以有多个 JavaVM，但 Android 只允许有一个。

JNIEnv 的指针将在每个 JNI 函数的第一个参数中。

这个 JNIEnv 只能用于线程本地存储（Thread Local），所以无法在线程之间共享 JNIEnv，如果需要在其他线程中访问 JNIEnv，可以通过 JavaVM 调用 `GetEnv` 函数获得相应的 JNIEnv 指针（需要在之前使用过 `AttachCurrentThread` 对此线程进行附加后调用）。

JNIEnv 和 JavaVM 在 C 源文件和 C++ 源文件中的声明不同，使用 C 文件和 C++ 文件包含 `jni.h` 时，会有不同的类型定义。

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

因此，不建议同时在这两种语言包含的标头文件中添加 JNIEnv 参数（导致混乱）。或者当源文件中出现 `#ifdef __cplusplus` ，且该文件中所有的内容都引用了 JNIEnv 时，那么可能需要做额外的处理。


======== 分隔线 ==========

## JNI 方法注册：

静态注册
动态注册
类静态方法和类方法

## Java 方法调用

引用管理

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