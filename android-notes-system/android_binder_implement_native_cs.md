# Android Binder 的设计、实现与应用 - Native 层 Client-Server 通信分析

## 前言

通过分析客户端进程与服务端进程的完整通信过程了解 Android Binder 框架结构，接着上篇文档，继续分析 native 层服务，上篇文档中通过分析 `MediaPlayerService` 服务了解了服务端 Binder 的注册过程和 ServiceManager 的注册过程，但是并没有对 Binder 的 Native 层框架有一个概要认识，对于里面出现的 BpServceManager 和 BpBinder 类型也并不知道它们具体表示什么，所以还需要分析 Client-Server 通信过程，结合前面的分析之后得出结论。

那么就从 `MediaPlayerService` 开始，既然他作为服务端 Binder 而存在，那么必定有一个客户端在同它进行通信，这里选择 Android 中常用的 `MediaPlayer` 开始分析，看起来似乎与 `MediaPlayer` 有联系。

以下源码基于 Android 6.0.1 系统。

## MediaPlayer.java

MediaPalyer 是 Android 中的多媒体播放器，通过查看它的代码发现其可信功能都是由 native 层实现的，首先在起始代码处加载了对应的 c++ 库，调用了初始化方法。

```java
// MediaPlayer.java

static {
  System.loadLibrary("media_jni");
  native_init();
}
```

找一个常用方法看它的实现，例如 `setDataSource` 方法，发现它会调用到对应的 native 函数。

```java
// MediaPlayer.java

public void setDataSource(MediaDataSource dataSource) throws IllegalArgumentException, IllegalStateException {
  _setDataSource(dataSource);
}

private native void _setDataSource(MediaDataSource dataSource) throws IllegalArgumentException, IllegalStateException;
```

`start` 方法，也会调用到 native 函数。

```java
public void start() throws IllegalStateException {
  if (isRestricted()) {
    _setVolume(0, 0);
  }
  stayAwake(true);
  _start();
}

private native void _start() throws IllegalStateException;
```

通过阅读其他方法的实现，发现 MediaPlayer 完全是由 native 层实现的，它只是一个为应用层提供的接口。

jni 层对应的实现在 `android_media_MediaPlayer.cpp` 文件中，它会在 jni 初始化时注册一个全局的函数表，映射对应 java 层定义的 native 方法，例如 `_setDataSource` 方法，对应 c++ 中的 `android_media_MediaPlayer_setDataSourceCallback` 函数。

```c++
// android_media_MediaPlayer.cpp

static JNINativeMethod gMethods[] = {
    ...
    {"_setDataSource",      "(Landroid/media/MediaDataSource;)V",(void *)android_media_MediaPlayer_setDataSourceCallback },
    ...
```

追溯其实现：

```c++
// android_media_MediaPlayer.cpp

static void
android_media_MediaPlayer_setDataSourceCallback(JNIEnv *env, jobject thiz, jobject dataSource)
{
    sp<MediaPlayer> mp = getMediaPlayer(env, thiz);
    // 获取 native 层 MediaPlayer 的指针。
    if (mp == NULL ) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return;
    }

    if (dataSource == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return;
    }
    sp<IDataSource> callbackDataSource = new JMediaDataSource(env, dataSource);
    // 调用 mp 的 setDataSource 函数。
    process_media_player_call(env, thiz, mp->setDataSource(callbackDataSource), "java/lang/RuntimeException", "setDataSourceCallback failed." );
}
```

发现它获取了 c++ 实现的一个 `MediaPlayer` 类的对象指针，然后调用了它的 `setDataSource` 函数。

其他 jni 函数的实现都类似，最终都使了 `MediaPlayer` 这个类的对象，它实现和定义在 `mediaplayer.cpp` 和 `mediaplayer.h` 文件中，那么 java 层的 `MediaPlayer` 其实是 c++ 层 `MediaPlayer` 的一个接口层。

## mediaplayer.cpp 

分析的目标是搞清楚 Binder 通信规则，所以分析 mediaplayer.cpp 源码并不是主要目的，这里直接看函数的实现，继续上面的 `setDataSource` 函数。

```c++
// mediaplayer.cpp

status_t MediaPlayer::setDataSource(const sp<IStreamSource> &source)
{
    ALOGV("setDataSource");
    status_t err = UNKNOWN_ERROR;
    // 创建 IMediaPlayerService 对象。
    const sp<IMediaPlayerService>& service(getMediaPlayerService());
    if (service != 0) {
        // 创建 IMediaPlayer 对象。
        sp<IMediaPlayer> player(service->create(this, mAudioSessionId));
        if ((NO_ERROR != doSetRetransmitEndpoint(player)) ||
            (NO_ERROR != player->setDataSource(source))) {
            player.clear();
        }
        err = attachNewPlayer(player);
    }
    return err;
}
```

这里首先获取了 `IMediaPlayerService` 对象，它表示 native 层的 `MediaPlayerService` 服务，然后通过它的 `create` 函数创建了一个 `IMediaPlayer` 的对象实例，它表示 native 层的 `MediaPlayer` 媒体播放器的实现。

首先看 `getMediaPlayerService()` 函数，在 `MediaPlayer` 类的父类 `IMediaDeathNotifier.cpp` 文件中：

```c++
// IMediaDeathNotifier.cpp

/*static*/const sp<IMediaPlayerService>& IMediaDeathNotifier::getMediaPlayerService()
{
    ALOGV("getMediaPlayerService");
    Mutex::Autolock _l(sServiceLock);
    if (sMediaPlayerService == 0) {
        // 获取 ServiceManager 的引用。
        sp<IServiceManager> sm = defaultServiceManager();
        sp<IBinder> binder;
        do {
            // 使用 getService 函数获得 MediaPlayerService 服务的引用。
            binder = sm->getService(String16("media.player"));
            if (binder != 0) {
                break;
            }
            ALOGW("Media player service not published, waiting...");
            // 未获取到，等待 0.5 秒后在再次获取。
            usleep(500000); // 0.5 s
        } while (true);

        if (sDeathNotifier == NULL) {
            sDeathNotifier = new DeathNotifier();
        }
        binder->linkToDeath(sDeathNotifier);
        // 注意这里又使用了 interface_cast 这个模板函数。
        sMediaPlayerService = interface_cast<IMediaPlayerService>(binder);
    }
    ALOGE_IF(sMediaPlayerService == 0, "no media player service!?");
    return sMediaPlayerService;
}
```

使用 ServiceManager 的`getService` 函数将获得 `MediaPlayerService` 的 Binder 引用号，前面分析过 `interface_cast` 这个模板函数将有如下作用：

```c++
interface_cast<IMediaPlayerService>(binder);
```

最终可转化为：

```c++
new BpMediaPlayerService(new BpBinder(binder));
```

那么回到上面：

```c++
const sp<IMediaPlayerService>& service(getMediaPlayerService());
```

这里 `service` 换成 `BpMediaPlayerSevice` 的对象，继续看下一句：

```c++
sp<IMediaPlayer> player(service->create(this, mAudioSessionId));
```

追溯 `service` 的 `create` 函数，在 `BpMediaPlayerSevice` 类中，它在 `IServiceManager.cpp` 文件中。

## BpMediaPlayerService

 ```c++
// BpMediaPlayerService.cpp

class BpMediaPlayerService: public BpInterface<IMediaPlayerService>
{
public:
    BpMediaPlayerService(const sp<IBinder>& impl)
        : BpInterface<IMediaPlayerService>(impl) {}
    ...
    virtual sp<IMediaPlayer> create(
            const sp<IMediaPlayerClient>& client, int audioSessionId) {
        Parcel data, reply;
        data.writeInterfaceToken(IMediaPlayerService::getInterfaceDescriptor());
        data.writeStrongBinder(IInterface::asBinder(client));
        data.writeInt32(audioSessionId);

		// 通过 Binder 驱动向服务端发送消息。
        remote()->transact(CREATE, data, &reply);
        return interface_cast<IMediaPlayer>(reply.readStrongBinder());
    }
    ...
};
 ```

通过前面 Service Binder 的注册过程了解到，`remote()` 是 `BpBinder` 对象，然后它内部会通过对 Binder 服务端的引用号通过驱动向服务端 Binder 发送消息，这里这个 `remote()` 含有 `MediaPlayerService` 服务的引用号，前面的文档分析过最终接收者为 `BBinder` 类型，它表示服务端 Binder，这里就其实就是 `MediaPlayerService` 对象，那么最终驱动将会把消息传递给 `MediaPlayerService` 服务。

## IMediaPlayerService

`IMediaPlayerService` 类型负责定义客户端与服务端双方沟通的接口：

```c++
// IMediaPlayerService.h

class IMediaPlayerService: public IInterface
{
public:
    DECLARE_META_INTERFACE(MediaPlayerService);

    virtual sp<IMediaRecorder> createMediaRecorder(const String16 &opPackageName) = 0;
    virtual sp<IMediaMetadataRetriever> createMetadataRetriever() = 0;
    virtual sp<IMediaPlayer> create(const sp<IMediaPlayerClient>& client, int audioSessionId = 0)
            = 0;

    virtual sp<IOMX>            getOMX() = 0;
    virtual sp<ICrypto>         makeCrypto() = 0;
    virtual sp<IDrm>            makeDrm() = 0;
    virtual sp<IHDCP>           makeHDCP(bool createEncryptionModule) = 0;
    virtual sp<IMediaCodecList> getCodecList() const = 0;

    // Connects to a remote display.
    // 'iface' specifies the address of the local interface on which to listen for
    // a connection from the remote display as an ip address and port number
    // of the form "x.x.x.x:y".  The media server should call back into the provided remote
    // display client when display connection, disconnection or errors occur.
    // The assumption is that at most one remote display will be connected to the
    // provided interface at a time.
    virtual sp<IRemoteDisplay> listenForRemoteDisplay(const String16 &opPackageName,
            const sp<IRemoteDisplayClient>& client, const String8& iface) = 0;

    // codecs and audio devices usage tracking for the battery app
    enum BatteryDataBits {
        // tracking audio codec
        kBatteryDataTrackAudio          = 0x1,
        // tracking video codec
        kBatteryDataTrackVideo          = 0x2,
        // codec is started, otherwise codec is paused
        kBatteryDataCodecStarted        = 0x4,
        // tracking decoder (for media player),
        // otherwise tracking encoder (for media recorder)
        kBatteryDataTrackDecoder        = 0x8,
        // start to play an audio on an audio device
        kBatteryDataAudioFlingerStart   = 0x10,
        // stop/pause the audio playback
        kBatteryDataAudioFlingerStop    = 0x20,
        // audio is rounted to speaker
        kBatteryDataSpeakerOn           = 0x40,
        // audio is rounted to devices other than speaker
        kBatteryDataOtherAudioDeviceOn  = 0x80,
    };

    virtual void addBatteryData(uint32_t params) = 0;
    virtual status_t pullBatteryData(Parcel* reply) = 0;
};

```

## MediaPlayerService

下面看 `MediaPlayerService` 的类定义，在 `MediaPlayerService.h` 头文件中：

```c++
// MediaPlayerService.h

class MediaPlayerService : public BnMediaPlayerService
{
    ...
}
```

它实现了一个 `BnMediaPlayerService` 类型，`BnMediaPlayerService` 从名字上看起来和上面的 `BpMediaPlayerService` 有一个对应关系，它的定义在 `IMediaPlayerService,h` 文件中：

## BnMediaPlayerService

```c++
// IMediaPlayerService.h

class BnMediaPlayerService: public BnInterface<IMediaPlayerService>
{
public:
    virtual status_t onTransact( uint32_t code,
                                    const Parcel& data,
                                    Parcel* reply,
                                    uint32_t flags = 0);
};
```

继续看它的父类 `BnInterface<IMediaPlayerService>`，在 `IInterface.h` 中。

```c++
// IInterface.h

template<typename INTERFACE>
class BnInterface : public INTERFACE, public BBinder
{
public:
    virtual sp<IInterface>      queryLocalInterface(const String16& _descriptor);
    virtual const String16&     getInterfaceDescriptor() const;

protected:
    virtual IBinder*            onAsBinder();
};

template<typename INTERFACE>
inline sp<IInterface> BnInterface<INTERFACE>::queryLocalInterface(
        const String16& _descriptor)
{
    if (_descriptor == INTERFACE::descriptor) return this;
    return NULL;
}

template<typename INTERFACE>
inline const String16& BnInterface<INTERFACE>::getInterfaceDescriptor() const
{
    return INTERFACE::getInterfaceDescriptor();
}

template<typename INTERFACE>
IBinder* BnInterface<INTERFACE>::onAsBinder()
{
    return this;
}
```

也是一个模板类，和 `BpInterface` 类似，替换 IMediaPlayerService 后得到：	

```c++
class BnInterface : public IMediaPlayerService, public BBinder
{
public:
    virtual sp<IInterface>      queryLocalInterface(const String16& _descriptor);
    virtual const String16&     getInterfaceDescriptor() const;

protected:
    virtual IBinder*            onAsBinder();
};

inline sp<IInterface> BnInterface<IMediaPlayerService>::queryLocalInterface(
        const String16& _descriptor)
{
    if (_descriptor == IMediaPlayerService::descriptor) return this;
    return NULL;
}

inline const String16& BnInterface<IMediaPlayerService>::getInterfaceDescriptor() const
{
    return IMediaPlayerService::getInterfaceDescriptor();
}

IBinder* BnInterface<IMediaPlayerService>::onAsBinder()
{
    return this;
}
```

`BnMediaPlayerService` 的 `onTransact` 将会收到客户端请求的消息并处理：

```c++
// IMediaPlayerService.cpp

status_t BnMediaPlayerService::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch (code) {
        case CREATE: {
            CHECK_INTERFACE(IMediaPlayerService, data, reply);
            sp<IMediaPlayerClient> client =
                interface_cast<IMediaPlayerClient>(data.readStrongBinder());
            int audioSessionId = data.readInt32();
            sp<IMediaPlayer> player = create(client, audioSessionId);
            reply->writeStrongBinder(IInterface::asBinder(player));
            return NO_ERROR;
        } break;
        case CREATE_MEDIA_RECORDER: {
            CHECK_INTERFACE(IMediaPlayerService, data, reply);
            const String16 opPackageName = data.readString16();
            sp<IMediaRecorder> recorder = createMediaRecorder(opPackageName);
            reply->writeStrongBinder(IInterface::asBinder(recorder));
            return NO_ERROR;
        } break;
        ...
        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
}
```

前面 `BpMediaPlayerService` 的 `create` 函数向服务端请求了 `CREATE` 编号，对应这里的 `CREATE` 编号。

这里使用了 `create` 函数返回了一个 `IMediaPlayer` 的对象。

`IMediaPlayer` 也是一个服务的进程间交互接口的定义。

```c++
class IMediaPlayer: public IInterface
{
    ...
}
```

```c++
sp<IMediaPlayer> MediaPlayerService::create(const sp<IMediaPlayerClient>& client,
        int audioSessionId)
{
    pid_t pid = IPCThreadState::self()->getCallingPid();
    int32_t connId = android_atomic_inc(&mNextConnId);

    sp<Client> c = new Client(
            this, pid, connId, client, audioSessionId,
            IPCThreadState::self()->getCallingUid());

    ALOGV("Create new client(%d) from pid %d, uid %d, ", connId, pid,
         IPCThreadState::self()->getCallingUid());

    wp<Client> w = c;
    {
        Mutex::Autolock lock(mLock);
        mClients.add(w);
    }
    return c;
}
```

可以看到上面创建了一个 `Client` 对象。

```c++
class Client : public BnMediaPlayer {
    // IMediaPlayer interface
    virtual void            disconnect();
    virtual status_t        setVideoSurfaceTexture(
        const sp<IGraphicBufferProducer>& bufferProducer);
    virtual status_t        prepareAsync();
    virtual status_t        start();
    virtual status_t        stop();
    virtual status_t        pause();
    virtual status_t        isPlaying(bool* state);
    ... 省略
}; // Client
```

原来这个 `Client` 也是一个服务端 Binder 对象，从命名上可以看到，它就是 `MediaPlayer` 的最终服务实现类。

回到上一级，使用 `writeStorngBinder` 向返回数据包写入了一个 `IInterface::asBinder(player)` 对象。

```c++
// IInterface.h

sp<IBinder> IInterface::asBinder(const sp<IInterface>& iface)
{
    if (iface == NULL) return NULL;
    return iface->onAsBinder();
}
```

只要是实现了 `BnInterface` 的类，它们的 `onAsBinder` 函数都是相同的实现，返回自己：

```
// IInterface.h

template<typename INTERFACE>
IBinder* BnInterface<INTERFACE>::onAsBinder()
{
    return this;
}
```

那么看 `writeStorngBinder` 做了什么，它在数据包的 `Parcel` 类型中。

## Parcel

```c++
// Parcel.cpp

status_t Parcel::writeStrongBinder(const sp<IBinder>& val)
{
    return flatten_binder(ProcessState::self(), val, this);
}
```

```c++
// Parcel.cpp

status_t flatten_binder(const sp<ProcessState>& /*proc*/,
    const sp<IBinder>& binder, Parcel* out)
{
    flat_binder_object obj;

    obj.flags = 0x7f | FLAT_BINDER_FLAG_ACCEPTS_FDS;
    if (binder != NULL) {
        IBinder *local = binder->localBinder();
        if (!local) {
            // 处理 BpBinder 类型，客户端 Binder。
            BpBinder *proxy = binder->remoteBinder();
            if (proxy == NULL) {
                ALOGE("null proxy");
            }
            const int32_t handle = proxy ? proxy->handle() : 0;
            obj.type = BINDER_TYPE_HANDLE;
            obj.binder = 0; /* Don't pass uninitialized stack data to a remote process */
            obj.handle = handle;
            obj.cookie = 0;
        } else {
            // 处理 BBinder 类型，服务端 Binder。
            obj.type = BINDER_TYPE_BINDER;
            obj.binder = reinterpret_cast<uintptr_t>(local->getWeakRefs());
            obj.cookie = reinterpret_cast<uintptr_t>(local);
        }
    } else {
        obj.type = BINDER_TYPE_BINDER;
        obj.binder = 0;
        obj.cookie = 0;
    }

    return finish_flatten_binder(binder, obj, out);
}
```

前门的 Binder 设计部分提到过，Binder 是通过 `flat_binder_object` 结构在进程间进程传输的，这里构造了一个 `flat_binder_object` 对象，上面通过 `localBinder` 判断构造不同的结构。

前面写入的是一个 `BnMediaPlayer` 对象，即 `BBinder` 类型，它的实现如下：

```c++
// Parcel.cpp

BBinder* BBinder::localBinder()
{
    return this;
}
```

那么走下面的分支，将本地 Binder 对象 `local` 的指针保存在 `obj.cookie` 里，`obj.type` 设置为 `BINDER_TYPE_BINDER`，当驱动接收到此类型，将会去除对应的 Binder 引用号，返回给客户端，返回给客户端的 `obj.type` 将被自动转化为 `BINDER_TYPE_HANDLE` 或 `BINDER_TYPE_WEAK_HANDLE`。

最后的 `finish_flat_binder` 将 `flat_binder_object` 结构保存至缓冲区：

```c++
// Parcel.cpp

inline static status_t finish_flatten_binder(
    const sp<IBinder>& /*binder*/, const flat_binder_object& flat, Parcel* out)
{
    return out->writeObject(flat, false);
}
```

至此 `MediaPlayerService` 完成了它 `create` 一个 `MediaPlayerClient` 的工作，此时，一个 `Client` 对象，即 `BnMediaPlayer` 服务端 Binder 对象将通过 Binder 驱动被发送到客户端，客户端会收到服务端 Binder 的引用号，可以使用它来向服务端 Binder 发起请求。

# todo 😭


