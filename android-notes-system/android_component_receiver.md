# Android Receiver 实现原理分析

## 前言

广播是 Android 系统的四大组件之一，是进程间通信的的一种方式，下面基于 Android 6.0.1 系统源码分析广播的实现原理，实现分为两个部分，注册和发送，首先分析广播的注册。

## 广播注册方式

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

## 注册流程分析

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

#### ActivityManagerService

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
                // mStickyBroadcasts(SpareArray) 表示用户发出的粘性广播。
                ArrayMap<String, ArrayList<Intent>> stickies = mStickyBroadcasts.get(id);
                if (stickies != null) {
                    // 筛选出其中的粘性广播 Intent。
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
        // mReceiverResolver 表示广播的查询器，这里提前添加广播的过滤器。
        mReceiverResolver.addFilter(bf);

        // 将与此过滤器匹配的所有粘性广播加入队列。
        if (allSticky != null) {
            ArrayList receivers = new ArrayList();
            receivers.add(bf);

            final int stickyCount = allSticky.size();
            for (int i = 0; i < stickyCount; i++) {
                Intent intent = allSticky.get(i);
                BroadcastQueue queue = broadcastQueueForIntent(intent);
                // 创建广播记录。
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

看一下上面是如何匹配粘性广播的，`filter.match(...)`

#### IntentFilter

```java
// IntentFilter.java

public final int match(ContentResolver resolver, Intent intent,
        boolean resolve, String logTag) {
    String type = resolve ? intent.resolveType(resolver) : intent.getType();
    return match(intent.getAction(), type, intent.getScheme(),
                 intent.getData(), intent.getCategories(), logTag);
}

public final int match(String action, String type, String scheme,
        Uri data, Set<String> categories, String logTag) {
    // 1. 匹配 action。
    if (action != null && !matchAction(action)) {
        if (false) Log.v(
            logTag, "No matching action " + action + " for " + this);
        return NO_MATCH_ACTION;
    }

    // 2. 匹配 type, scheme, data。
    int dataMatch = matchData(type, scheme, data);
    if (dataMatch < 0) {
        if (false) {
            if (dataMatch == NO_MATCH_TYPE) {
                Log.v(logTag, "No matching type " + type
                      + " for " + this);
            }
            if (dataMatch == NO_MATCH_DATA) {
                Log.v(logTag, "No matching scheme/path " + data
                      + " for " + this);
            }
        }
        return dataMatch;
    }

    // 3. 匹配 category。
    String categoryMismatch = matchCategories(categories);
    if (categoryMismatch != null) {
        if (false) {
            Log.v(logTag, "No matching category " + categoryMismatch + " for " + this);
        }
        return NO_MATCH_CATEGORY;
    }
    
    return dataMatch;
}
```

匹配中的 action，type，scheme，data，category 都必须相同。

这里就完成了广播的注册，上面广播相关的数据结构这里使用伪代码表示如下（`{}` 表示类成员）：

```java
LoadedApk {
  // 表示这个 Context 相关的广播接收器以及对应的分发对象。
  ArrayMap<Context, ArrayMap<BroadcastReceiver, ReceiverDispatcher>> mReceivers;
}

ActivityManagerService {
  // 表示用户发出的粘性广播。
  SparseArray<ArrayMap<String, ArrayList<Intent>>> mStickyBroadcasts;
  // 保存已注册的 Receiver。
  HashMap<IBinder, ReceiverList> mRegisteredReceivers;
}

ProcessRecord {
  // 表示这个进程注册的所有 Receiver。
  ArraySet<ReceiverList> receivers = new ArraySet<>();
}
```

广播的注册过程使用时序图表示为：

## 时序图 todo

## 广播发送方式

### 无序广播

可并行发送，广播接收器接收到消息的顺序与发送时的顺序无关。

```java
context.sendBroadcast(new Intent("com.example.receiver.action"));
```

### 有序广播

广播接收器接收到的消息将遵循发送时的顺序。

```java
context.sendOrderedBroadcast(new Intent("com.example.receiver.action"), null);
```

### 粘性广播

广播发送后的 Intent 将被缓存起来，后续注册的接收器可持续接收（由安全性不能保证，在 API 21 已过时）。

```java
context.sendStickyBroadcast(new Intent("com.example.receiver.action"));
```

##  发送流程分析

下面开始分析广播的发送流程。

### Client

#### ContextImpl

```java
// ContextImpl.java

//  发送无序广播。
@Override
public void sendBroadcast(Intent intent) {
    warnIfCallingFromSystemProcess();
    String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
    try {
        intent.prepareToLeaveProcess();
        ActivityManagerNative.getDefault().broadcastIntent(
                mMainThread.getApplicationThread(), intent, resolvedType, null,
                Activity.RESULT_OK, null, null, null, AppOpsManager.OP_NONE, null, false, false,
                getUserId());
    } catch (RemoteException e) {
        throw new RuntimeException("Failure from system", e);
    }
}

// 发送有序广播。
@Override
public void sendOrderedBroadcast(Intent intent, String receiverPermission) {
    warnIfCallingFromSystemProcess();
    String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
    String[] receiverPermissions = receiverPermission == null ? null
            : new String[] {receiverPermission};
    try {
        intent.prepareToLeaveProcess();
        ActivityManagerNative.getDefault().broadcastIntent(
                mMainThread.getApplicationThread(), intent, resolvedType, null,
                Activity.RESULT_OK, null, null, receiverPermissions, AppOpsManager.OP_NONE,
                null, true, false, getUserId());
    } catch (RemoteException e) {
        throw new RuntimeException("Failure from system", e);
    }
}

// 发送粘性广播。
@Override
@Deprecated
public void sendStickyBroadcast(Intent intent) {
    warnIfCallingFromSystemProcess();
    String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
    try {
        intent.prepareToLeaveProcess();
        ActivityManagerNative.getDefault().broadcastIntent(
            mMainThread.getApplicationThread(), intent, resolvedType, null,
            Activity.RESULT_OK, null, null, null, AppOpsManager.OP_NONE, null, false, true,
            getUserId());
    } catch (RemoteException e) {
        throw new RuntimeException("Failure from system", e);
    }
}
```

可以看到三种广播的发送最终都是调用了 `broadcastIntent` 方法发送广播消息，广播属性由后面的两个 boolean 类型的参数决定。

#### ActivityManagerProxy

```java
// ActivityManagerNative.java - class ActivityManagerProxy

public int broadcastIntent(IApplicationThread caller,
        Intent intent, String resolvedType, IIntentReceiver resultTo,
        int resultCode, String resultData, Bundle map,
        String[] requiredPermissions, int appOp, Bundle options, boolean serialized,
        boolean sticky, int userId) throws RemoteException
{
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    data.writeInterfaceToken(IActivityManager.descriptor);
    data.writeStrongBinder(caller != null ? caller.asBinder() : null);
    intent.writeToParcel(data, 0);
    data.writeString(resolvedType);
    data.writeStrongBinder(resultTo != null ? resultTo.asBinder() : null);
    data.writeInt(resultCode);
    data.writeString(resultData);
    data.writeBundle(map);
    data.writeStringArray(requiredPermissions);
    data.writeInt(appOp);
    data.writeBundle(options);
    data.writeInt(serialized ? 1 : 0);
    data.writeInt(sticky ? 1 : 0);
    data.writeInt(userId);
    mRemote.transact(BROADCAST_INTENT_TRANSACTION, data, reply, 0);
    reply.readException();
    int res = reply.readInt();
    reply.recycle();
    data.recycle();
    return res;
}
```

广播类型与参数对应（`serialized` 表示连续，`sticky` 表示粘性）。

```java
无序广播 serialized = false; sticky = false;
有序广播 serialized = true;  sticky = false;
粘性广播 serialized = false; sticky = true;
```

#### ActivityManagerNative

```java
// ActivityManagerNative.java

@Override
public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
        throws RemoteException {
    switch (code) {
    ....
    case BROADCAST_INTENT_TRANSACTION:
    {
        data.enforceInterface(IActivityManager.descriptor);
        IBinder b = data.readStrongBinder();
        IApplicationThread app =
            b != null ? ApplicationThreadNative.asInterface(b) : null;
        Intent intent = Intent.CREATOR.createFromParcel(data);
        String resolvedType = data.readString();
        b = data.readStrongBinder();
        IIntentReceiver resultTo =
            b != null ? IIntentReceiver.Stub.asInterface(b) : null;
        int resultCode = data.readInt();
        String resultData = data.readString();
        Bundle resultExtras = data.readBundle();
        String[] perms = data.readStringArray();
        int appOp = data.readInt();
        Bundle options = data.readBundle();
        boolean serialized = data.readInt() != 0;
        boolean sticky = data.readInt() != 0;
        int userId = data.readInt();
        int res = broadcastIntent(app, intent, resolvedType, resultTo,
                resultCode, resultData, resultExtras, perms, appOp,
                options, serialized, sticky, userId);
        reply.writeNoException();
        reply.writeInt(res);
        return true;
    }
    ...
}
```

#### ActivityManagerService

```java
// ActivityManagerService.java

private final int broadcastIntentLocked(ProcessRecord callerApp,
        String callerPackage, Intent intent, String resolvedType,
        IIntentReceiver resultTo, int resultCode, String resultData,
        Bundle resultExtras, String[] requiredPermissions, int appOp, Bundle options,
        boolean ordered, boolean sticky, int callingPid, int callingUid, int userId) {
    intent = new Intent(intent);

    // By default broadcasts do not go to stopped apps.
    intent.addFlags(Intent.FLAG_EXCLUDE_STOPPED_PACKAGES);

    // If we have not finished booting, don't allow this to launch new processes.
    if (!mProcessesReady && (intent.getFlags()&Intent.FLAG_RECEIVER_BOOT_UPGRADE) == 0) {
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
    }

    if (DEBUG_BROADCAST_LIGHT) Slog.v(TAG_BROADCAST,
            (sticky ? "Broadcast sticky: ": "Broadcast: ") + intent
            + " ordered=" + ordered + " userid=" + userId);
    if ((resultTo != null) && !ordered) {
        Slog.w(TAG, "Broadcast " + intent + " not ordered but result callback requested!");
    }

    userId = handleIncomingUser(callingPid, callingUid, userId,
            true, ALLOW_NON_FULL, "broadcast", callerPackage);

    // Make sure that the user who is receiving this broadcast is running.
    // If not, we will just skip it. Make an exception for shutdown broadcasts
    // and upgrade steps.

    if (userId != UserHandle.USER_ALL && !isUserRunningLocked(userId, false)) {
        if ((callingUid != Process.SYSTEM_UID
                || (intent.getFlags() & Intent.FLAG_RECEIVER_BOOT_UPGRADE) == 0)
                && !Intent.ACTION_SHUTDOWN.equals(intent.getAction())) {
            Slog.w(TAG, "Skipping broadcast of " + intent
                    + ": user " + userId + " is stopped");
            return ActivityManager.BROADCAST_FAILED_USER_STOPPED;
        }
    }

    BroadcastOptions brOptions = null;
    if (options != null) {
        brOptions = new BroadcastOptions(options);
        if (brOptions.getTemporaryAppWhitelistDuration() > 0) {
            // See if the caller is allowed to do this.  Note we are checking against
            // the actual real caller (not whoever provided the operation as say a
            // PendingIntent), because that who is actually supplied the arguments.
            if (checkComponentPermission(
                    android.Manifest.permission.CHANGE_DEVICE_IDLE_TEMP_WHITELIST,
                    Binder.getCallingPid(), Binder.getCallingUid(), -1, true)
                    != PackageManager.PERMISSION_GRANTED) {
                String msg = "Permission Denial: " + intent.getAction()
                        + " broadcast from " + callerPackage + " (pid=" + callingPid
                        + ", uid=" + callingUid + ")"
                        + " requires "
                        + android.Manifest.permission.CHANGE_DEVICE_IDLE_TEMP_WHITELIST;
                Slog.w(TAG, msg);
                throw new SecurityException(msg);
            }
        }
    }

    /*
     * Prevent non-system code (defined here to be non-persistent
     * processes) from sending protected broadcasts.
     */
    int callingAppId = UserHandle.getAppId(callingUid);
    if (callingAppId == Process.SYSTEM_UID || callingAppId == Process.PHONE_UID
        || callingAppId == Process.SHELL_UID || callingAppId == Process.BLUETOOTH_UID
        || callingAppId == Process.NFC_UID || callingUid == 0) {
        // Always okay.
    } else if (callerApp == null || !callerApp.persistent) {
        try {
            if (AppGlobals.getPackageManager().isProtectedBroadcast(
                    intent.getAction())) {
                String msg = "Permission Denial: not allowed to send broadcast "
                        + intent.getAction() + " from pid="
                        + callingPid + ", uid=" + callingUid;
                Slog.w(TAG, msg);
                throw new SecurityException(msg);
            } else if (AppWidgetManager.ACTION_APPWIDGET_CONFIGURE.equals(intent.getAction())) {
                // Special case for compatibility: we don't want apps to send this,
                // but historically it has not been protected and apps may be using it
                // to poke their own app widget.  So, instead of making it protected,
                // just limit it to the caller.
                if (callerApp == null) {
                    String msg = "Permission Denial: not allowed to send broadcast "
                            + intent.getAction() + " from unknown caller.";
                    Slog.w(TAG, msg);
                    throw new SecurityException(msg);
                } else if (intent.getComponent() != null) {
                    // They are good enough to send to an explicit component...  verify
                    // it is being sent to the calling app.
                    if (!intent.getComponent().getPackageName().equals(
                            callerApp.info.packageName)) {
                        String msg = "Permission Denial: not allowed to send broadcast "
                                + intent.getAction() + " to "
                                + intent.getComponent().getPackageName() + " from "
                                + callerApp.info.packageName;
                        Slog.w(TAG, msg);
                        throw new SecurityException(msg);
                    }
                } else {
                    // Limit broadcast to their own package.
                    intent.setPackage(callerApp.info.packageName);
                }
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "Remote exception", e);
            return ActivityManager.BROADCAST_SUCCESS;
        }
    }

    final String action = intent.getAction();
    if (action != null) {
        switch (action) {
            case Intent.ACTION_UID_REMOVED:
            case Intent.ACTION_PACKAGE_REMOVED:
            case Intent.ACTION_PACKAGE_CHANGED:
            case Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE:
            case Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE:
                // Handle special intents: if this broadcast is from the package
                // manager about a package being removed, we need to remove all of
                // its activities from the history stack.
                if (checkComponentPermission(
                        android.Manifest.permission.BROADCAST_PACKAGE_REMOVED,
                        callingPid, callingUid, -1, true)
                        != PackageManager.PERMISSION_GRANTED) {
                    String msg = "Permission Denial: " + intent.getAction()
                            + " broadcast from " + callerPackage + " (pid=" + callingPid
                            + ", uid=" + callingUid + ")"
                            + " requires "
                            + android.Manifest.permission.BROADCAST_PACKAGE_REMOVED;
                    Slog.w(TAG, msg);
                    throw new SecurityException(msg);
                }
                switch (action) {
                    case Intent.ACTION_UID_REMOVED:
                        final Bundle intentExtras = intent.getExtras();
                        final int uid = intentExtras != null
                                ? intentExtras.getInt(Intent.EXTRA_UID) : -1;
                        if (uid >= 0) {
                            mBatteryStatsService.removeUid(uid);
                            mAppOpsService.uidRemoved(uid);
                        }
                        break;
                    case Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE:
                        // If resources are unavailable just force stop all those packages
                        // and flush the attribute cache as well.
                        String list[] =
                                intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                        if (list != null && list.length > 0) {
                            for (int i = 0; i < list.length; i++) {
                                forceStopPackageLocked(list[i], -1, false, true, true,
                                        false, false, userId, "storage unmount");
                            }
                            mRecentTasks.cleanupLocked(UserHandle.USER_ALL);
                            sendPackageBroadcastLocked(
                                    IApplicationThread.EXTERNAL_STORAGE_UNAVAILABLE, list,
                                    userId);
                        }
                        break;
                    case Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE:
                        mRecentTasks.cleanupLocked(UserHandle.USER_ALL);
                        break;
                    case Intent.ACTION_PACKAGE_REMOVED:
                    case Intent.ACTION_PACKAGE_CHANGED:
                        Uri data = intent.getData();
                        String ssp;
                        if (data != null && (ssp=data.getSchemeSpecificPart()) != null) {
                            boolean removed = Intent.ACTION_PACKAGE_REMOVED.equals(action);
                            boolean fullUninstall = removed &&
                                    !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                            final boolean killProcess =
                                    !intent.getBooleanExtra(Intent.EXTRA_DONT_KILL_APP, false);
                            if (killProcess) {
                                forceStopPackageLocked(ssp, UserHandle.getAppId(
                                        intent.getIntExtra(Intent.EXTRA_UID, -1)),
                                        false, true, true, false, fullUninstall, userId,
                                        removed ? "pkg removed" : "pkg changed");
                            }
                            if (removed) {
                                sendPackageBroadcastLocked(IApplicationThread.PACKAGE_REMOVED,
                                        new String[] {ssp}, userId);
                                if (fullUninstall) {
                                    mAppOpsService.packageRemoved(
                                            intent.getIntExtra(Intent.EXTRA_UID, -1), ssp);

                                    // Remove all permissions granted from/to this package
                                    removeUriPermissionsForPackageLocked(ssp, userId, true);

                                    removeTasksByPackageNameLocked(ssp, userId);
                                    mBatteryStatsService.notePackageUninstalled(ssp);
                                }
                            } else {
                                cleanupDisabledPackageComponentsLocked(ssp, userId, killProcess,
                                        intent.getStringArrayExtra(
                                                Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST));
                            }
                        }
                        break;
                }
                break;
            case Intent.ACTION_PACKAGE_ADDED:
                // Special case for adding a package: by default turn on compatibility mode.
                Uri data = intent.getData();
                String ssp;
                if (data != null && (ssp = data.getSchemeSpecificPart()) != null) {
                    final boolean replacing =
                            intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                    mCompatModePackages.handlePackageAddedLocked(ssp, replacing);

                    try {
                        ApplicationInfo ai = AppGlobals.getPackageManager().
                                getApplicationInfo(ssp, 0, 0);
                        mBatteryStatsService.notePackageInstalled(ssp,
                                ai != null ? ai.versionCode : 0);
                    } catch (RemoteException e) {
                    }
                }
                break;
            case Intent.ACTION_TIMEZONE_CHANGED:
                // If this is the time zone changed action, queue up a message that will reset
                // the timezone of all currently running processes. This message will get
                // queued up before the broadcast happens.
                mHandler.sendEmptyMessage(UPDATE_TIME_ZONE);
                break;
            case Intent.ACTION_TIME_CHANGED:
                // If the user set the time, let all running processes know.
                final int is24Hour =
                        intent.getBooleanExtra(Intent.EXTRA_TIME_PREF_24_HOUR_FORMAT, false) ? 1
                                : 0;
                mHandler.sendMessage(mHandler.obtainMessage(UPDATE_TIME, is24Hour, 0));
                BatteryStatsImpl stats = mBatteryStatsService.getActiveStatistics();
                synchronized (stats) {
                    stats.noteCurrentTimeChangedLocked();
                }
                break;
            case Intent.ACTION_CLEAR_DNS_CACHE:
                mHandler.sendEmptyMessage(CLEAR_DNS_CACHE_MSG);
                break;
            case Proxy.PROXY_CHANGE_ACTION:
                ProxyInfo proxy = intent.getParcelableExtra(Proxy.EXTRA_PROXY_INFO);
                mHandler.sendMessage(mHandler.obtainMessage(UPDATE_HTTP_PROXY_MSG, proxy));
                break;
        }
    }

    // Add to the sticky list if requested.
    if (sticky) {
        if (checkPermission(android.Manifest.permission.BROADCAST_STICKY,
                callingPid, callingUid)
                != PackageManager.PERMISSION_GRANTED) {
            String msg = "Permission Denial: broadcastIntent() requesting a sticky broadcast from pid="
                    + callingPid + ", uid=" + callingUid
                    + " requires " + android.Manifest.permission.BROADCAST_STICKY;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        if (requiredPermissions != null && requiredPermissions.length > 0) {
            Slog.w(TAG, "Can't broadcast sticky intent " + intent
                    + " and enforce permissions " + Arrays.toString(requiredPermissions));
            return ActivityManager.BROADCAST_STICKY_CANT_HAVE_PERMISSION;
        }
        if (intent.getComponent() != null) {
            throw new SecurityException(
                    "Sticky broadcasts can't target a specific component");
        }
        // We use userId directly here, since the "all" target is maintained
        // as a separate set of sticky broadcasts.
        if (userId != UserHandle.USER_ALL) {
            // But first, if this is not a broadcast to all users, then
            // make sure it doesn't conflict with an existing broadcast to
            // all users.
            ArrayMap<String, ArrayList<Intent>> stickies = mStickyBroadcasts.get(
                    UserHandle.USER_ALL);
            if (stickies != null) {
                ArrayList<Intent> list = stickies.get(intent.getAction());
                if (list != null) {
                    int N = list.size();
                    int i;
                    for (i=0; i<N; i++) {
                        if (intent.filterEquals(list.get(i))) {
                            throw new IllegalArgumentException(
                                    "Sticky broadcast " + intent + " for user "
                                    + userId + " conflicts with existing global broadcast");
                        }
                    }
                }
            }
        }
        ArrayMap<String, ArrayList<Intent>> stickies = mStickyBroadcasts.get(userId);
        if (stickies == null) {
            stickies = new ArrayMap<>();
            mStickyBroadcasts.put(userId, stickies);
        }
        ArrayList<Intent> list = stickies.get(intent.getAction());
        if (list == null) {
            list = new ArrayList<>();
            stickies.put(intent.getAction(), list);
        }
        final int stickiesCount = list.size();
        int i;
        for (i = 0; i < stickiesCount; i++) {
            if (intent.filterEquals(list.get(i))) {
                // This sticky already exists, replace it.
                list.set(i, new Intent(intent));
                break;
            }
        }
        if (i >= stickiesCount) {
            list.add(new Intent(intent));
        }
    }

    int[] users;
    if (userId == UserHandle.USER_ALL) {
        // Caller wants broadcast to go to all started users.
        users = mStartedUserArray;
    } else {
        // Caller wants broadcast to go to one specific user.
        users = new int[] {userId};
    }

    // Figure out who all will receive this broadcast.
    List receivers = null;
    List<BroadcastFilter> registeredReceivers = null;
    // Need to resolve the intent to interested receivers...
    if ((intent.getFlags()&Intent.FLAG_RECEIVER_REGISTERED_ONLY)
             == 0) {
        receivers = collectReceiverComponents(intent, resolvedType, callingUid, users);
    }
    if (intent.getComponent() == null) {
        if (userId == UserHandle.USER_ALL && callingUid == Process.SHELL_UID) {
            // Query one target user at a time, excluding shell-restricted users
            UserManagerService ums = getUserManagerLocked();
            for (int i = 0; i < users.length; i++) {
                if (ums.hasUserRestriction(
                        UserManager.DISALLOW_DEBUGGING_FEATURES, users[i])) {
                    continue;
                }
                List<BroadcastFilter> registeredReceiversForUser =
                        mReceiverResolver.queryIntent(intent,
                                resolvedType, false, users[i]);
                if (registeredReceivers == null) {
                    registeredReceivers = registeredReceiversForUser;
                } else if (registeredReceiversForUser != null) {
                    registeredReceivers.addAll(registeredReceiversForUser);
                }
            }
        } else {
            registeredReceivers = mReceiverResolver.queryIntent(intent,
                    resolvedType, false, userId);
        }
    }

    final boolean replacePending =
            (intent.getFlags()&Intent.FLAG_RECEIVER_REPLACE_PENDING) != 0;

    if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST, "Enqueing broadcast: " + intent.getAction()
            + " replacePending=" + replacePending);

    int NR = registeredReceivers != null ? registeredReceivers.size() : 0;
    if (!ordered && NR > 0) {
        // If we are not serializing this broadcast, then send the
        // registered receivers separately so they don't wait for the
        // components to be launched.
        final BroadcastQueue queue = broadcastQueueForIntent(intent);
        BroadcastRecord r = new BroadcastRecord(queue, intent, callerApp,
                callerPackage, callingPid, callingUid, resolvedType, requiredPermissions,
                appOp, brOptions, registeredReceivers, resultTo, resultCode, resultData,
                resultExtras, ordered, sticky, false, userId);
        if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST, "Enqueueing parallel broadcast " + r);
        final boolean replaced = replacePending && queue.replaceParallelBroadcastLocked(r);
        if (!replaced) {
            queue.enqueueParallelBroadcastLocked(r);
            queue.scheduleBroadcastsLocked();
        }
        registeredReceivers = null;
        NR = 0;
    }

    // Merge into one list.
    int ir = 0;
    if (receivers != null) {
        // A special case for PACKAGE_ADDED: do not allow the package
        // being added to see this broadcast.  This prevents them from
        // using this as a back door to get run as soon as they are
        // installed.  Maybe in the future we want to have a special install
        // broadcast or such for apps, but we'd like to deliberately make
        // this decision.
        String skipPackages[] = null;
        if (Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())
                || Intent.ACTION_PACKAGE_RESTARTED.equals(intent.getAction())
                || Intent.ACTION_PACKAGE_DATA_CLEARED.equals(intent.getAction())) {
            Uri data = intent.getData();
            if (data != null) {
                String pkgName = data.getSchemeSpecificPart();
                if (pkgName != null) {
                    skipPackages = new String[] { pkgName };
                }
            }
        } else if (Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE.equals(intent.getAction())) {
            skipPackages = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
        }
        if (skipPackages != null && (skipPackages.length > 0)) {
            for (String skipPackage : skipPackages) {
                if (skipPackage != null) {
                    int NT = receivers.size();
                    for (int it=0; it<NT; it++) {
                        ResolveInfo curt = (ResolveInfo)receivers.get(it);
                        if (curt.activityInfo.packageName.equals(skipPackage)) {
                            receivers.remove(it);
                            it--;
                            NT--;
                        }
                    }
                }
            }
        }

        int NT = receivers != null ? receivers.size() : 0;
        int it = 0;
        ResolveInfo curt = null;
        BroadcastFilter curr = null;
        while (it < NT && ir < NR) {
            if (curt == null) {
                curt = (ResolveInfo)receivers.get(it);
            }
            if (curr == null) {
                curr = registeredReceivers.get(ir);
            }
            if (curr.getPriority() >= curt.priority) {
                // Insert this broadcast record into the final list.
                receivers.add(it, curr);
                ir++;
                curr = null;
                it++;
                NT++;
            } else {
                // Skip to the next ResolveInfo in the final list.
                it++;
                curt = null;
            }
        }
    }
    while (ir < NR) {
        if (receivers == null) {
            receivers = new ArrayList();
        }
        receivers.add(registeredReceivers.get(ir));
        ir++;
    }

    if ((receivers != null && receivers.size() > 0)
            || resultTo != null) {
        BroadcastQueue queue = broadcastQueueForIntent(intent);
        BroadcastRecord r = new BroadcastRecord(queue, intent, callerApp,
                callerPackage, callingPid, callingUid, resolvedType,
                requiredPermissions, appOp, brOptions, receivers, resultTo, resultCode,
                resultData, resultExtras, ordered, sticky, false, userId);

        if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST, "Enqueueing ordered broadcast " + r
                + ": prev had " + queue.mOrderedBroadcasts.size());
        if (DEBUG_BROADCAST) Slog.i(TAG_BROADCAST,
                "Enqueueing broadcast " + r.intent.getAction());

        boolean replaced = replacePending && queue.replaceOrderedBroadcastLocked(r);
        if (!replaced) {
            queue.enqueueOrderedBroadcastLocked(r);
            queue.scheduleBroadcastsLocked();
        }
    }

    return ActivityManager.BROADCAST_SUCCESS;
}
```

