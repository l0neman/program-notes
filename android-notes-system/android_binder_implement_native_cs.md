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

通过前面 Service Binder 的注册过程了解到，`remote()` 是 `BpBinder` 对象，然后它内部会通过对 Binder 服务端的引用号通过驱动向服务端 Binder 发送消息，这里这个 `remote()` 含有 `MediaPlayerService` 服务的引用号，前面的文档分析过最终接收者为 `BBinder` 类型，它表示服务端 Binder，这里就其实就是 `MediaPlayerService` 对象，那么最终驱动将会把消息传递给 `MediaPlayerService` 服务，首先看它的类定义，在 `MediaPlayerService.h` 头文件中：

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
// IMediaPlayerServic.h

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
```

也是一个模板类，和 `BpInterface` 类似，替换 `IMediaPlayerService` 后得到。

```c++
class BnInterface : public IMediaPlayerService, public BBinder
{
public:
    virtual sp<IInterface>      queryLocalInterface(const String16& _descriptor);
    virtual const String16&     getInterfaceDescriptor() const;

protected:
    virtual IBinder*            onAsBinder();
};
```

 

# todo 补充分析（哈哈哈😄）。