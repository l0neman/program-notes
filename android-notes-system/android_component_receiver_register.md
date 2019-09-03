# Android Receiver 注册流程分析

## 前言

广播是 Android 系统的四大组件之一，是进程间通信的的一种方式，下面基于 Android 6.0.1 系统源码分析广播的注册流程。

## 注册方式

### 静态注册

Receiver 可通过两种方式注册，可在清单文件中直接进行静态注册：

```java
// 实现广播接收器。

public static final class ExampleReceiver implements BroadcastReceiver {
    
    private static final String EXAMPLE_ACTION = "static.example.receiver.action"
    
    @Override
    public void onReceive(Context context, Intent intent) {
        // ...
    }
}
```

```xml
<!-- AndroidManifest.xml -->
<!-- 清单文件静态注册 -->
...

<application ...>
	<receiver android:name="com.example.ExampleReceiver">
    	<intent-filter>
        	<action android:name="static.example.receiver.action"/>
        </intent-filter>
    </receiver>
</application>
```

### 动态注册

可在代码逻辑中，使用 Context 对象动态注册广播接收器：

```java
// XXXActivity.java

@Override
public void onCreate(Bundle saveInstanceState) {
    ...
    IntentFilter filter = new IntentFilter();
    filter.addAction("static.example.receiver.action");
    registerReceiver(new ExampleReceiver(), filter);
}
```

## 注册分析

静态注册的广播接收者将在发送广播时由系统查询并激活，这里从动态注册的方式开始分析。

首先是 Context 的 `registerReceiver`，Context 的实现类为 `ContextImpl`：

### Client

#### ContextImpl

```java
// ContextImpl.java

@Override
public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
    return registerReceiver(receiver, filter, null, null);
}

@Override
public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
        String broadcastPermission, Handler scheduler) {
    return registerReceiverInternal(receiver, getUserId(),
            filter, broadcastPermission, scheduler, getOuterContext());
}

@Override
public Intent registerReceiverAsUser(BroadcastReceiver receiver, UserHandle user,
        IntentFilter filter, String broadcastPermission, Handler scheduler) {
    return registerReceiverInternal(receiver, user.getIdentifier(),
            filter, broadcastPermission, scheduler, getOuterContext());
}

private Intent registerReceiverInternal(BroadcastReceiver receiver, int userId,
        IntentFilter filter, String broadcastPermission,
        Handler scheduler, Context context) {
    IIntentReceiver rd = null;
    if (receiver != null) {
        if (mPackageInfo != null && context != null) {
            if (scheduler == null) {
                // scheduler 为 ActivityThread 的 H 类。
                scheduler = mMainThread.getHandler();
            }
            // 获得广播分发器。
            rd = mPackageInfo.getReceiverDispatcher(
                receiver, context, scheduler,
                mMainThread.getInstrumentation(), true);
        } else {
            if (scheduler == null) {
                scheduler = mMainThread.getHandler();
            }
            rd = new LoadedApk.ReceiverDispatcher(
                    receiver, context, scheduler, null, true).getIIntentReceiver();
        }
    }
    try {
        // 请求 AMS 注册广播接收者。
        return ActivityManagerNative.getDefault().registerReceiver(
                mMainThread.getApplicationThread(), mBasePackageName,
                rd, filter, broadcastPermission, userId);
    } catch (RemoteException e) {
        return null;
    }
}
```

首先看一下 `IIntentReceiver` 是怎样获取的：

#### LoadedApk

```java
// LoadedApk.java

public IIntentReceiver getReceiverDispatcher(BroadcastReceiver r,
        Context context, Handler handler,
        Instrumentation instrumentation, boolean registered) {
    synchronized (mReceivers) {
        LoadedApk.ReceiverDispatcher rd = null;
        // 广播和分发器对象的映射。
        ArrayMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher> map = null;
        if (registered) {
            // mReceivers 是以 Context 为 Key 到上面 Map 的映射。
            // 表示这个 Context 相关的广播接收器以及对应的分发对象。
            map = mReceivers.get(context);
            if (map != null) {
                rd = map.get(r);
            }
        }
        if (rd == null) {
            rd = new ReceiverDispatcher(r, context, handler,
                    instrumentation, registered);
            if (registered) {
                if (map == null) {
                    map = new ArrayMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher>();
                    mReceivers.put(context, map);
                }
                map.put(r, rd);
            }
        } else {
            rd.validate(context, handler);
        }
        rd.mForgotten = false;
        return rd.getIIntentReceiver();
    }
}

```

#### ReceiverDispatcher

```java
// LoadedApk.java - class ReceiverDispatcher

static final class ReceiverDispatcher {

    final static class InnerReceiver extends IIntentReceiver.Stub {
        final WeakReference<LoadedApk.ReceiverDispatcher> mDispatcher;
        final LoadedApk.ReceiverDispatcher mStrongRef;

        InnerReceiver(LoadedApk.ReceiverDispatcher rd, boolean strong) {
            // 持有分发器的弱引用。
            mDispatcher = new WeakReference<LoadedApk.ReceiverDispatcher>(rd);
            mStrongRef = strong ? rd : null;
        }
        public void performReceive(Intent intent, int resultCode, String data,
                Bundle extras, boolean ordered, boolean sticky, int sendingUser) {
            LoadedApk.ReceiverDispatcher rd = mDispatcher.get();
            ...
            // 通知 BroadcastReceiver 的 onReceive 方法。
        }
    }

    final IIntentReceiver.Stub mIIntentReceiver;
    final BroadcastReceiver mReceiver;
    final Context mContext;
    final Handler mActivityThread;
    final Instrumentation mInstrumentation;
    final boolean mRegistered;
    final IntentReceiverLeaked mLocation;
    RuntimeException mUnregisterLocation;
    boolean mForgotten;

    ...
    
    ReceiverDispatcher(BroadcastReceiver receiver, Context context,
            Handler activityThread, Instrumentation instrumentation,
            boolean registered) {
        if (activityThread == null) {
            throw new NullPointerException("Handler must not be null");
        }

        mIIntentReceiver = new InnerReceiver(this, !registered);
        mReceiver = receiver;
        mContext = context;
        mActivityThread = activityThread;
        mInstrumentation = instrumentation;
        mRegistered = registered;
        mLocation = new IntentReceiverLeaked(null);
        mLocation.fillInStackTrace();
    }
  
    IIntentReceiver getIIntentReceiver() {
        return mIIntentReceiver;
    }
    ...
}
```

可以看到，返回了一个 `InnerReceiver` 内部类的对象，它持有广播分发器的弱引用，同时它是以个 Binder 的服务端（实现了 aidl 协议中的 Stub 端），负责在合适的时候通知最终的 BroadcastReceiver。

回到上面，获得广播分发器后，下面开始请求 ActivityManagerService 注册广播。

#### ActivityManagerProxy

```java
// ActivityManagerProxy.java

public Intent registerReceiver(IApplicationThread caller, String packageName,
        IIntentReceiver receiver,
        IntentFilter filter, String perm, int userId) throws RemoteException
{
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    data.writeInterfaceToken(IActivityManager.descriptor);
    data.writeStrongBinder(caller != null ? caller.asBinder() : null);
    data.writeString(packageName);
    data.writeStrongBinder(receiver != null ? receiver.asBinder() : null);
    filter.writeToParcel(data, 0);
    data.writeString(perm);
    data.writeInt(userId);
    mRemote.transact(REGISTER_RECEIVER_TRANSACTION, data, reply, 0);
    reply.readException();
    Intent intent = null;
    int haveIntent = reply.readInt();
    if (haveIntent != 0) {
        intent = Intent.CREATOR.createFromParcel(reply);
    }
    reply.recycle();
    data.recycle();
    return intent;
}
```

### Server

#### ActivityManagerNative

```java
@Override
public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
        throws RemoteException {
    switch (code) {
    ...
    case REGISTER_RECEIVER_TRANSACTION:
    {
        data.enforceInterface(IActivityManager.descriptor);
        IBinder b = data.readStrongBinder();
        IApplicationThread app =
            b != null ? ApplicationThreadNative.asInterface(b) : null;
        String packageName = data.readString();
        b = data.readStrongBinder();
        IIntentReceiver rec
            = b != null ? IIntentReceiver.Stub.asInterface(b) : null;
        IntentFilter filter = IntentFilter.CREATOR.createFromParcel(data);
        String perm = data.readString();
        int userId = data.readInt();
        Intent intent = registerReceiver(app, packageName, rec, filter, perm, userId);
        reply.writeNoException();
        if (intent != null) {
            reply.writeInt(1);
            intent.writeToParcel(reply, 0);
        } else {
            reply.writeInt(0);
        }
        return true;
    }
    ...
}
```

### ActivityManagerService

```java
// ActivityManagerService.java

public Intent registerReceiver(IApplicationThread caller, String callerPackage,
        IIntentReceiver receiver, IntentFilter filter, String permission, int userId) {
    enforceNotIsolatedCaller("registerReceiver");
    ArrayList<Intent> stickyIntents = null;
    ProcessRecord callerApp = null;
    int callingUid;
    int callingPid;
    synchronized(this) {
        if (caller != null) {
            // 获得调用者进程记录。
            callerApp = getRecordForAppLocked(caller);
            if (callerApp == null) {
                throw new SecurityException(
                        "Unable to find app for caller " + caller
                        + " (pid=" + Binder.getCallingPid()
                        + ") when registering receiver " + receiver);
            }
            if (callerApp.info.uid != Process.SYSTEM_UID &&
                    !callerApp.pkgList.containsKey(callerPackage) &&
                    !"android".equals(callerPackage)) {
                throw new SecurityException("Given caller package " + callerPackage
                        + " is not running in process " + callerApp);
            }
            callingUid = callerApp.info.uid;
            callingPid = callerApp.pid;
        } else {
            callerPackage = null;
            callingUid = Binder.getCallingUid();
            callingPid = Binder.getCallingPid();
        }

        userId = handleIncomingUser(callingPid, callingUid, userId,
                true, ALLOW_FULL_ONLY, "registerReceiver", callerPackage);

        Iterator<String> actions = filter.actionsIterator();
        if (actions == null) {
            ArrayList<String> noAction = new ArrayList<String>(1);
            noAction.add(null);
            actions = noAction.iterator();
        }

        // 收集用户发出的粘性广播。
        int[] userIds = { UserHandle.USER_ALL, UserHandle.getUserId(callingUid) };
        while (actions.hasNext()) {
            String action = actions.next();
            for (int id : userIds) {
                ArrayMap<String, ArrayList<Intent>> stickies = mStickyBroadcasts.get(id);
                if (stickies != null) {
                    ArrayList<Intent> intents = stickies.get(action);
                    if (intents != null) {
                        if (stickyIntents == null) {
                            stickyIntents = new ArrayList<Intent>();
                        }
                        // 添加所有的粘性广播。
                        stickyIntents.addAll(intents);
                    }
                }
            }
        }
    }

    ArrayList<Intent> allSticky = null;
    if (stickyIntents != null) {
        final ContentResolver resolver = mContext.getContentResolver();
        // 寻找所有匹配的粘性广播……
        for (int i = 0, N = stickyIntents.size(); i < N; i++) {
            Intent intent = stickyIntents.get(i);
            // 如果 intent 的 scheme 属性中有 "content"，则需要访问需要在 ActivityThread
            // 中锁定 mProviderMap 的 Provider，并且还可能需要等待应用程序响应，因此我们无法
            // 在此处锁定 ActivityManagerService。
            if (filter.match(resolver, intent, true, TAG) >= 0) {
                if (allSticky == null) {
                    allSticky = new ArrayList<Intent>();
                }
                allSticky.add(intent);
            }
        }
    }

    // 列表中的第一个粘性广播的 Intent 将直接返回给客户端。
    Intent sticky = allSticky != null ? allSticky.get(0) : null;
    if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST, "Register receiver " + filter + ": " + sticky);
    if (receiver == null) {
        return sticky;
    }

    synchronized (this) {
        if (callerApp != null && (callerApp.thread == null
                || callerApp.thread.asBinder() != caller.asBinder())) {
            // 原始调用者已经死亡。
            return null;
        }
        // mRegisteredReceivers 保存已注册的 Receiver，已广播分发器为 Key。
        ReceiverList rl = mRegisteredReceivers.get(receiver.asBinder());
        if (rl == null) {
            // 如果没有注册过，则创建新的接收器队列。
            rl = new ReceiverList(this, callerApp, callingPid, callingUid,
                    userId, receiver);
            if (rl.app != null) {
                // rl.app.receivers 表示进程已注册的广播，加入进程已注册广播队列。
                rl.app.receivers.add(rl);
            } else {
                try {
                    // 死亡通知。
                    receiver.asBinder().linkToDeath(rl, 0);
                } catch (RemoteException e) {
                    return sticky;
                }
                rl.linkedToDeath = true;
            }
            // 加入已注册广播接收器。
            mRegisteredReceivers.put(receiver.asBinder(), rl);
        } else if (rl.uid != callingUid) {
            throw new IllegalArgumentException(
                    "Receiver requested to register for uid " + callingUid
                    + " was previously registered for uid " + rl.uid);
        } else if (rl.pid != callingPid) {
            throw new IllegalArgumentException(
                    "Receiver requested to register for pid " + callingPid
                    + " was previously registered for pid " + rl.pid);
        } else if (rl.userId != userId) {
            throw new IllegalArgumentException(
                    "Receiver requested to register for user " + userId
                    + " was previously registered for user " + rl.userId);
        }
        BroadcastFilter bf = new BroadcastFilter(filter, rl, callerPackage,
                permission, callingUid, userId);
        // 加入广播对应的 Intent 过滤器。
        rl.add(bf);
        if (!bf.debugCheck()) {
            Slog.w(TAG, "==> For Dynamic broadcast");
        }
        mReceiverResolver.addFilter(bf);

        // Enqueue broadcasts for all existing stickies that match
        // this filter.
        if (allSticky != null) {
            ArrayList receivers = new ArrayList();
            receivers.add(bf);

            final int stickyCount = allSticky.size();
            for (int i = 0; i < stickyCount; i++) {
                Intent intent = allSticky.get(i);
                BroadcastQueue queue = broadcastQueueForIntent(intent);
                BroadcastRecord r = new BroadcastRecord(queue, intent, null,
                        null, -1, -1, null, null, AppOpsManager.OP_NONE, null, receivers,
                        null, 0, null, null, false, true, true, -1);
                queue.enqueueParallelBroadcastLocked(r);
                queue.scheduleBroadcastsLocked();
            }
        }

        return sticky;
    }
}
```

