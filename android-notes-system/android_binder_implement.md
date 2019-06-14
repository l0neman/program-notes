# Android Binder 的设计、实现与应用 - Binder 实现分析

## 前言

Binder 是 Android 系统中的进程间通信方式之一，Binder 定义的整个进程间通信框架完全贯穿了 Android 系统的底层到上层，如果要深入理解 Binder 原理，那么必须对每个层次的 Binder 架构进行分析。

下面是每个层次的 Binder 职责描述。

- 应用层，为应用程序提供进程间通信服务，可使用 aidl 描述语言定义远程服务之间的 Binder 通信接口，并实现面向对象的进程间通信。
- java 层，为 Framework java 层服务提供进程间通信服务，例如 AMS，PMS 等服务。
- native 层，为 Framework native 层的服务提供进程间通信，例如 MediaPlayerService 服务；管理 native 层服务的注册获取，native 层实现了 Binder 服务注册统一管理者 ServiceManager。

- driver 层，为 Binder 核心通信方法的实现，以驱动的形式存在于 Android 系统中。

下面将分别分析每个层次的实现：

[todo]

## Binder 架构

通过分析参与 Binder 通信的相关服务对于 Binder 框架每一层的实现，描述每个层次的架构以及绘制图形。

### Java 层

[todo]

### Native 层

[todo]

### Driver 层

[todo]

## Binder 协议应用

通过分析参与 Binder 通信的相关服务，描述 Binder 协议的应用场景。

[todo]