# Android Provider 实现分析

## 前言

ContentProvider 是 Android 系统的四大组件之一，它提供了一种共享数据的机制，同时也是一种进程间通信的方式，下面基于 Android 6.0.1 系统源码分析 Provider 组件的工作及实现原理，分为两部分，Provider 的查询和 Provider 的安装。

## 使用方式

一般的开发工作很少用到 ContentProvider，下面说一下它的用法。

### 实现数据提供者

首先需要实现自定义的数据提供者，需要实现增删查改的数据操作方法，还可实现自定义操作的 `call` 方法：

```java
public class ExampleContentProvider extends ContentProvider {

  @Override public boolean onCreate() {
    return false;
  }

  @Override
  public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }

  @Override public String getType(Uri uri) { return null; }

  @Override public Uri insert(Uri uri, ContentValues values) { return null; }

  @Override
  public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

  @Override
  public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
```

### 注册数据提供者

实现数据提供者后就可以在清单文件中注册了，注册时需要指定 Uri，当其他进程需要访问时，使用 Uri 即可获得 ContentProvider 的远程代理，然后使用代理即可向 ContentProvider 发送数据操作请求。

```xml
<application>
	<provider
        android:name="ExampleContentProvider"
        android:authorities="l0neman.provider.example" />
</application>
```

### 访问数据提供者

上面的工作完成后就可以向数据提供者发送请求了，使用 `content://` 前缀加 Uri 即可，还可在后面加数据访问路径：

```java
Bundle result = contenxt.getContentResolver().call(
  Uri.parse("content://l0neman.provider.example/path"), "method", null, null);
```

下面从对 ContentProvider 的 call 调用开始分析 Provider 的实现。

## Provider 的查询

首先从获得 ContentProvider 开始分析，即 `content.getCOntentResolver()`。

### Client

#### ContextImpl

```java
private final ApplicationContentResolver mContentResolver;
...

public ContextImpl() {
    ...
    mContentResolver = new ApplicationContentResolver(this, mainThread, user);
}

@Override
public ContentResolver getContentResolver() {
    return mContentResolver;
}
```

它由 `ApplicationContentProvider` 类实现。

```java
private static final class ApplicationContentResolver extends ContentResolver {
  ...
}
```

call 方法的实现在父类 `ContentProvider` 中。

#### ContentProvider

```java
// ContentProvider.java

public final @Nullable Bundle call(@NonNull Uri uri, @NonNull String method,
        @Nullable String arg, @Nullable Bundle extras) {
    Preconditions.checkNotNull(uri, "uri");
    Preconditions.checkNotNull(method, "method");
    // 首先获得 provider 引用。
    IContentProvider provider = acquireProvider(uri);
    if (provider == null) {
        throw new IllegalArgumentException("Unknown URI " + uri);
    }
    try {
        // 发起 call 请求。
        return provider.call(mPackageName, method, arg, extras);
    } catch (RemoteException e) {
        // 随意且不值得记录，因为活动管理器无论如何都会很快终止这个过程。
        return null;
    } finally {
        releaseProvider(provider);
    }
}
```

上面首先使用 `acquireProvider` 方法获取 provider 句柄，然后再调用 call，`acquireProvider` 是一个抽象方法，实现者是 `ApplicationContentResolver`。

#### ApplicationContentResolver

```java
// ApplicationContentResolver.java

@Override
protected IContentProvider acquireProvider(Context context, String auth) {
    return mMainThread.acquireProvider(context,
            ContentProvider.getAuthorityWithoutUserId(auth),
            resolveUserIdFromAuthority(auth), true);
}
```

`mMainThread` 为 `ActivityThread`，它在构造器中被传入。

#### ActivityThread

```java
// ActivityThread.java

public final IContentProvider acquireProvider(
        Context c, String auth, int userId, boolean stable) {
    // 获得已存在的 provider。
    final IContentProvider provider = acquireExistingProvider(c, auth, userId, stable);
    if (provider != null) {
        return provider;
    }

    // 这里可能发生竞争。另一个线程可能会尝试获取同一个 provider。
    // 当这种情况发生时，我们希望确保第一个获胜。
    // 请注意，我们无法在获取和安装提供程序时保持锁定，因为它可能需要很
    // 长时间才能运行，并且在提供程序处于同一进程的情况下也可能是重入的。
    IActivityManager.ContentProviderHolder holder = null;
    try {
        holder = ActivityManagerNative.getDefault().getContentProvider(
                getApplicationThread(), auth, userId, stable);
    } catch (RemoteException ex) {
    }
    if (holder == null) {
        Slog.e(TAG, "Failed to find provider info for " + auth);
        return null;
    }

    // 安装 provider 会增加我们的引用计数，并打破竞赛中的任何关系。
    holder = installProvider(c, holder, holder.info,
            true /*noisy*/, holder.noReleaseNeeded, stable);
    return holder.provider;
}
```

上面首先获取已存在的 provider，如果有则直接返回，否则向 AMS 请求获取 ContentProvider。

```java
// ActivityThread.java

public final IContentProvider acquireExistingProvider(
        Context c, String auth, int userId, boolean stable) {
    synchronized (mProviderMap) {
        final ProviderKey key = new ProviderKey(auth, userId);
        // 查询 provider 客户端记录。
        final ProviderClientRecord pr = mProviderMap.get(key);
        if (pr == null) {
            return null;
        }

        IContentProvider provider = pr.mProvider;
        IBinder jBinder = provider.asBinder();
        if (!jBinder.isBinderAlive()) {
            // provider 的宿主进程已经死亡；我们不能使用这个。
            Log.i(TAG, "Acquiring provider " + auth + " for user " + userId
                    + ": existing object's process dead");
            handleUnstableProviderDiedLocked(jBinder, true);
            return null;
        }

        // 如果我们只有一个，那么只增加引用计数。如果我们不这样做，
        // 那么 provider 不会被引用计数，也不需要被释放。
        ProviderRefCount prc = mProviderRefCountMap.get(jBinder);
        if (prc != null) {
            // 增加引用计数。
            incProviderRefLocked(prc, stable);
        }
        return provider;
    }
}
```

这里从客户端缓存的 `mProviderMap` 中查询已存在的 provider，回到上面。

下面将通过进程间通信向 AMS 发出 `getContentProvider` 请求。

#### ActivityManagerProxy

```java
// ActivityManagerProxy.java

public ContentProviderHolder getContentProvider(IApplicationThread caller,
        String name, int userId, boolean stable) throws RemoteException {
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    data.writeInterfaceToken(IActivityManager.descriptor);
    data.writeStrongBinder(caller != null ? caller.asBinder() : null);
    data.writeString(name);
    data.writeInt(userId);
    data.writeInt(stable ? 1 : 0);
    mRemote.transact(GET_CONTENT_PROVIDER_TRANSACTION, data, reply, 0);
    reply.readException();
    int res = reply.readInt();
    ContentProviderHolder cph = null;
    if (res != 0) {
        cph = ContentProviderHolder.CREATOR.createFromParcel(reply);
    }
    data.recycle();
    reply.recycle();
    return cph;
}
```

####ActivityManagerNative

```java
// ActivityManagerNative.java
@Override
public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
        throws RemoteException {
    switch (code) {
    ...
    case GET_CONTENT_PROVIDER_TRANSACTION: {
        data.enforceInterface(IActivityManager.descriptor);
        IBinder b = data.readStrongBinder();
        IApplicationThread app = ApplicationThreadNative.asInterface(b);
        String name = data.readString();
        int userId = data.readInt();
        boolean stable = data.readInt() != 0;
        ContentProviderHolder cph = getContentProvider(app, name, userId, stable);
        reply.writeNoException();
        if (cph != null) {
            reply.writeInt(1);
            cph.writeToParcel(reply, 0);
        } else {
            reply.writeInt(0);
        }
        return true;
    }
    ...
}
```

#### ActivityManagerService

```java
// ActivityManagerService.java

@Override
public final ContentProviderHolder getContentProvider(
        IApplicationThread caller, String name, int userId, boolean stable) {
    enforceNotIsolatedCaller("getContentProvider");
    if (caller == null) {
        String msg = "null IApplicationThread when getting content provider "
                + name;
        Slog.w(TAG, msg);
        throw new SecurityException(msg);
    }
    // The incoming user check is now handled in checkContentProviderPermissionLocked() to deal
    // with cross-user grant.
    return getContentProviderImpl(caller, name, null, stable, userId);
}
```



## Provider 的安装



