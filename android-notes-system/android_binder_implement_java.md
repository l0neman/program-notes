# Android Binder 的设计、实现与应用 - Java 层实现分析

## 前言

Binder 的核心逻辑都在 Native 层进行实现，例如 Binder 服务端总管 ServiceManager 以及相关服务端和客户端的 Binder 表示类型 `BBinder` 和 `BpBinder` 类型，那么 java 层的 Binder 没有必要重新实现一遍这些过程，所以 java 层 Binder 框架作为上层服务与 Native 层的交互接口而存在，java 层 Binder 框架是对 Native Binder 框架的映射。

java 层存在 native 层的多个映射类型，java 层的 `ServiceManager` 负责管理服务的注册和获取接口，java 层的 `Binder` 类型作为 java 层 Binder 代表类型而存在，在前面的文档中分析了 Native 层时如何实现的 Binder 框架，那么这里可以按照分析 native 层的实现顺序分析 java 层的实现。

下面基于 Android 6.0 源码分析 java 层的 Binder 框架的实现。

## ServiceManager

首先分析 ServiceManager 类，它负责管理服务的注册和获取，它具有和 native 层 ServiceManager 名字相同的几个管理 Binder 的方法：

```java
public static IBinder getService(String name);
public static void addService(String name, IBinder service);
public static IBinder checkService(String name);
public static String[] listServices(); throws RemoteException
```

这里

它的内部包含一个 `IServiceManager` 接口类型成员，首先看它的定义：

```java
// IServiceManager.java

public interface IServiceManager extends IInterface
{
    /**
     * Retrieve an existing service called @a name from the
     * service manager.  Blocks for a few seconds waiting for it to be
     * published if it does not already exist.
     */
    public IBinder getService(String name) throws RemoteException;
    
    /**
     * Retrieve an existing service called @a name from the
     * service manager.  Non-blocking.
     */
    public IBinder checkService(String name) throws RemoteException;

    /**
     * Place a new @a service called @a name into the service
     * manager.
     */
    public void addService(String name, IBinder service, boolean allowIsolated)
                throws RemoteException;

    /**
     * Return a list of all currently running services.
     */
    public String[] listServices() throws RemoteException;

    /**
     * Assign a permission controller to the service manager.  After set, this
     * interface is checked before any services are added.
     */
    public void setPermissionController(IPermissionController controller)
            throws RemoteException;
    
    static final String descriptor = "android.os.IServiceManager";

    int GET_SERVICE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION;
    int CHECK_SERVICE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+1;
    int ADD_SERVICE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+2;
    int LIST_SERVICES_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+3;
    int CHECK_SERVICES_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+4;
    int SET_PERMISSION_CONTROLLER_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+5;
}
```

这和 native 层的 ServiceManager 实现的功一一对应。

## AndroidRuntime

java 层的 Binder 主要实现在 Binder 这个类中，它与 Native 层交互的工作由 `android_util_Binder.cpp` 负责实现。

首先 Android 系统的 Zygote 在启动时会做一些初始化工作，其中一项就是初始化 jni 层资源，它的入口在 `AndroidRuntime::startReg` 函数中，。

```c++
// AndroidRuntime.cpp

int AndroidRuntime::startReg(JNIEnv* env)
{
    /*
     * This hook causes all future threads created in this process to be
     * attached to the JavaVM.  (This needs to go away in favor of JNI
     * Attach calls.)
     */
    androidSetCreateThreadFunc((android_create_thread_fn) javaCreateThreadEtc);

    ALOGV("--- registering native functions ---\n");

    /*
     * Every "register" function calls one or more things that return
     * a local reference (e.g. FindClass).  Because we haven't really
     * started the VM yet, they're all getting stored in the base frame
     * and never released.  Use Push/Pop to manage the storage.
     */
    env->PushLocalFrame(200);
	// 这里会初始化 jni 相关资源。
    if (register_jni_procs(gRegJNI, NELEM(gRegJNI), env) < 0) {
        env->PopLocalFrame(NULL);
        return -1;
    }
    env->PopLocalFrame(NULL);

    //createJavaThread("fubar", quickTest, (void*) "hello");
    return 0;
}
```

其中的 `register_jni_procs` 会遍历调用一个初始化函数列表。

```c++
// AdroidRuntime.cpp

static int register_jni_procs(const RegJNIRec array[], size_t count, JNIEnv* env)
{
    for (size_t i = 0; i < count; i++) {
        if (array[i].mProc(env) < 0) {
#ifndef NDEBUG
            ALOGD("----------!!! %s failed to load\n", array[i].mName);
#endif
            return -1;
        }
    }
    return 0;
}
```

```c++
static const RegJNIRec gRegJNI[] = {
    REG_JNI(register_com_android_internal_os_RuntimeInit),
    REG_JNI(register_android_os_SystemClock),
    REG_JNI(register_android_util_EventLog),
    REG_JNI(register_android_util_Log),
    REG_JNI(register_android_content_AssetManager),
    ...
    REG_JNI(register_android_os_Process),
    REG_JNI(register_android_os_SystemProperties),
    REG_JNI(register_android_os_Binder),
    REG_JNI(register_android_os_Parcel),
    ...
};
```

其中 `REG_JNI` 是一个宏，用于初始化结构体：

```c++
// AdroidRuntime.cpp

#define REG_JNI(name)      { name }
struct RegJNIRec {
    int (*mProc)(JNIEnv*);
};
```

而上面循环中的 `mProce` 函数就指向每个初始化的 `name` 函数，即调结构体数组中的一个函数进行它们的初始化工作，其中就包括一个函数 `register_android_os_Binder`，它负责初始化 Binder 类型在 jni 层的资源。

## Binder-jni

进入 Binder 初始化 jni 资源的入口：

```c++
// android_util_Binder.cpp

int register_android_os_Binder(JNIEnv* env)
{
    if (int_register_android_os_Binder(env) < 0)
        return -1;
    if (int_register_android_os_BinderInternal(env) < 0)
        return -1;
    if (int_register_android_os_BinderProxy(env) < 0)
        return -1;

    // 保存一些 java 层类型的指针。
    jclass clazz = FindClassOrDie(env, "android/util/Log");
    gLogOffsets.mClass = MakeGlobalRefOrDie(env, clazz);
    gLogOffsets.mLogE = GetStaticMethodIDOrDie(env, clazz, "e",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I");

    clazz = FindClassOrDie(env, "android/os/ParcelFileDescriptor");
    gParcelFileDescriptorOffsets.mClass = MakeGlobalRefOrDie(env, clazz);
    gParcelFileDescriptorOffsets.mConstructor = GetMethodIDOrDie(env, clazz, "<init>",
                                                                 "(Ljava/io/FileDescriptor;)V");

    clazz = FindClassOrDie(env, "android/os/StrictMode");
    gStrictModeCallbackOffsets.mClass = MakeGlobalRefOrDie(env, clazz);
    gStrictModeCallbackOffsets.mCallback = GetStaticMethodIDOrDie(env, clazz,
            "onBinderStrictModePolicyChange", "(I)V");

    return 0;
}
```

```c++
// android_util_Binder.cpp

const char* const kBinderPathName = "android/os/Binder";

static int int_register_android_os_Binder(JNIEnv* env)
{
    // 保存 java 层 Binder 类型的指针。
    jclass clazz = FindClassOrDie(env, kBinderPathName);

    gBinderOffsets.mClass = MakeGlobalRefOrDie(env, clazz);
    // 保存 Bindre 类型的 execTransact 方法和 mObject 指针。
    gBinderOffsets.mExecTransact = GetMethodIDOrDie(env, clazz, "execTransact", "(IJJI)Z");
    gBinderOffsets.mObject = GetFieldIDOrDie(env, clazz, "mObject", "J");

    // 注册 java 层函数到 native 层。
    return RegisterMethodsOrDie(
        env, kBinderPathName,
        gBinderMethods, NELEM(gBinderMethods));
}

```

```c++
// android_util_Binder.cpp

const char* const kBinderInternalPathName = "com/android/internal/os/BinderInternal";

static int int_register_android_os_BinderInternal(JNIEnv* env)
{
    // 保存 java 层 BinderInternal 类型指针和 forceBinderGc 方法。
    jclass clazz = FindClassOrDie(env, kBinderInternalPathName);

    gBinderInternalOffsets.mClass = MakeGlobalRefOrDie(env, clazz);
    gBinderInternalOffsets.mForceGc = GetStaticMethodIDOrDie(env, clazz, "forceBinderGc", "()V");

    return RegisterMethodsOrDie(
        env, kBinderInternalPathName,
        gBinderInternalMethods, NELEM(gBinderInternalMethods));
}
```

```c++
// android_util_Binder.cpp

const char* const kBinderProxyPathName = "android/os/BinderProxy";

static int int_register_android_os_BinderProxy(JNIEnv* env)
{
    jclass clazz = FindClassOrDie(env, "java/lang/Error");
    gErrorOffsets.mClass = MakeGlobalRefOrDie(env, clazz);

    // 保存 java 层 BinderProxy 类型的相关信息。
    clazz = FindClassOrDie(env, kBinderProxyPathName);
    gBinderProxyOffsets.mClass = MakeGlobalRefOrDie(env, clazz);
    gBinderProxyOffsets.mConstructor = GetMethodIDOrDie(env, clazz, "<init>", "()V");
    gBinderProxyOffsets.mSendDeathNotice = GetStaticMethodIDOrDie(env, clazz, "sendDeathNotice",
            "(Landroid/os/IBinder$DeathRecipient;)V");

    gBinderProxyOffsets.mObject = GetFieldIDOrDie(env, clazz, "mObject", "J");
    gBinderProxyOffsets.mSelf = GetFieldIDOrDie(env, clazz, "mSelf",
                                                "Ljava/lang/ref/WeakReference;");
    gBinderProxyOffsets.mOrgue = GetFieldIDOrDie(env, clazz, "mOrgue", "J");

    clazz = FindClassOrDie(env, "java/lang/Class");
    gClassOffsets.mGetName = GetMethodIDOrDie(env, clazz, "getName", "()Ljava/lang/String;");

    return RegisterMethodsOrDie(
        env, kBinderProxyPathName,
        gBinderProxyMethods, NELEM(gBinderProxyMethods));
}
```

可以看到，上面的主要工作就是保存 java 层三个类型的信息，方便后面的逻辑与 java 层进行交互。

三个类型分别为 `Binder`，`BinderInternal`，`BinderProxy`。

