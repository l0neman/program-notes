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

/*static*/const sp<IMediaPlayerService>&
IMediaDeathNotifier::getMediaPlayerService()
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
        // 注意这里又使用了 interface_cast 这个宏。
        sMediaPlayerService = interface_cast<IMediaPlayerService>(binder);
    }
    ALOGE_IF(sMediaPlayerService == 0, "no media player service!?");
    return sMediaPlayerService;
}
```



