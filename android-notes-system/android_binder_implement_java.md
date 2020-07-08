# Android Binder 的设计、实现与应用 - Java 层实现分析

- [Binder 框架准备工作](#binder-框架准备工作)
  - [AndroidRuntime](#androidruntime)
  - [Binder-jni](#binder-jni)
- [Binder 服务总管](#binder-服务总管)
  - [ServiceManager](#servicemanager)
  - [BinderInternal](#binderinternal)
  - [ServiceManagerNative](#servicemanagernative)
  - [ServiceManagerProxy](#servicemanagerproxy)
  - [Parcel](#parcel)
  - [JavaBBinderHolder](#javabbinderholder)
  - [BinderProxy](#binderproxy)
  - [时序图](#时序图)
- [Binder 通信框架](#binder-通信框架)
- [Binder 服务端](#binder-服务端)
  - [ActivityManagerService](#activitymanagerservice)
  - [JavaBBinder](#javabbinder)
  - [Binder](#binder)
  - [ActivityManagetNative](#activitymanagetnative)
- [Binder 客户端](#binder-客户端)
  - [ContextImpl](#contextimpl)
  - [SystemServiceRegistry](#systemserviceregistry)
  - [ActivityManager](#activitymanager)
  - [ActivityManagerNative](#activitymanagernative)
  - [ActivityManagerProxy](#activitymanagerproxy)
  - [ActivityManagerNative](#activitymanagernative-1)
- [java 层 Binder 框架总结](#java-层-binder-框架总结)
  - [数据流图](#数据流图)
  - [框架类图](#框架类图)



## 前言

Binder 的核心逻辑都在 Native 层进行实现，例如 Binder 服务端总管 ServiceManager 以及相关服务端和客户端的 Binder 表示类型 `BBinder` 和 `BpBinder` 类型，那么 java 层的 Binder 没有必要重新实现一遍这些过程，所以 java 层 Binder 框架作为上层服务与 Native 层 Binder 框架的交互接口而存在，java 层 Binder 框架是对 Native 层 Binder 框架的一个映射。

java 层存在 native 层的多个映射类型，java 层的 `ServiceManager` 负责管理服务的注册和获取接口，在前面的文档中分析了 Native 层时如何实现的 Binder 框架，那么这里可以按照分析 native 层的组件的实现顺序来分析 java 层相应的映射组件的实现。

下面基于 Android 6.0 源码分析 java 层的 Binder 框架的实现。

## Binder 框架准备工作

### AndroidRuntime

java 层 Binder 框架与 native 层关系密切，在 Android 系统启动时会做一些初始化相关的 jni 资源的工作，其中就包含 java 层 Binder 框架的相关资源，java 层 Binder 框架与 Native 层交互的工作由 `android_util_Binder.cpp` 负责实现。

在 Zygote 进程启动时会做这些资源的工作，它的入口在 `AndroidRuntime::startReg` 函数中：

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

### Binder-jni

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
    // 保存 Binder 类型的 execTransact 方法和 mObject 指针。
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

## Binder 服务总管

下面按照签名分析 native 层的顺序，首先分析 ServiceManager 类，它负责管理服务的注册和获取。

### ServiceManager

首先看到 `ServiceManager` 类具有和 native 层 ServiceManager 名字相同的几个管理 Binder 的方法：

```java
public static IBinder getService(String name);
public static void addService(String name, IBinder service);
public static IBinder checkService(String name);
public static String[] listServices(); throws RemoteException
```

这里首先看用于注册服务的 `addService` 方法。

```java
// ServiceManager.java

/**
 * Place a new @a service called @a name into the service
 * manager.
 * 
 * @param name the name of the new service
 * @param service the service object
 */
public static void addService(String name, IBinder service) {
    try {
        getIServiceManager().addService(name, service, false);
    } catch (RemoteException e) {
        Log.e(TAG, "error in addService", e);
    }
}

```

它是由 `getIServiceManager` 返回的一个 `IServiceManager` 类型的对象的 `addService` 方法去实现的。

`IServiceManager` 这个类，它是一个接口，定义如下：

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

它也和 native 层的 ServiceManager 函数相对应。

通过查看 `ServiceManager` 其他方法的实现，发现它们都是通过 `IServiceManager` 去实现的功能，看来 `ServiceManager` 只是一个外壳，真正的实现在 `getIServiceManager` 方法里。

```java
// ServiceManager.java

private static IServiceManager getIServiceManager() {
    if (sServiceManager != null) {
        return sServiceManager;
    }

    // Find the service manager
    sServiceManager = ServiceManagerNative.asInterface(BinderInternal.getContextObject());
    return sServiceManager;
}
```

`ServiceManagerNative` 的 `asInterface` 返回了 `IServiceManager` 对象。

### BinderInternal

首先看 `BinderInternal` 的 `getContextObject` 方法，它是一个 native 方法。

```java
// ServiceManager.java

public static final native IBinder getContextObject();
```

实现在 `android_util_Binder.cpp` 中：

```c++
// android_util_Binder.cpp

static jobject android_os_BinderInternal_getContextObject(JNIEnv* env, jobject clazz)
{
    sp<IBinder> b = ProcessState::self()->getContextObject(NULL);
    return javaObjectForIBinder(env, b);
}
```

第一行代码很熟悉，就是之前分析过的 `ProcessState` 里的 `getContextObject` 函数，它的内部会打开 Binder 驱动，最终会返回一个 `new BpBinder(0)`，表示 ServiceManager 的客户端 Binder 类型。

那么看 `javaObjectForIBinder` 的实现：

```c++
// android_util_Binder.cpp

jobject javaObjectForIBinder(JNIEnv* env, const sp<IBinder>& val)
{
    if (val == NULL) return NULL;

    // BpBinder 的默认实现为 false。
    if (val->checkSubclass(&gBinderOffsets)) {
        // One of our own!
        jobject object = static_cast<JavaBBinder*>(val.get())->object();
        LOGDEATH("objectForBinder %p: it's our own %p!\n", val.get(), object);
        return object;
    }

    // For the rest of the function we will hold this lock, to serialize
    // looking/creation of Java proxies for native Binder proxies.
    AutoMutex _l(mProxyLock);

    // Someone else's...  do we know about it?
    // 从 BpBinder 的 mObjects(Vector) 缓存里面查询对象，对应下面的 attachObject。
    jobject object = (jobject)val->findObject(&gBinderProxyOffsets);
    if (object != NULL) {
        jobject res = jniGetReferent(env, object);
        if (res != NULL) {
            ALOGV("objectForBinder %p: found existing %p!\n", val.get(), res);
            return res;
        }
        LOGDEATH("Proxy object %p of IBinder %p no longer in working set!!!", object, val.get());
        android_atomic_dec(&gNumProxyRefs);
        val->detachObject(&gBinderProxyOffsets);
        env->DeleteGlobalRef(object);
    }

    // 创建 BinderProxy 的对象。
    object = env->NewObject(gBinderProxyOffsets.mClass, gBinderProxyOffsets.mConstructor);
    if (object != NULL) {
        LOGDEATH("objectForBinder %p: created new proxy %p !\n", val.get(), object);
        // 同时将 BpBinder 的引用绑定在 BinderProxy 中。
        // The proxy holds a reference to the native object.
        env->SetLongField(object, gBinderProxyOffsets.mObject, (jlong)val.get());
        val->incStrong((void*)javaObjectForIBinder);

        // The native object needs to hold a weak reference back to the
        // proxy, so we can retrieve the same proxy if it is still active.
        jobject refObject = env->NewGlobalRef(
                env->GetObjectField(object, gBinderProxyOffsets.mSelf));
        // 放入 BpBinder 的 mObjects 缓存。
        val->attachObject(&gBinderProxyOffsets, refObject,
                jnienv_to_javavm(env), proxy_cleanup);

        // Also remember the death recipients registered on this proxy
        sp<DeathRecipientList> drl = new DeathRecipientList;
        drl->incStrong((void*)javaObjectForIBinder);
        // 绑定了一个 DeathRecipientList 对象到 BinderProxy 的 mOrgue 对象。
        env->SetLongField(object, gBinderProxyOffsets.mOrgue, reinterpret_cast<jlong>(drl.get()));

        // Note that a new object reference has been created.
        android_atomic_inc(&gNumProxyRefs);
        incRefsCreated(env);
    }

    return object;
}
```

分析到这里了解到了 `BinderInternal.getContextObject()` 方法内部创建了一个 `BpBinder(0)` 对象，最终返回了一个 `BinderProxy` 对象，而且 `BinderProxy` 和 `BpBinder` 互相绑定，都持有对方的引用。

### ServiceManagerNative

接下来看 `ServiceManagerNative.asInterface` 的实现：

```java
// ServiceManagerNative.java

static public IServiceManager asInterface(IBinder obj)
{
    if (obj == null) {
        return null;
    }
    // BinderProxy 的 queryLocalInterface 返回 null。
    IServiceManager in =
        (IServiceManager)obj.queryLocalInterface(descriptor);
    if (in != null) {
        return in;
    }

    return new ServiceManagerProxy(obj);
}
```

最终创建了 `ServiceManagerProxy` 类型，即 `IServiceManager` 的实现类。

### ServiceManagerProxy

追溯 `addService` 方法的实现：

```java
// ServiceManagerNative.java - class ServiceManagerProxy

public void addService(String name, IBinder service, boolean allowIsolated)
    throws RemoteException {
    // Parcel 为 native 层 Parcel 映射类。
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    data.writeInterfaceToken(IServiceManager.descriptor);
    data.writeString(name);
	// 写入服务端 Binder 对象。
    data.writeStrongBinder(service);
    data.writeInt(allowIsolated ? 1 : 0);
    // mRemote 为传入的 BinderProxy 对象。
    mRemote.transact(ADD_SERVICE_TRANSACTION, data, reply, 0);
    reply.recycle();
    data.recycle();
}
```

### Parcel

首先使用 `Parcel` 数据包对象的 `writeStrongBinder` 方法将服务端 Binder 对象写入了数据包中：

```java
// Parcel.java

public final void writeStrongBinder(IBinder val) {
    nativeWriteStrongBinder(mNativePtr, val);
}
```

调用了 jni 层的方法：

```c++
// android_os_Parcel.cpp

static void android_os_Parcel_writeStrongBinder(JNIEnv* env, jclass clazz, jlong nativePtr, jobject object)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        const status_t err = parcel->writeStrongBinder(ibinderForJavaObject(env, object));
        if (err != NO_ERROR) {
            signalExceptionForError(env, clazz, err);
        }
    }
}
```

 可以看到使用了 native 层 `Pracel` 对象的 `writeStrongBinder` 函数写入的 Binder 对象，不过首先使用了 `ibinderForJavaObject` 函数从 java 层对象中获取 Binder：

```c++
// android_os_Parcel.cpp

sp<IBinder> ibinderForJavaObject(JNIEnv* env, jobject obj)
{
    if (obj == NULL) return NULL;

    if (env->IsInstanceOf(obj, gBinderOffsets.mClass)) {
        // 从 java 层服务对象的 mObject 指针值中获取 JavaBBinderHolder 对象。
        JavaBBinderHolder* jbh = (JavaBBinderHolder*)
            env->GetLongField(obj, gBinderOffsets.mObject);
        return jbh != NULL ? jbh->get(env, obj) : NULL;
    }

    if (env->IsInstanceOf(obj, gBinderProxyOffsets.mClass)) {
        return (IBinder*)
            env->GetLongField(obj, gBinderProxyOffsets.mObject);
    }

    ALOGW("ibinderForJavaObject: %p is not a Binder object", obj);
    return NULL;
}
```

### JavaBBinderHolder

最终是返回了一个 `JavaBBinderHolder` 对象的 `get` 函数返回的值。

```c++
// android_util_Parcel.cpp

class JavaBBinderHolder : public RefBase
{
public:
    sp<JavaBBinder> get(JNIEnv* env, jobject obj)
    {
        AutoMutex _l(mLock);
        sp<JavaBBinder> b = mBinder.promote();
        if (b == NULL) {
            b = new JavaBBinder(env, obj);
            mBinder = b;
            ALOGV("Creating JavaBinder %p (refs %p) for Object %p, weakCount=%" PRId32 "\n", 
                 b.get(), b->getWeakRefs(), obj, b->getWeakRefs()->getWeakCount());
        }
        return b;
    }

    sp<JavaBBinder> getExisting()
    {
        AutoMutex _l(mLock);
        return mBinder.promote();
    }
private:
    Mutex           mLock;
    wp<JavaBBinder> mBinder;
};
```

原来是一个 `JavaBBinder` 类型，它就是 native 层的服务端表示对象 `BBinder` 的子类型，看来 java 层服务最终的表示对象是 `JavaBBinder` 。

回到上面，最终调用了 `BinderProxy` 的 `transact` 方法发送注册消息。

### BinderProxy

`BinderProxy` 是 `Binder` 的内部类型，查看它的 `transact` 方法实现：

```java
// Binder.java - class BinderProxy

public boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
    Binder.checkParcel(this, code, data, "Unreasonably large binder buffer");
    return transactNative(code, data, reply, flags);
}

public native boolean transactNative(int code, Parcel data, Parcel reply,
                                     int flags) throws RemoteException;
```

还是由 native 层实现。

```c++
// android_util_Binder.cpp

static jboolean android_os_BinderProxy_transact(JNIEnv* env, jobject obj,
        jint code, jobject dataObj, jobject replyObj, jint flags) // throws RemoteException
{
    if (dataObj == NULL) {
        jniThrowNullPointerException(env, NULL);
        return JNI_FALSE;
    }

    // java 层 Parcel 映射成 native 层 Parcel。
    Parcel* data = parcelForJavaObject(env, dataObj);
    if (data == NULL) {
        return JNI_FALSE;
    }
    Parcel* reply = parcelForJavaObject(env, replyObj);
    if (reply == NULL && replyObj != NULL) {
        return JNI_FALSE;
    }

    IBinder* target = (IBinder*)
        env->GetLongField(obj, gBinderProxyOffsets.mObject);
    if (target == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", "Binder has been finalized!");
        return JNI_FALSE;
    }

    ...
    
    // target 就是 BpBinder，保存在了 BinderProxy 的 mObject 成员变量里面。
    //printf("Transact from Java code to %p sending: ", target); data->print();
    status_t err = target->transact(code, *data, reply, flags);
    //if (reply) printf("Transact from Java code to %p received: ", target); reply->print();

    if (kEnableBinderSample) {
        if (time_binder_calls) {
            conditionally_log_binder_call(start_millis, target, code);
        }
    }

    if (err == NO_ERROR) {
        return JNI_TRUE;
    } else if (err == UNKNOWN_TRANSACTION) {
        return JNI_FALSE;
    }

    signalExceptionForError(env, obj, err, true /*canThrowRemoteException*/, data->dataSize());
    return JNI_FALSE;
}
```

这里就明确了，`BinderProxy` 最终还是使用 `BpBinder` 的函数向 native 层的 `ServiceManager` 发送消息的。

其他相关方法和 `addService` 方法实现最终都会调用对应的 `BpBinder` 的通信函数实现。

下面用时序图表示上述 `addService` 过程。

### 时序图

![](./image/android_binder_implement_java/java_binder_addService.png)

## Binder 通信框架

上面分析了实现 java 层 ServiceManager 的几个重要类型，接下来分析 java 层 Binder 具体通信的实现方式，两者结合起来就能准确表达出 java 层 Binder 框架的设计了。

这里选择 android 系统中的 `ActivityManagerService(AMS)` 服务作为典型案例，分析它的注册以及如何处理客户端的请求。

## Binder 服务端

首先分析 Binder 服务端是如何注册自己以及如何处理客户端请求的。

### ActivityManagerService

ActivityManagerService 在它的 `setSystemProcess` 方法中注册自己为系统服务，这个方法是由 java 层的 `SystemServer` 类调用。

```java
// ActivityManagerService.java

public void setSystemProcess() {
    try {
        //  这里注册了自身。
        ServiceManager.addService(Context.ACTIVITY_SERVICE, this, true);
        // 注册其它相关服务。
        ServiceManager.addService(ProcessStats.SERVICE_NAME, mProcessStats);
        ServiceManager.addService("meminfo", new MemBinder(this));
        ServiceManager.addService("gfxinfo", new GraphicsBinder(this));
        ServiceManager.addService("dbinfo", new DbBinder(this));
        if (MONITOR_CPU_USAGE) {
            ServiceManager.addService("cpuinfo", new CpuBinder(this));
        }
        ServiceManager.addService("permission", new PermissionController(this));
        ServiceManager.addService("processinfo", new ProcessInfoService(this));

        ApplicationInfo info = mContext.getPackageManager().getApplicationInfo(
            "android", STOCK_PM_FLAGS);
        mSystemThread.installSystemApplicationInfo(info, getClass().getClassLoader());

        synchronized (this) {
            ...
        }
    } catch (PackageManager.NameNotFoundException e) {
        throw new RuntimeException(
            "Unable to find android system package", e);
    }
}
```

上面分析过，服务注册时，最终 jni 层会从 java 层对象中获取出一个 `JavaBBinder` 对象表示服务端 Binder，那么这个 `JavaBBinder` 对象是何时和 java 层的 `ActivityManagerService` 关联的呢。

在 `ActivityManagerService` 的顶级父类 `Binder` 中，`ActivityManagerService` 继承了 `ActivityManagerNative` 类型，它的父类是 `Binder`，在 `Binder` 的构造器中有一个 `init` 方法：

```java
// Binder.java

public Binder() {
    init();

    if (FIND_POTENTIAL_LEAKS) {
        final Class<? extends Binder> klass = getClass();
        if ((klass.isAnonymousClass() || klass.isMemberClass() || klass.isLocalClass()) &&
            (klass.getModifiers() & Modifier.STATIC) == 0) {
            Log.w(TAG, "The following Binder class should be static or leaks might occur: " +
                  klass.getCanonicalName());
        }
    }
}
...

private native final void init();
```

它的实现在 native 层中：

```java
// android_utl_Binder.cpp

static void android_os_Binder_init(JNIEnv* env, jobject obj)
{
    JavaBBinderHolder* jbh = new JavaBBinderHolder();
    if (jbh == NULL) {
        jniThrowException(env, "java/lang/OutOfMemoryError", NULL);
        return;
    }
    ALOGV("Java Binder %p: acquiring first ref on holder %p", obj, jbh);
    jbh->incStrong((void*)android_os_Binder_init);
    // 这里将 JavaBBinderHolder 的指针关联到 AMS 的 mObject 成员上。
    env->SetLongField(obj, gBinderOffsets.mObject, (jlong)jbh);
}
```

在 jni 层的 `init` 函数中，`JavaBBinderHolder` 对象和 `ActivityManagerSerivce` 对象进行了绑定。这里仅仅创建了一个 `JavaBBinderHolder` 对象，那么前面分析的 `JavaBBinder` 对象又如何代表 java 层的 `ActivityManagerService` 服务端对象呢，在前面的 `JavaBBinderHolder` 的 `get` 函数中创建了 `JavaBBinder` 的对象：

```cpp
// android_util_Binder.cpp - class JavaBBinderHolder

sp<JavaBBinder> get(JNIEnv* env, jobject obj)
 {
     AutoMutex _l(mLock);
     sp<JavaBBinder> b = mBinder.promote();
     if (b == NULL) {
         // 这里创建了 JavaBBinder。
         b = new JavaBBinder(env, obj);
         mBinder = b;
         ALOGV("Creating JavaBinder %p (refs %p) for Object %p, weakCount=%" PRId32 "\n",
               b.get(), b->getWeakRefs(), obj, b->getWeakRefs()->getWeakCount());
     }

     return b;
 }
```

创建 `JavaBBinder` 时传递了 `ActivityManagerService` 的 `obj` 对象。

### JavaBBinder

那么分析 `JavaBBinder` 的实现：

```c++
// android_utl_Binder.cpp - class JavaBBinder

class JavaBBinder : public BBinder
{
public:
    JavaBBinder(JNIEnv* env, jobject object)
        : mVM(jnienv_to_javavm(env)), mObject(env->NewGlobalRef(object))
    {
        ALOGV("Creating JavaBBinder %p\n", this);
        android_atomic_inc(&gNumLocalRefs);
        incRefsCreated(env);
    }

    bool    checkSubclass(const void* subclassID) const
    {
        return subclassID == &gBinderOffsets;
    }

    jobject object() const
    {
        return mObject;
    }

protected:
    virtual ~JavaBBinder()
    {
        ALOGV("Destroying JavaBBinder %p\n", this);
        android_atomic_dec(&gNumLocalRefs);
        JNIEnv* env = javavm_to_jnienv(mVM);
        env->DeleteGlobalRef(mObject);
    }

    virtual status_t onTransact(
        uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags = 0)
    {
        JNIEnv* env = javavm_to_jnienv(mVM);

        ALOGV("onTransact() on %p calling object %p in env %p vm %p\n", this, mObject, env, mVM);

        IPCThreadState* thread_state = IPCThreadState::self();
        const int32_t strict_policy_before = thread_state->getStrictModePolicy();

        //printf("Transact from %p to Java code sending: ", this);
        //data.print();
        //printf("\n");
        // 注意这里将 data 和 reply 数据包转发给了 java 层 Binder 对象的 execTransact 方法。
        // gBinderOffsets.mExecTransact 在 Binder 框架准备工作时存放了 execTransact 的 ID。
        jboolean res = env->CallBooleanMethod(mObject, gBinderOffsets.mExecTransact,
            code, reinterpret_cast<jlong>(&data), reinterpret_cast<jlong>(reply), flags);

        if (env->ExceptionCheck()) {
            jthrowable excep = env->ExceptionOccurred();
            report_exception(env, excep,
                "*** Uncaught remote exception!  "
                "(Exceptions are not yet supported across processes.)");
            res = JNI_FALSE;

            /* clean up JNI local ref -- we don't return to Java code */
            env->DeleteLocalRef(excep);
        }

        // Check if the strict mode state changed while processing the
        // call.  The Binder state will be restored by the underlying
        // Binder system in IPCThreadState, however we need to take care
        // of the parallel Java state as well.
        if (thread_state->getStrictModePolicy() != strict_policy_before) {
            set_dalvik_blockguard_policy(env, strict_policy_before);
        }

        if (env->ExceptionCheck()) {
            jthrowable excep = env->ExceptionOccurred();
            report_exception(env, excep,
                "*** Uncaught exception in onBinderStrictModePolicyChange");
            /* clean up JNI local ref -- we don't return to Java code */
            env->DeleteLocalRef(excep);
        }

        // Need to always call through the native implementation of
        // SYSPROPS_TRANSACTION.
        if (code == SYSPROPS_TRANSACTION) {
            BBinder::onTransact(code, data, reply, flags);
        }

        //aout << "onTransact to Java code; result=" << res << endl
        //    << "Transact from " << this << " to Java code returning "
        //    << reply << ": " << *reply << endl;
        return res != JNI_FALSE ? NO_ERROR : UNKNOWN_TRANSACTION;
    }

    virtual status_t dump(int fd, const Vector<String16>& args)
    {
        return 0;
    }
private:
    JavaVM* const   mVM;
    jobject const   mObject;
};
```

可以看到在服务端 Binder 的消息处理函数 `onTransact` 中将数据包转发给了 java 层的 Binder 对象。

### Binder

```java
// Binder.java

// Entry point from android_util_Binder.cpp's onTransact
private boolean execTransact(int code, long dataObj, long replyObj,
                             int flags) {
    Parcel data = Parcel.obtain(dataObj);
    Parcel reply = Parcel.obtain(replyObj);
    // theoretically, we should call transact, which will call onTransact,
    // but all that does is rewind it, and we just got these from an IPC,
    // so we'll just call it directly.
    boolean res;
    // Log any exceptions as warnings, don't silently suppress them.
    // If the call was FLAG_ONEWAY then these exceptions disappear into the ether.
    try {
        // 调用自身的 `onTransact` 方法处理消息，子类会重写这个方法。
        res = onTransact(code, data, reply, flags);
    } catch (RemoteException e) {
        if ((flags & FLAG_ONEWAY) != 0) {
            Log.w(TAG, "Binder call failed.", e);
        } else {
            reply.setDataPosition(0);
            reply.writeException(e);
        }
        res = true;
    } catch (RuntimeException e) {
        if ((flags & FLAG_ONEWAY) != 0) {
            Log.w(TAG, "Caught a RuntimeException from the binder stub implementation.", e);
        } else {
            reply.setDataPosition(0);
            reply.writeException(e);
        }
        res = true;
    } catch (OutOfMemoryError e) {
        // Unconditionally log this, since this is generally unrecoverable.
        Log.e(TAG, "Caught an OutOfMemoryError from the binder stub implementation.", e);
        RuntimeException re = new RuntimeException("Out of memory", e);
        reply.setDataPosition(0);
        reply.writeException(re);
        res = true;
    }
    checkParcel(this, code, reply, "Unreasonably large binder reply buffer");
    reply.recycle();
    data.recycle();

    // Just in case -- we are done with the IPC, so there should be no more strict
    // mode violations that have gathered for this thread.  Either they have been
    // parceled and are now in transport off to the caller, or we are returning back
    // to the main transaction loop to wait for another incoming transaction.  Either
    // way, strict mode begone!
    StrictMode.clearGatheredViolations();

    return res;
}
```

### ActivityManagetNative

最后 `ActivityManagerService` 的父类 `ActivityManagerNative` 类将会重写 `onTransact` 方法，处理服务端的请求，并将请求交由 `ActivityManagerService` 处理，它的身份类似于 native 层的 `BnXXService`，作为服务端的代理类型，使服务对象专注于处理业务逻辑，而不是信息的交互。

下面是 `ActivityManagerNative` 的 `onTransact` 处理单个请求的示例：

```java
 @Override
public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
    throws RemoteException {
    switch (code) {
        // 处理 startActivity 请求。
        case START_ACTIVITY_TRANSACTION:
        {
            data.enforceInterface(IActivityManager.descriptor);
            IBinder b = data.readStrongBinder();
            IApplicationThread app = ApplicationThreadNative.asInterface(b);
            String callingPackage = data.readString();
            Intent intent = Intent.CREATOR.createFromParcel(data);
            String resolvedType = data.readString();
            IBinder resultTo = data.readStrongBinder();
            String resultWho = data.readString();
            int requestCode = data.readInt();
            int startFlags = data.readInt();
            ProfilerInfo profilerInfo = data.readInt() != 0
               ? ProfilerInfo.CREATOR.createFromParcel(data) : null;
            Bundle options = data.readInt() != 0
               ? Bundle.CREATOR.createFromParcel(data) : null;
            // 让 ActivityManagerService 处理实际业务。
            int result = startActivity(app, callingPackage, intent, resolvedType,
                                       resultTo, resultWho, requestCode, startFlags, profilerInfo, options);
            reply.writeNoException();
            reply.writeInt(result);
            return true;
        }
    }  
    ...
    return super.onTransact(code, data, reply, flags);
}
```

好了，到这里就了解了服务端的注册，和如何处理消息了，下面需要分析客户端是如何请求的。

## Binder 客户端

在平时的应用开发工作中使用系统服务的时，通常使用 `Context` 的 `getSystemService` 方法获取系统服务，然后使用，那么就从这里开始分析，看如何使用系统的服务。

这里假设需要获取系统中正在运行的应用列表，那么需要获取 `ActivityManager` 服务，并使用它的 `getRunningAppProcesses` 方法，代码如下：

```java
// Context.ACTIVITY_SERVICE = "activity".
ActivityManager am = (ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
List<ActivityManager.RunningAppProcessInfo> appProcesses = am.getRunningAppProcesses();
```

那么就看 `getSystemService` 方法的实现，它在 `Context` 的实现类 `ContextImple` 中：

### ContextImpl

```java
// ContextImpl.java

@Override
public Object getSystemService(String name) {
    return SystemServiceRegistry.getSystemService(this, name);
}
```

### SystemServiceRegistry

使用了 `SystemServiceRegistry` 这个类，从名字上看是负责服务注册的工作：

```java
// SystemServiceRegistry.java

public static Object getSystemService(ContextImpl ctx, String name) {
	// 从 map 中取出了一个 fetcher.
    ServiceFetcher<?> fetcher = SYSTEM_SERVICE_FETCHERS.get(name);
    return fetcher != null ? fetcher.getService(ctx) : null;
}
```

发现是从一个 map 中取出来一个 `ServiceFercher` 对象，然后再用它的 `getService` 方法返回的。

跟踪这个 `SYSTEM_SERVICE_FETCHERS`，看它是在哪里放入对象的。

```java
// SystemServiceRegistry.java

static {
    ...
    registerService(Context.ACTIVITY_SERVICE, ActivityManager.class,
        new CachedServiceFetcher<ActivityManager>() {
    @Override
    public ActivityManager createService(ContextImpl ctx) {
        return new ActivityManager(ctx.getOuterContext(), ctx.mMainThread.getHandler());
    }});

    registerService(Context.ALARM_SERVICE, AlarmManager.class,
        new CachedServiceFetcher<AlarmManager>() {
    @Override
    public AlarmManager createService(ContextImpl ctx) {
        IBinder b = ServiceManager.getService(Context.ALARM_SERVICE);
        IAlarmManager service = IAlarmManager.Stub.asInterface(b);
        return new AlarmManager(service, ctx);
    }});
    ...
}
```

发现在类的静态块中注册了很多的服务，其中就包括 `Context.Activity_Service` 服务。

查看 `registerService` 注册方法：

```java
// SystemServiceRegistry.java

private static <T> void registerService(String serviceName, Class<T> serviceClass,
        ServiceFetcher<T> serviceFetcher) {
    SYSTEM_SERVICE_NAMES.put(serviceClass, serviceName);
    SYSTEM_SERVICE_FETCHERS.put(serviceName, serviceFetcher);
}
```

原来注册就是将名字和对应服务的 `ServiceFetcher` 对象放入 map 缓存起来。

那么看这个 `ServiceFetcher` 里面是什么，上面的 `ACTIVITY_SERVICE` 对应了一个 `CachedServiceFetcher` 对象，它的 `createService` 返回了一个 `new ActivityManager` 的对象，`createService` 对应前面的 `getService` 方法，这里不关心它的实现细节，那么看到这里就了解到  `context.getSystemService(Context.ACTIVITY_SERVICE)` 返回的就是一个 `ActivtyManager` 对象。

### ActivityManager

下面就重点关注 `ActivityManager` 的实现了，直接看 `getRunningAppProcesses` 方法的实现：

```java
// ActivityManager.java

public List<RunningAppProcessInfo> getRunningAppProcesses() {
    try {
        return ActivityManagerNative.getDefault().getRunningAppProcesses();
    } catch (RemoteException e) {
        return null;
    }
}
```

使用到了 `ActivityManagerNative` 这个类，它是 `ActivityManagerService` 的父类。

### ActivityManagerNative

看它的 `getDefault` 方法：

```java
// ActivityManagerNative.java

static public IActivityManager getDefault() {
    return gDefault.get();
}
```

```java
// ActivityManagerNative.java

private static final Singleton<IActivityManager> gDefault = new Singleton<IActivityManager>() {
    protected IActivityManager create() {
        IBinder b = ServiceManager.getService("activity");
        if (false) {
            Log.v("ActivityManager", "default service binder = " + b);
        }
        IActivityManager am = asInterface(b);
        if (false) {
            Log.v("ActivityManager", "default service = " + am);
        }
        return am;
    }
};
```

它是一个单例，看来 `create` 方法返回的就是 `ActivtyManager` 的实现类了。

首先它的第一行通过 `ServiceManager` 的 `getService` 方法获取了一个 `IBinder` 对象，注意这里通过 `"activity"`   这个名字想要获取的服务就对应前面分析过的 `ActivityManagerService` 服务，它注册了 `"activity"` 这个名字。

前面分析了 `ServiceManager` 的 `addService` 方法，这里就直接看 `ServiceManagerProxy` 的 `getService` 方法吧：

```java
// ServiceManagerNative.java - class ServiceManagerProxy

public IBinder getService(String name) throws RemoteException {
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    data.writeInterfaceToken(IServiceManager.descriptor);
    data.writeString(name);
    mRemote.transact(GET_SERVICE_TRANSACTION, data, reply, 0);
    IBinder binder = reply.readStrongBinder();
    reply.recycle();
    data.recycle();
    return binder;
}
```

最终返回了 `replay.readStrongBinder` 对象，在 jni 层实现：

```c++
// android_os_Pracel.cpp

static jobject android_os_Parcel_readStrongBinder(JNIEnv* env, jclass clazz, jlong nativePtr)
{
    Parcel* parcel = reinterpret_cast<Parcel*>(nativePtr);
    if (parcel != NULL) {
        return javaObjectForIBinder(env, parcel->readStrongBinder());
    }
    return NULL;
}
```

从之前的 native 层中分析可以知道 `parcel->readStrongBinder()` 返回的是一个 `BpBinder` 对象，它表示客户端 Binder，内部有服务端 Binder 的引用号，并可向 Binder 驱动发送消息，这里就是对应 `ActivityManagerService` 服务端了。

这个 `javaObjectForIBinder` 在前面分析 `ServiceManager` 的时候，已经知道返回的是 `BpBinder` 对象。

```c++
// android_os_Pracel.cpp

jobject javaObjectForIBinder(JNIEnv* env, const sp<IBinder>& val)
{
    ...
    // 创建了一个 java 层的 BinderProxy 对象。
    object = env->NewObject(gBinderProxyOffsets.mClass, gBinderProxyOffsets.mConstructor);
    if (object != NULL) {
        LOGDEATH("objectForBinder %p: created new proxy %p !\n", val.get(), object);
        // The proxy holds a reference to the native object.
        env->SetLongField(object, gBinderProxyOffsets.mObject, (jlong)val.get());
        val->incStrong((void*)javaObjectForIBinder);

        // The native object needs to hold a weak reference back to the
        // proxy, so we can retrieve the same proxy if it is still active.
        jobject refObject = env->NewGlobalRef(
                env->GetObjectField(object, gBinderProxyOffsets.mSelf));
        // 将 java 层 BinderProxy 对象绑带到了 BpBinder 对象中。
        val->attachObject(&gBinderProxyOffsets, refObject,
                jnienv_to_javavm(env), proxy_cleanup);

        // Also remember the death recipients registered on this proxy
        sp<DeathRecipientList> drl = new DeathRecipientList;
        drl->incStrong((void*)javaObjectForIBinder);
        // 绑定了一个 DeathRecipientList 对象到 BinderProxy 的 mOrgue 对象。
        env->SetLongField(object, gBinderProxyOffsets.mOrgue, reinterpret_cast<jlong>(drl.get()));

        // Note that a new object reference has been created.
        android_atomic_inc(&gNumProxyRefs);
        incRefsCreated(env);
    }

    return object;
}
```

从这里可以看出，java 层客户端 Binder 的表示类型即为 `BinderProxy`。

那么继续看上面的 `IBinder b`，这个 `b` 就是 `BinderProxy`  的对象。

下面一行从 `asInterface` 方法返回了一个 `IActivityManager` 对象。

```java
static public IActivityManager asInterface(IBinder obj) {
    if (obj == null) {
        return null;
    }
    
    // 前面分析过 BinderProxy 会返回 null。
    IActivityManager in =
        (IActivityManager)obj.queryLocalInterface(descriptor);
    if (in != null) {
        return in;
    }

    return new ActivityManagerProxy(obj);
}
```

返回的是一个 `ActivityManagerProxy` 对象，内部包含了 `BinderProxy` 客户端 Binder 表示对象。

### ActivityManagerProxy

辗转到这里，终于找到了 `ActivityManager` 的实现类 `ActivityManagerProxy` 就看它的 `getRunningAppProcesses` 方法的实现吧：

```java
// ActivityManagerNative.java - class ActivityManagerProxy

public List<ActivityManager.RunningAppProcessInfo> getRunningAppProcesses()
    throws RemoteException {
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    data.writeInterfaceToken(IActivityManager.descriptor);
    mRemote.transact(GET_RUNNING_APP_PROCESSES_TRANSACTION, data, reply, 0);
    reply.readException();
    ArrayList<ActivityManager.RunningAppProcessInfo> list
        = reply.createTypedArrayList(ActivityManager.RunningAppProcessInfo.CREATOR);
    data.recycle();
    reply.recycle();
    return list;
}
```

又看到了熟悉的代码，和前面 `ServiceManagerProxy` 的形式一致，那么最终 `getRunningAppProcesses` 方法将通过 `mRemote` 即 `BinderProxy` 的 `transact` 方法，通过 native 层的 `BpBinder` 的 `transact` 函数将 `GET_RUNNING_APP_PROCESSES_TRANSACTION` 的请求辗转发送到 java 层服务端的代表类型 `JavaBBinder` 对象中。这个 `JavaBBinder` 绑定了 java 层的 `Binder` 对象，并回调它的 `onTransact` 方法，最终将消息发送到 `ActivityManagerNative` 的 `onTransact` 方法中：

### ActivityManagerNative

```java
// ActivityManagerNative.java

@Override
public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
        throws RemoteException {
    switch (code) {
    case GET_RUNNING_APP_PROCESSES_TRANSACTION: {
        data.enforceInterface(IActivityManager.descriptor);
        List<ActivityManager.RunningAppProcessInfo> list = getRunningAppProcesses();
        reply.writeNoException();
        reply.writeTypedList(list);
        return true;
    }
    ...
    return super.onTransact(code, data, reply, flags);
}
```

最终由 `getRunningAppProcesses` 实现，它在 `ActivityManagerService` 中，是服务端的真正功能的实现：

```java
// ActivityManagerService.java

public List<ActivityManager.RunningAppProcessInfo> getRunningAppProcesses() {
    enforceNotIsolatedCaller("getRunningAppProcesses");

    final int callingUid = Binder.getCallingUid();

    // Lazy instantiation of list
    List<ActivityManager.RunningAppProcessInfo> runList = null;
    final boolean allUsers = ActivityManager.checkUidPermission(INTERACT_ACROSS_USERS_FULL,
            callingUid) == PackageManager.PERMISSION_GRANTED;
    final int userId = UserHandle.getUserId(callingUid);
    final boolean allUids = isGetTasksAllowed(
            "getRunningAppProcesses", Binder.getCallingPid(), callingUid);

    synchronized (this) {
        // Iterate across all processes
        for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
            ProcessRecord app = mLruProcesses.get(i);
            if ((!allUsers && app.userId != userId)
                    || (!allUids && app.uid != callingUid)) {
                continue;
            }
            if ((app.thread != null) && (!app.crashing && !app.notResponding)) {
                // Generate process state info for running application
                ActivityManager.RunningAppProcessInfo currApp =
                    new ActivityManager.RunningAppProcessInfo(app.processName,
                            app.pid, app.getPackageList());
                fillInProcMemInfo(app, currApp);
                if (app.adjSource instanceof ProcessRecord) {
                    currApp.importanceReasonPid = ((ProcessRecord)app.adjSource).pid;
                    currApp.importanceReasonImportance =
                            ActivityManager.RunningAppProcessInfo.procStateToImportance(
                                    app.adjSourceProcState);
                } else if (app.adjSource instanceof ActivityRecord) {
                    ActivityRecord r = (ActivityRecord)app.adjSource;
                    if (r.app != null) currApp.importanceReasonPid = r.app.pid;
                }
                if (app.adjTarget instanceof ComponentName) {
                    currApp.importanceReasonComponent = (ComponentName)app.adjTarget;
                }
                //Slog.v(TAG, "Proc " + app.processName + ": imp=" + currApp.importance
                //        + " lru=" + currApp.lru);
                if (runList == null) {
                    runList = new ArrayList<>();
                }
                runList.add(currApp);
            }
        }
    }
    return runList;
}
```

至于具体实现逻辑，在这里并不重要，最后的结果通过 `ActivityManagerNative` 使用 `Parcel` 数据包 `replay`  回复至 `ActivityManagerProxy`，然后返回给 `ActivityManager` 重要的是，分析到这里，终于打通了客户端到服务端的通信流程。

下面用时序图表示客户端请求到服务端接收的完整 `getRunningAppProcesses` 请求的实现。

![](./image/android_binder_implement_java/java_binder_client_server.png)

## java 层 Binder 框架总结

根据以上分析总结出如下 java 层 Binder 框架，首先是客户端与服务端通信的数据流图。

### 数据流图

![](./image/android_binder_implement_java/java_binder_transfer.png)

java 层 Binder 框架与 native 框架很类似，客户端和服务端都有其对应的代理对象。

- XXManager 为服务端提供给用户的客户端接口，为用户提供真实服务端所需的服务，与服务端接口保持一致，例如 `ActivityManager`。
- XXManagerProxy 是客户端的代理，负责处理用户向客户端的提出的服务请求，将数据打包发送至服务端。
- BinderProxy 是客户端 Binder 的在 java 层的表示，它是 native 层 BpBinder 的映射类型。
- JavaBBinder 是 java 层服务端在 native 层的表示，同时也是服务端 Binder 的表示，它是一个 BBinder 类型。
- XXManagerNative 是服务端的代理，负责处理客户端向服务端发送的请求，解析数据包转发给服务端处理。
- XXManagerService 是服务端的实现类，为用户提供具体的服务。

### 框架类图

![](./image/android_binder_implement_java/java_binder_frame.png)