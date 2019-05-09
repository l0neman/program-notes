# Android Binder 的设计、实现与应用 - Binder 实现部分

# Native 层

## 前言

通过分析 MediaServer 的实现，了解其中的与 Binder 相关的逻辑，作为典型案例，以理解 Binder 具体是如何工作的。

MediaServer 是 Android 系统中极其中重要的系统服务，它包含了媒体相关的多个核心服务，包括：AuditFlinger(音频核心服务)，AudiaPolicyService(音频系统策略相关服务)，MediaPlayerService(多媒体系统服务)，CameraService(相机相关服务)。

MediaServer 本身是一个 Binder Server 端，它将向 ServiceManager 请求注册服务，并且通过 Binder 通信与 Client 端沟通，所以通过解析 MediaServer 的实现，理解 Binder 系统的具体实现。

下面分析 Android 6.0 系统中 MediaServer 的源码。

## todo 源码列表

```c++
// todo add source list.
```

## 入口

MediaServer 的入口在 `main_mediaserver.h` 文件中。

```c++
// main_mediaserver.h

int main(int argc __unuserd, char** argv)
{
  	// 省略两行无关代码。
    // 初始化 Unicode 支持这里与 binder 无关。
    InitializeIcuOrDie();
    // sp 为 Android 定义的智能指针，StrongPointer。
    // 获取 ProcessState 在进程中的单例，其内部会打开 binder 驱动。
    sp<ProcessState> proc(ProcessState::self());
    // 获取 ServiceManager，为注册 Server 端服务。
    sp<IServiceManager> sm = defaultServiceManager();
    ALOGI("ServiceManager: %p", sm.get());
    // 注册 AudioFlinger。
    AudioFlinger::instantiate();
    // 内部注册 MediaPlayerService。
    MediaPlayerService::instantiate();
    // 注册相关服务。
    ResourceManagerService::instantiate();
    CameraService::instantiate();
    AudioPolicyService::instantiate();
    SoundTriggerHwService::instantiate();
    RadioService::instantiate();
    registerExtensions();
    // 启用线程收发 Binder 数据包。
    ProcessState::self()->startThreadPool();
    IPCThreadState::self()->joinThreadPool();
}
```

## 打开 binder 驱动

### ProcessState

首先分析第 2 句，通过 `ProcessState` 的 `self` 方法得到了 `ProcessState` 的一个对象。

```c++
// ProcessState.cpp

sp<ProcessState> ProcessState::self()
{
    // 持有线程锁。
    Mutex::Autolock _l(gProcessMutex);
    if (gProcess != NULL) {
        return gProcess;
    }
    gProcess = new ProcessState;
    return gProcess;
}
```

这里用单例模式来保证 `ProcessState` 在当前进程中只有一个实例，下面是构造器。

```c++
// ProcessState.cpp

ProcessState::ProcessState()
    // 内部打开 binder 驱动。
    : mDriverFD(open_driver())
    , mVMStart(MAP_FAILED)
    , mThreadCountLock(PTHREAD_MUTEX_INITIALIZER)
    , mThreadCountDecrement(PTHREAD_COND_INITIALIZER)
    , mExecutingThreadsCount(0)
    // #define DEFAULT_MAX_BINDER_THREADS 15，最大线程数。
    , mMaxThreads(DEFAULT_MAX_BINDER_THREADS)
    , mManagesContexts(false)
    , mBinderContextCheckFunc(NULL)
    , mBinderContextUserData(NULL)
    , mThreadPoolStarted(false)
    , mThreadPoolSeq(1)
{
    if (mDriverFD >= 0) {
// 这里 HAVE_WIN32_IPC 未定义值，所以判断为 true。
#if !defined(HAVE_WIN32_IPC)
        // #define BINDER_VM_SIZE ((1*1024*1024) - (4096 *2))，即 1M-8k 大小。
        // 为接受方进程创建了一片大小为 BINDER_VM_SIZE 的接收缓存区，mmap() 返回内存映射在用户空间的地址，这段空间由驱动管理，用户不能直接访问（类型为 PROT_READ 只读映射）
        mVMStart = mmap(0, BINDER_VM_SIZE, PROT_READ, MAP_PRIVATE | MAP_NORESERVE, mDriverFD, 0);
        if (mVMStart == MAP_FAILED) {
            ALOGE("Using /dev/binder failed: unable to mmap transaction memory.\n");
            close(mDriverFD);
            mDriverFD = -1;
        }
#else
        mDriverFD = -1;
#endif
    }
    LOG_ALWAYS_FATAL_IF(mDriverFD < 0, "Binder driver could not be opened.  Terminating.");
}
```

ProcessState 类的构造器中做了两个重要的工作：

1. 打开驱动。
2. 为接受方创建接收缓存区。

下面是 `open_driver()` 函数所做的工作：

```c++
// ProcessState.cpp

static int open_driver()
{
	// 通过系统调用打开 binder 驱动设备，得到 binder 文件描述符。
    int fd = open("/dev/binder", O_RDWR);
    if (fd >= 0) {
        fcntl(fd, F_SETFD, FD_CLOEXEC);
        int vers = 0;
       	// 通过 BINDER_VERSION 命令获取 binder 驱动版本。
        status_t result = ioctl(fd, BINDER_VERSION, &vers);
        if (result == -1) {
            ALOGE("Binder ioctl to obtain version failed: %s", strerror(errno));
            close(fd);
            fd = -1;
        }
        // #define BINDER_CURRENT_PROTOCOL_VERSION 7，当前驱动版本为 7。
        if (result != 0 || vers != BINDER_CURRENT_PROTOCOL_VERSION) {
            ALOGE("Binder driver protocol does not match user space protocol!");
            close(fd);
            fd = -1;
        }
        size_t maxThreads = DEFAULT_MAX_BINDER_THREADS;
        // 通过 BINDER_SET_MAX_THREADS 命令告知 Binder 驱动接收方（Server 端）线程池的最大线程数，为了让驱动发现线程数达到该值时不要再命令接收端启动新的线程。
        result = ioctl(fd, BINDER_SET_MAX_THREADS, &maxThreads);
        if (result == -1) {
            ALOGE("Binder ioctl to set max threads failed: %s", strerror(errno));
        }
    } else {
        ALOGW("Opening '/dev/binder' failed: %s\n", strerror(errno));
    }
    return fd;
}
```

可以看到 `ProcessState` 打开了 binder 驱动并保存了其描述符作为访问标识，并检查了驱动版本以及设置了接收端线程池的最大值。

至此 `ProcessState` 完成了它的构造任务。

## 获取 ServiceManager

回到 main 函数，下一句为 `sp<IServiceManager> sm = defaultServiceManager();`，此句为了获取 ServiceManger 组件，为后面注册 Service 做准备。

`defaultServiceManager`  函数在 `IServiceManager.cpp` 中。

### defaultServiceManager

```c++
// IServiceManager.cpp

sp<IServiceManager> defaultServiceManager()
{
    if (gDefaultServiceManager != NULL) return gDefaultServiceManager;
    {
        AutoMutex _l(gDefaultServiceManagerLock);
        while (gDefaultServiceManager == NULL) {
            gDefaultServiceManager = interface_cast<IServiceManager>(
                ProcessState::self()->getContextObject(NULL));
            if (gDefaultServiceManager == NULL)
                sleep(1);
        }
    }
    
    return gDefaultServiceManager;
}
```

这里也使用了单例模式，`gDefaultServiceManager` 通过一个 `interface_cast` 操作获得，内部传递了一个 `ProcessState` 的 `getContextObject(NULL)` 方法返回的对象。

```c++
// ProcessState.cpp

sp<IBinder> ProcessState::getContextObject(const sp<IBinder>& /*caller*/)
{
    return getStrongProxyForHandle(0);
}
```

```c++
// ProcessState.cpp

sp<IBinder> ProcessState::getStrongProxyForHandle(int32_t handle)
{
    sp<IBinder> result;
    AutoMutex _l(mLock);
    // 从列表中查询并插入新 handle_entry（handle），如果有则返回。
    handle_entry* e = lookupHandleLocked(handle);
    if (e != NULL) {
        IBinder* b = e->binder;
        // 如果为 NULL 或弱引用失效，则创建一个新的 BpBinder 对象。
        if (b == NULL || !e->refs->attemptIncWeak(this)) {
            if (handle == 0) {
                Parcel data;
                // 通过 0 号引用和 PING_TRANSACTION 操作测试 ServiceManager 是否有效。
                status_t status = IPCThreadState::self()->transact(
                        0, IBinder::PING_TRANSACTION, data, NULL, 0);
                if (status == DEAD_OBJECT)
                   return NULL;
            }
            // 创建 BpBinder 对象。
            b = new BpBinder(handle); 
            e->binder = b;
            if (b) e->refs = b->getWeakRefs();
            result = b;
        } else {
            result.force_set(b);
            e->refs->decWeak(this);
        }
    }
    return result;
}
```

`handle_entry` 结构体：

```c++
struct handle_entry {
    IBinder* binder;
    RefBase::weakref_type* refs;
};
```

查询并插入元素，`mHandleToObject` 为 `KeyedVector` 类型。

```c++
// ProcessState.cpp

ProcessState::handle_entry* ProcessState::lookupHandleLocked(int32_t handle)
{
    const size_t N=mHandleToObject.size();
    if (N <= (size_t)handle) {
        handle_entry e;
        e.binder = NULL;
        e.refs = NULL;
        // 扩容并向 N 位置插入元素。
        status_t err = mHandleToObject.insertAt(e, N, handle+1-N);
        if (err < NO_ERROR) return NULL;
    }
    return &mHandleToObject.editItemAt(handle);
}
```

由上可以看出 `ProcessState::self()->getContextObject(NULL)` 函数返回的是一个 `new BpBinder(0)` 对象。

### BpBinder

查看 `BpBinder` 的构造器，在 `BpBinder.cpp` 中：

```c++
// BpBinder.cpp

BpBinder::BpBinder(int32_t handle)
    // 保存 handle 值。
    : mHandle(handle)
    , mAlive(1)
    , mObitsSent(0)
    , mObituaries(NULL)
{
    ALOGV("Creating BpBinder %p handle %d\n", this, mHandle);
    // BpBinder 继承于 IBinder 和 RefBase。这是智能指针的方法，可控制自身对象的生命周期。
    // 延长自身生命周期，OBJECT_LIFETIME_WEAK 表示当强引用计数值和弱引用计数都为 0 时才释放对象内存。
    extendObjectLifetime(OBJECT_LIFETIME_WEAK);
    // 内部通过 BC_INCREFS 命令通知 binder 驱动增加 handle 的弱引用计数。
    IPCThreadState::self()->incWeakHandle(handle);
}
```

```c++
// IPCThreadState.cpp

void IPCThreadState::incWeakHandle(int32_t handle)
{
    LOG_REMOTEREFS("IPCThreadState::incWeakHandle(%d)\n", handle);
    mOut.writeInt32(BC_INCREFS);
    mOut.writeInt32(handle);
}
```

`BpBinder` 对象的作用就是保存 `mHandle` 的值和增加引用计数。

回到上面，此时 `gDefaultServiceManager` 将可改写成如下形式：

```c++
gDefaultServiceManager = interface_cast<IServiceManager>(new BpBinder(0));
```

`interface_cast` 将 `BpBinder ` 类型转化为 `IServiceManager` 类型，它的定义在 `IInterface.h` 中，它是一个模板类型。

```c++
// IInterface.h

template<typename INTERFACE>
inline sp<INTERFACE> interface_cast(const sp<IBinder>& obj)
{
    return INTERFACE::asInterface(obj);
}
```

将 `IServiceManager` 替换可得到：

```c++
template<typename IServiceManager>
inline sp<IServiceManager> interface_cast(const sp<IBinder>& obj)
{
    return IServiceManager::asInterface(obj);
}
```

追溯 `IServiceManager` 的 `asInterface()` 方法，发现没有这个方法，但是在 `IServiceManager.h`  中声明了一个 `DECLARE_META_INTERFACE` 宏。

```c++
// IServiceManager.h

DECLARE_META_INTERFACE(ServiceManager);
```

它的定义在 `IInterface.h` 中。

```c++
// IInterface.h

// ## 表示字符串连接。

#define DECLARE_META_INTERFACE(INTERFACE)                               \
    static const android::String16 descriptor;                          \
    static android::sp<I##INTERFACE> asInterface(                       \
            const android::sp<android::IBinder>& obj);                  \
    virtual const android::String16& getInterfaceDescriptor() const;    \
    I##INTERFACE();                                                     \
    virtual ~I##INTERFACE();                                            \
```

替换 `INTERFACE` 为 `ServiceManager` 得到如下定义：

```c++
// 描述字符串。
static const android::String16 descriptor;
// 这里定义了 asInterface 函数。
static android::sp<IServiceManager> asInterface(
    const android::sp<android::IBinder>& obj
);
// 获取描述字符串。
virtual const android::String16& getInterfaceDescriptor() const;
// 构造/析构器。
IServiceManager();
virtual ~IServiceManager();
```

`IInterface.h` 中还包含一个 `IMPLEMENT_META_INTERFACE` 宏，对应它的实现。

在 `IServiceManager.cpp` 中使用了它：

```c++
// IServiceManager.cpp

IMPLEMENT_META_INTERFACE(ServiceManager, "android.os.IServiceManager");
```

下面是它的实现：

```c++
// IInterface.cpp

#define IMPLEMENT_META_INTERFACE(INTERFACE, NAME)                       \
    const android::String16 I##INTERFACE::descriptor(NAME);             \
    const android::String16&                                            \
            I##INTERFACE::getInterfaceDescriptor() const {              \
        return I##INTERFACE::descriptor;                                \
    }                                                                   \
    android::sp<I##INTERFACE> I##INTERFACE::asInterface(                \
            const android::sp<android::IBinder>& obj)                   \
    {                                                                   \
        android::sp<I##INTERFACE> intr;                                 \
        if (obj != NULL) {                                              \
            intr = static_cast<I##INTERFACE*>(                          \
                obj->queryLocalInterface(                               \
                        I##INTERFACE::descriptor).get());               \
            if (intr == NULL) {                                         \
                intr = new Bp##INTERFACE(obj);                          \
            }                                                           \
        }                                                               \
        return intr;                                                    \
    }                                                                   \
    I##INTERFACE::I##INTERFACE() { }                                    \
    I##INTERFACE::~I##INTERFACE() { }                                   \
```

将上面的使用替换得到：

```c++
// 标识符为 "android.os.IServiceManager"
const android::String16 IServiceManager::descriptor("android.os.IServiceManager");
const android::String16& IServiceManager::getInterfaceDescriptor() const {
    return IServiceManager::descriptor;
}

android::sp<IServiceManager> IServiceManager::asInterface(
    const android::sp<android::IBinder>& obj)
{
    android::sp<IServiceManager> intr;
    if (obj != NULL) {
        intr = static_cast<IServiceManager*> (
            obj->queryLocalInterface(IServiceManager::descriptor).get());
        if (intr == NULL) {
            // 创建了 BpServiceManager 的实例。
            intr = new BpServiceManager(obj);
        }
    }

    return intr;
}

IServiceManager::IServiceManager() { }
IServiceManager::~IServiceManager() { }
```

其中的 `queryLocalInterface` 方法，BpBinder 并没有实现，所以是 `IBinder` 提供的默认实现：

```c++
// Binder.cpp

sp<IInterface>  IBinder::queryLocalInterface(const String16& /*descriptor*/)
{
    return NULL;
}
```

则由上面可以看出，`IServiceManager::asInterface` 函数最终返回的是一个 `new BpServiceManager(obj)`，回到上面的 `gDefaultServiceManager` 的获取：

```c++
gDefaultServiceManager = interface_cast<IServiceManager>(new BpBinder(0));
```

此时可表示为：

```c++
gDefaultServiceManager = new BpServiceManager(new BpBinder(0));
```

那么最终得到的 `gDefaultServiceManager` 对象实质上为 `new BpServiceManage(new BpBinder(0))`。

整理上面的流程，用时序图表示如下：

![getServiceManager](./image/android_binder_implement/getServiceManager.png)

下面分析 BpServiceManager 的实现。

### BpServiceManager

`BpServiceManager` 的实现在 `IServiceManager.cpp` 中。

```c++
// IServiceManager.cpp

class BpServiceManager : public BpInterface<IServiceManager>
{
public:
    BpServiceManager(const sp<IBinder>& impl)
        : BpInterface<IServiceManager>(impl) {}

	// 获得已注册过的 Service。
    virtual sp<IBinder> getService(const String16& name) const
    {
        unsigned n;
        for (n = 0; n < 5; n++){
            sp<IBinder> svc = checkService(name);
            if (svc != NULL) return svc;
            ALOGI("Waiting for service %s...\n", String8(name).string());
            sleep(1);
        }
        return NULL;
    }

	// 检查 Service 是否注册。
    virtual sp<IBinder> checkService( const String16& name) const
    {
        Parcel data, reply;
        data.writeInterfaceToken(IServiceManager::getInterfaceDescriptor());
        data.writeString16(name);
        remote()->transact(CHECK_SERVICE_TRANSACTION, data, &reply);
        return reply.readStrongBinder();
    }

	// 注册 Service。
    virtual status_t addService(const String16& name, const sp<IBinder>& service,
            bool allowIsolated)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IServiceManager::getInterfaceDescriptor());
        data.writeString16(name);
        data.writeStrongBinder(service);
        data.writeInt32(allowIsolated ? 1 : 0);
        status_t err = remote()->transact(ADD_SERVICE_TRANSACTION, data, &reply);
        return err == NO_ERROR ? reply.readExceptionCode() : err;
    }

	// 列出注册的所有 Service。
    virtual Vector<String16> listServices()
    {
        Vector<String16> res;
        int n = 0;

        for (;;) {
            Parcel data, reply;
            data.writeInterfaceToken(IServiceManager::getInterfaceDescriptor());
            data.writeInt32(n++);
            status_t err = remote()->transact(LIST_SERVICES_TRANSACTION, data, &reply);
            if (err != NO_ERROR)
                break;
            res.add(reply.readString16());
        }
        return res;
    }
}
```

其中父类 `BpInterface` 定义在 `IInterface.h` 中。

```c++
// IInterface.h

template<typename INTERFACE>
class BpInterface : public INTERFACE, public BpRefBase
{
public:
                                BpInterface(const sp<IBinder>& remote);
protected:
    virtual IBinder*            onAsBinder();
};

template<typename INTERFACE>
inline BpInterface<INTERFACE>::BpInterface(const sp<IBinder>& remote)
    : BpRefBase(remote) {}

template<typename INTERFACE>
inline IBinder* BpInterface<INTERFACE>::onAsBinder()
{    return remote();    }
```

`BpRefBase` 定义在了 `Binder.h` 中，内部持有一个 `mRemote` 对象，通过 `remote()` 函数获取。

`mRemote`  就是之前  `new BpServiceManager(new BpBinder(0))` 的 `new BpBinder(0)` 对象。

以上就是获取 ServiceManager 的整个过程，并追溯到了它的实现类型。

回到 `main_mediaserver.cpp` 的 `main` 函数中，获取 ServiceManager 后下面是几个系统服务的初始化。

```c++
// main_mediaserver.cpp

int main(int argc __unuserd, char** argv)
{
    ...
    sp<IServiceManager> sm = defaultServiceManager();
    // 注册相关服务。
    AudioFlinger::instantiate();
    MediaPlayerService::instantiate();
    ResourceManagerService::instantiate();
    CameraService::instantiate();
    AudioPolicyService::instantiate();
    SoundTriggerHwService::instantiate();
    RadioService::instantiate();
    ...
}
```

## 注册 Server 端

在 `main_mediaserver.cpp` 的 `main` 函数中，在获取 `SeviceManager` 后，初始化了多个系统服务，它们的 `instantiate` 方法中都做了类似的工作，即向 `ServiceManager` 注册自身为 Binder Server 端。

例如 `AudioFlinger` ，`CameraService`  和 `AudioPolicyService` 的都是 `BinderService` 的子类。

```c++
// AudioFlinger.h

class AudioFlinger :
    public BinderService<AudioFlinger>,
    public BnAudioFlinger
{    ...    }

// CameraService.h

class CameraService :
    public BinderService<CameraService>,
    public BnCameraService,
    public IBinder::DeathRecipient,
    public camera_module_callbacks_t
{    ...    }

// AudioPolicyService.h

class AudioPolicyService :
    public BinderService<AudioPolicyService>,
    public BnAudioPolicyService,
    public IBinder::DeathRecipient
{    ...    }
```

它们都使用了父类 `BinderService` 的  `instantiate()` 函数，向 `ServiceManager` 注册自身，最终都调用了 `ServiceManager` 的 `addService()` 方法。

```c++
// BinderService.h

template<typename SERVICE>
class BinderService
{
public:
    static status_t publish(bool allowIsolated = false) {
        sp<IServiceManager> sm(defaultServiceManager());
        return sm->addService(
                String16(SERVICE::getServiceName()),
                new SERVICE(), allowIsolated);
    }
    static void instantiate() { publish(); }
    ...
}
```

上面使用了模板的方式实现，但不够直接，这里可以直接参考 `MediaPlayerService` 的实现。

```c++
// MediaPlayerService.cpp

void MediaPlayerService::instantiate() {
    defaultServiceManager()->addService(
            String16("media.player"), new MediaPlayerService());
}
```

注册时传入一个服务的字符串标识和对象，下面分析 `addService` 的具体实现。

### addService

通过前面的分析获取到的 `defaultServiceManager` 对象是 `BpServiceManager` 的实例。

```c++
// IServiceMnager.cpp

class BpServiceManager : public BpInterface<IServiceManager>
{
public:
	// 注册 Service。
    virtual status_t addService(const String16& name, const sp<IBinder>& service,
            bool allowIsolated)
    {
        // data 为请求数据包，reply 为返回数据包。
        Parcel data, reply;
        data.writeInterfaceToken(IServiceManager::getInterfaceDescriptor());
        data.writeString16(name);
        data.writeStrongBinder(service);
        data.writeInt32(allowIsolated ? 1 : 0);
        //  通过 transact 发送 ADD_SERVICE_TRANSACTION 请求。
        status_t err = remote()->transact(ADD_SERVICE_TRANSACTION, data, &reply);
        return err == NO_ERROR ? reply.readExceptionCode() : err;
    }
    ...
}
```

`addService()` 函数中，通过数据包包将服务描述和服务对象打包通过 `remote()` 对象的 `transact()` 发送出去。

前面分析过，`remote()` 返回的是 `new BpBinder(0)`。

```c++
// BpBinder.cpp

status_t BpBinder::transact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    // Once a binder has died, it will never come back to life.
    // 一但 binder 死亡了，它就永远不会复活了。
    if (mAlive) {
        status_t status = IPCThreadState::self()->transact(
            mHandle, code, data, reply, flags);
        if (status == DEAD_OBJECT) mAlive = 0;
        return status;
    }

    return DEAD_OBJECT;
}
```

### IPCThreadState



## 数据包收发处理

### startThreadPool

### joinThreadPool

## ServiceManager 的注册



# Framework 层

### 前言

