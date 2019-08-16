# Android Activity 启动流程分析

[TOC]

## 前言

了解 Android 系统原理不仅需要从宏观上了解，还需要深入细节，两者结合才能做到深入理解。下面基于 Android 6.0.1 系统源码分析 activity 启动过程。

## 启动方式

一般启动 activiy 可以通过两种方式，一种是通过 activtiy 启动另一个 activtiy，另一种则是通过非 activity 的 context 对象启动。

系统对于 activity 的展示是通过任务返回栈的方式管理的，那么通常通过 activity 启动一个普通启动模式的 activity 将被放置在启动者 activity 的上方，而 context 由于并不是 activity，所以它本身不存在 activity 栈，那么使用 context 启动 activity 需要指定 `Intent.FLAG_ACTIVITY_NEW_TASK` 标记指定在新栈中启动。

通过 activity 启动的 activity 还可以提供返回值给启动者 activity，通过 context 启动则不能。

启动 activity 时，如果 activity 在清单文件中配置了进程名，那么他将运行在新的进程中。

下面分别查看两种启动方式的实现：

### Activity

```java
// Activity.java

@Override
public void startActivity(Intent intent) {
    this.startActivity(intent, null);
}

@Override
public void startActivity(Intent intent, @Nullable Bundle options) {
    if (options != null) {
        startActivityForResult(intent, -1, options);
    } else {
        // Note we want to go through this call for compatibility with
        // applications that may have overridden the method.
        startActivityForResult(intent, -1);
    }
}

public void startActivityForResult(Intent intent, int requestCode) {
    startActivityForResult(intent, requestCode, null);
}

// 最终都走到这个方法。
public void startActivityForResult(Intent intent, int requestCode, @Nullable Bundle options) {
    // mParent 为 ActivityGroup（已过时）提供支持，所以这里先不考虑存在的情况。
    if (mParent == null) {
        Instrumentation.ActivityResult ar =
            mInstrumentation.execStartActivity(
                this, mMainThread.getApplicationThread(), mToken, this,
                intent, requestCode, options);
        if (ar != null) {
            // 将结果发送给需要接收的 activity（for result）。
            mMainThread.sendActivityResult(
                mToken, mEmbeddedID, requestCode, ar.getResultCode(),
                ar.getResultData());
        }
        if (requestCode >= 0) {
            // 如果本次启动请求了返回结果，我们可以避免 activity 在接收到返回的结果之前展示。
            // 为了避免闪烁，设置此代码将使 activity 在 onCreate 或 onResume 的过程
            // 中处于隐藏状态。
            // 此代码只能在请求结果完成时才能取消，因为这样可以保证无论发生什么情况，
            // 我们都可以在 activity 结束之后获取信息。
            mStartedActivity = true;
        }

        cancelInputsAndStartExitTransition(options);
    } else {
        if (options != null) {
            mParent.startActivityFromChild(this, intent, requestCode, options);
        } else {
            // Note we want to go through this method for compatibility with
            // existing applications that may have overridden it.
            mParent.startActivityFromChild(this, intent, requestCode);
        }
    }
}
```

### Context

```java
// ContextImpl.java

@Override
public void startActivity(Intent intent) {
    warnIfCallingFromSystemProcess();
    startActivity(intent, null);
}

@Override
public void startActivity(Intent intent, Bundle options) {
    warnIfCallingFromSystemProcess();
    if ((intent.getFlags()&Intent.FLAG_ACTIVITY_NEW_TASK) == 0) {
        throw new AndroidRuntimeException(
                "Calling startActivity() from outside of an Activity "
                + " context requires the FLAG_ACTIVITY_NEW_TASK flag."
                + " Is this really what you want?");
    }
    mMainThread.getInstrumentation().execStartActivity(
            getOuterContext(), mMainThread.getApplicationThread(), null,
            (Activity) null, intent, -1, options);
}
```

## 启动流程

可以看到不管启动的 activity 是否需要返回值（for result），最终他们都会调用到 `mInstrumentation.execStartActivity` 这个方法，不过参数上会有些差异。

```java
public ActivityResult execStartActivity(
        Context who, IBinder contextThread, IBinder token, Activity target,
        Intent intent, int requestCode, Bundle options);
```

`Instrumentation` 类型直译过来是“仪表”，在 android framework 层它是负责管理 activity 生命周期的类型。

首先看一下上面参数的各个含义：

```java
execStartActivity:
who:Context           -> 启动 activity 的 context。
contextThread:IBinder -> 启动 activity 的主线程对象，它由 `ApplicationThread` 类实现，将被发送到 AMS 中，方便 AMS 与应用进程沟通。
token:IBinder         -> 当前 activity 的 token 对象，它是 AMS 中 ActivityRecord 对象对应的 Binder 客户端句柄，ActivityRecord 类型是 AMS 为了记录启动的 activity 信息的类型。
target:Activity       -> 当前 activity 对象。
intent:Intent         -> 要启动的 activity 意图。
requestCode:int       -> 需要接收结果时的请求码，-1 表示不需要接收结果。
options:Bundle        -> 附加选项。
```

对比 context 和 activity 启动 activity 的参数可以发现，context 由于可能不是 activity 对象，所以 `token` 和 `activity` 都是 null，而且不能接收返回值，所以 `requestCode` 一定为 -1。其中 context 传递的第一个参数 `getOuterContext` 如果 context 是 activity，那么它就是这个 activity 的对象。

这个在 `Activity` 的 context 创建过程中体现：

```java
ContextImpl appContext = ContextImpl.createActivityContext(
        this, r.packageInfo, displayId, r.overrideConfig);
appContext.setOuterContext(activity);
Context baseContext = appContext;
...
```

### Client

#### Instrumentation

由于 activity 这种启动方式参数较为全面，那么现在从它开始分析 activity 启动流程。

``` java
// Instrumentation.java
    
public ActivityResult execStartActivity(
        Context who, IBinder contextThread, IBinder token, Activity target,
        Intent intent, int requestCode, Bundle options) {
    IApplicationThread whoThread = (IApplicationThread) contextThread;
    Uri referrer = target != null ? target.onProvideReferrer() : null;
    if (referrer != null) {
        intent.putExtra(Intent.EXTRA_REFERRER, referrer);
    }
    // 检查 activity 监视器是否允许 activity 启动，不运行则直接返回。
    if (mActivityMonitors != null) {
        synchronized (mSync) {
            final int N = mActivityMonitors.size();
            for (int i=0; i<N; i++) {
                final ActivityMonitor am = mActivityMonitors.get(i);
                if (am.match(who, null, intent)) {
                    am.mHits++;
                    if (am.isBlocking()) {
                        return requestCode >= 0 ? am.getResult() : null;
                    }
                    break;
                }
            }
        }
    }
    try {
        intent.migrateExtraStreamToClipData();
        intent.prepareToLeaveProcess();
        int result = ActivityManagerNative.getDefault()
            .startActivity(whoThread, who.getBasePackageName(), intent,
                    intent.resolveTypeIfNeeded(who.getContentResolver()),
                    token, target != null ? target.mEmbeddedID : null,
                    requestCode, 0, null, options);
        checkStartActivityResult(result, intent);
    } catch (RemoteException e) {
        throw new RuntimeException("Failure from system", e);
    }
    return null;
}
```

这里只是检查了一下 activity 监视器状态，然后就调用了 `ActivityManagerNative。getDefault()` 的 `startActivity` 方法。

通过对 Android 系统中 Binder 通信框架和系统服务的关系，`ActivityManagerNative。getDefault()` 返回的是 `ActivityManagerProxy` 对象，它作为 `ActivityManagerService` 的客户端，将应用进程的请求转发到 `ActivityManagerService` 中，即平常提到的 AMS 服务。

分析一下方法参数：

```java
startActivity:
caller:IApplicationThread -> 同上。
callingPackage:String     -> 调用者 context 包名。
intent:Intent             -> 同上。
resolvedType:String       -> intent MIME type。
resultTo:IBinder          -> 结果接收 activity 的 ActivityRecord 句柄，目前为当前 activity。
resultWho:String          -> 结果接收者描述，Activity 的 mEmbeddedID。
requestCode:int           -> 同上。
startFlags:int            -> 0。
profilerInfo:ProfilerInfo -> null。
options:Bundle            -> 同上。
```

#### ActivityManagerProxy

```java
// ActivityManagerNative.java - class ActivityManagerProxy

public int startActivity(IApplicationThread caller, String callingPackage, Intent intent,
        String resolvedType, IBinder resultTo, String resultWho, int requestCode,
        int startFlags, ProfilerInfo profilerInfo, Bundle options) throws RemoteException {
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    data.writeInterfaceToken(IActivityManager.descriptor);
    data.writeStrongBinder(caller != null ? caller.asBinder() : null);
    data.writeString(callingPackage);
    intent.writeToParcel(data, 0);
    data.writeString(resolvedType);
    data.writeStrongBinder(resultTo);
    data.writeString(resultWho);
    data.writeInt(requestCode);
    data.writeInt(startFlags);
    if (profilerInfo != null) {
        data.writeInt(1);
        profilerInfo.writeToParcel(data, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
    } else {
        data.writeInt(0);
    }
    if (options != null) {
        data.writeInt(1);
        options.writeToParcel(data, 0);
    } else {
        data.writeInt(0);
    }
    mRemote.transact(START_ACTIVITY_TRANSACTION, data, reply, 0);
    reply.readException();
    int result = reply.readInt();
    reply.recycle();
    data.recycle();
    return result;
}
```

这里将全部参数原封不动的发送给了 AMS，使用了 `START_ACTIVITY_TRANSACTION` 指令进行携带，最终发送到了 `ActivityManagerNative` 的 `onTransact` 方法中。

#### ActivityManagerNative

ActivityManagerNative 负责为 AMS 接收指令和数据并调用 AMS 的入口方法。

```java
// ActivityManagerNative.java

@Override
public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
        throws RemoteException {
    switch (code) {
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
        int result = startActivity(app, callingPackage, intent, resolvedType,
                resultTo, resultWho, requestCode, startFlags, profilerInfo, options);
        reply.writeNoException();
        reply.writeInt(result);
        return true;
    }
    ...
}
```

### Server

#### ActivityManagerService

```java
// ActivityManagerService.java

@Override
public final int startActivity(IApplicationThread caller, String callingPackage,
        Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode,
        int startFlags, ProfilerInfo profilerInfo, Bundle options) {
    return startActivityAsUser(caller, callingPackage, intent, resolvedType, resultTo,
        resultWho, requestCode, startFlags, profilerInfo, options,
        UserHandle.getCallingUserId());
}
```

调用了 `startActivityAsUser` 添加了一个 `UserHandle.getCallingUserId()` 参数，它是启动 Activity 的调用者的用户 Id。

```java
// ActivityManagerService.java

@Override
public final int startActivityAsUser(IApplicationThread caller, String callingPackage,
        Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode,
        int startFlags, ProfilerInfo profilerInfo, Bundle options, int userId) {
    // 隔离进程（uid 在一定范围内的进程）不允许调用 startActivity。
    enforceNotIsolatedCaller("startActivity");
    // 处理 userId，如果调用者 userId 和当前 userId 相同则为 userId，否则根据判断是否将
    // callingUserId 作为 mCurrentUserId。
    userId = handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId,
            false, ALLOW_FULL_ONLY, "startActivity", null);
    // TODO: Switch to user app stacks here.
    return mStackSupervisor.startActivityMayWait(caller, -1, callingPackage, intent,
            resolvedType, null, null, resultTo, resultWho, requestCode, startFlags,
            profilerInfo, null, null, options, false, userId, null, null);
}
```

AMS 在接收到 userId 后直接调用了 `mStackSupervisor` 的 `startActivity` 方法，它是 `ActivityStackSupervisor` 类型的对象，是用于管理 Activity 栈的类型。

#### ActivityStackSupervisor

`mStackSupervisor` 的初始化在 AMS 构造器中：

```java
// ActivityManagerService.java

mRecentTasks = new RecentTasks(this);
```

```java
// ActivityStackSupervisor.java

public ActivityStackSupervisor(ActivityManagerService service, RecentTasks recentTasks) {
    mService = service;
    mRecentTasks = recentTasks;
    mHandler = new  ActivityStackSupervisorHandler(mService.mHandler.getLooper());
}
```

接下来看 `startActivity` 方法：

参数分析

```java
startActivityMayWait:
caller:IApplicationThread     -> 同上。
callingUid:int                -> -1。
callingPackage:String         -> 同上。
intent:Intent                 -> 同上。
resolvedType:String           -> 同上。
voiceSession:IVoiceInteractionSession -> null，语音服务相关。
voiceInteractor:IVoiceInteractor      -> null，语音服务相关。
resultTo:IBinder              -> 同上。
resultWho:String              -> 同上。
requestCode:int               -> -1，同上。
startFlags:int                -> 0，同上。
profilerInfo:Prefiler         -> null。
outResult:WaitResult          -> null。
config:Configuration          -> null，配置变更。
options:Bundle                -> null。
ignoreTargetSecurity:boolean  -> false。
userId:int                    -> userId。
iContainer:IActivityContainer -> null，activity 父容器。
inTask:TaskRecord             -> null，栈记录。
```

```java
// ActivityStackSupervisor.java

final int startActivityMayWait(IApplicationThread caller, int callingUid,
        String callingPackage, Intent intent, String resolvedType,
        IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
        IBinder resultTo, String resultWho, int requestCode, int startFlags,
        ProfilerInfo profilerInfo, WaitResult outResult, Configuration config,
        Bundle options, boolean ignoreTargetSecurity, int userId,
        IActivityContainer iContainer, TaskRecord inTask) {
    // 拒接 intent 携带文件描述符。
    if (intent != null && intent.hasFileDescriptors()) {
        throw new IllegalArgumentException("File descriptors passed in Intent");
    }
    boolean componentSpecified = intent.getComponent() != null;

    // 不修改原始 intent。
    intent = new Intent(intent);

    // 1. 查询要启动的 activity 信息。
    ActivityInfo aInfo =
            resolveActivity(intent, resolvedType, startFlags, profilerInfo, userId);

    ActivityContainer container = (ActivityContainer)iContainer;
    synchronized (mService) {
        if (container != null && container.mParentActivity != null &&
                container.mParentActivity.state != RESUMED) {
            // 这里不考虑父子 activiy 的情况。
            // 如果父 activity 没有进入 resumed 状态，拒绝启动子 activity。
            return ActivityManager.START_CANCELED;
        }
        // 调用者进程 PID 和 UID。
        final int realCallingPid = Binder.getCallingPid();
        final int realCallingUid = Binder.getCallingUid();
        int callingPid;
        if (callingUid >= 0) {
            callingPid = -1;
        } else if (caller == null) {
            callingPid = realCallingPid;
            callingUid = realCallingUid;
        } else {
            callingPid = callingUid = -1;
        }

        final ActivityStack stack;
        if (container == null || container.mStack.isOnHomeDisplay()) {
            // 将 stack 赋值为当前焦点栈。
            stack = mFocusedStack;
        } else {
            stack = container.mStack;
        }
        // 配置是否变更，当前 config 为 null。
        stack.mConfigWillChange = config != null && mService.mConfiguration.diff(config) != 0;
        if (DEBUG_CONFIGURATION) Slog.v(TAG_CONFIGURATION,
                "Starting activity when config will change = " + stack.mConfigWillChange);

        // 清理 Binder 中调用者 id，并填入当前进程 id。
        final long origId = Binder.clearCallingIdentity();

        if (aInfo != null &&
                (aInfo.applicationInfo.privateFlags
                        &ApplicationInfo.PRIVATE_FLAG_CANT_SAVE_STATE) != 0) {
            // This may be a heavy-weight process!  Check to see if we already
            // have another, different heavy-weight process running.
            if (aInfo.processName.equals(aInfo.applicationInfo.packageName)) {
                if (mService.mHeavyWeightProcess != null &&
                        (mService.mHeavyWeightProcess.info.uid != aInfo.applicationInfo.uid ||
                        !mService.mHeavyWeightProcess.processName.equals(aInfo.processName))) {
                // 重量级进程处理，特殊情况走此分支。
            }
        }

        // 2. 下一步启动 activity。
        int res = startActivityLocked(caller, intent, resolvedType, aInfo,
                voiceSession, voiceInteractor, resultTo, resultWho,
                requestCode, callingPid, callingUid, callingPackage,
                realCallingPid, realCallingUid, startFlags, options, ignoreTargetSecurity,
                componentSpecified, null, container, inTask);

        Binder.restoreCallingIdentity(origId);

        if (stack.mConfigWillChange) {
            // 如果 caller 还想要切换到新的配置，请立即执行此操作。这允许一个干净的切换
            // 因为此时我们正在等待当前 activity 暂停（因为不会销毁它），并且还没有
            // 开始启动下一个 activity。
            mService.enforceCallingPermission(android.Manifest.permission.CHANGE_CONFIGURATION,
                    "updateConfiguration()");
            stack.mConfigWillChange = false;
            if (DEBUG_CONFIGURATION) Slog.v(TAG_CONFIGURATION,
                    "Updating to new configuration after starting activity.");
            // 通知 activity 配置更新，回调 onConfigurationChanged 方法。
            mService.updateConfigurationLocked(config, null, false, false);
        }

        if (outResult != null) {
            outResult.result = res;
            if (res == ActivityManager.START_SUCCESS) {
                mWaitingActivityLaunched.add(outResult);
                do {
                    try {
                        mService.wait();
                    } catch (InterruptedException e) {
                    }
                } while (!outResult.timeout && outResult.who == null);
            } else if (res == ActivityManager.START_TASK_TO_FRONT) {
                ActivityRecord r = stack.topRunningActivityLocked(null);
                if (r.nowVisible && r.state == RESUMED) {
                    outResult.timeout = false;
                    outResult.who = new ComponentName(r.info.packageName, r.info.name);
                    outResult.totalTime = 0;
                    outResult.thisTime = 0;
                } else {
                    outResult.thisTime = SystemClock.uptimeMillis();
                    mWaitingActivityVisible.add(outResult);
                    do {
                        try {
                            mService.wait();
                        } catch (InterruptedException e) {
                        }
                    } while (!outResult.timeout && outResult.who == null);
                }
            }
        }

        return res;
    }
}
```

上面首先使用 `resolveActivity` 寻找合适的 activity，然后继续下一步的启动，先看一下 `resolveActivity` 方法：

```java
// ActivityStackSupervisor.java

ActivityInfo resolveActivity(Intent intent, String resolvedType, int startFlags,
        ProfilerInfo profilerInfo, int userId) {
    // 收集目标 Intent 相关信息。
    ActivityInfo aInfo;
    try {
        // 调用 PackageManager 的 resolveIntent 方法，会被发送到 PMS 处理。
        ResolveInfo rInfo = AppGlobals.getPackageManager().resolveIntent(
                    intent, resolvedType,
                    PackageManager.MATCH_DEFAULT_ONLY
                                | ActivityManagerService.STOCK_PM_FLAGS, userId);
        aInfo = rInfo != null ? rInfo.activityInfo : null;
    } catch (RemoteException e) {
        aInfo = null;
    }

    if (aInfo != null) {
        // 将找到的目标组件存入 Intent 中， 由于已存在，那么我们不会再重新这样做了。
        // 例如，如果用户从历史记录导航回到这里，我们应该始终重新启动完全相同的 activity。
        intent.setComponent(new ComponentName(
                aInfo.applicationInfo.packageName, aInfo.name));

        // 对于 system process，不要进行调试。
        if ((startFlags&ActivityManager.START_FLAG_DEBUG) != 0) {
            if (!aInfo.processName.equals("system")) {
                mService.setDebugApp(aInfo.processName, true, false);
            }
        }

        if ((startFlags&ActivityManager.START_FLAG_OPENGL_TRACES) != 0) {
            if (!aInfo.processName.equals("system")) {
                mService.setOpenGlTraceApp(aInfo.applicationInfo, aInfo.processName);
            }
        }

        if (profilerInfo != null) {
            if (!aInfo.processName.equals("system")) {
                mService.setProfileApp(aInfo.applicationInfo, aInfo.processName, profilerInfo);
            }
        }
    }
    return aInfo;
}
```

继续看 `PackageManagerService` 的 `resolveIntent` 方法。

#### PackageManagerService

```java
// PackageManagerService.java

@Override
public ResolveInfo resolveIntent(Intent intent, String resolvedType,
        int flags, int userId) {
    if (!sUserManager.exists(userId)) return null;
    // 检查权限，主要是确认是否允许非当前用户调用。
    enforceCrossUserPermission(Binder.getCallingUid(), userId, false, false, "resolve intent");
    // 查询相关 activity。
    List<ResolveInfo> query = queryIntentActivities(intent, resolvedType, flags, userId);
    // 根据查询到的 activity 选择最优选择。
    return chooseBestActivity(intent, resolvedType, flags, query, userId);
}
```

```java
// PackageManagerService.java

@Override
public List<ResolveInfo> queryIntentActivities(Intent intent,
        String resolvedType, int flags, int userId) {
    if (!sUserManager.exists(userId)) return Collections.emptyList();
    enforceCrossUserPermission(Binder.getCallingUid(), userId, false, false, "query intent activities");
    ComponentName comp = intent.getComponent();
    if (comp == null) {
        if (intent.getSelector() != null) {
            intent = intent.getSelector();
            comp = intent.getComponent();
        }
    }

    if (comp != null) {
        // componentName 不为 null，则精确查找。
        final List<ResolveInfo> list = new ArrayList<ResolveInfo>(1);
        final ActivityInfo ai = getActivityInfo(comp, flags, userId);
        if (ai != null) {
            final ResolveInfo ri = new ResolveInfo();
            ri.activityInfo = ai;
            list.add(ri);
        }
        return list;
    }

    synchronized (mPackages) {
        final String pkgName = intent.getPackage();
        // 处理命名是否提供的方法。
        if (pkgName == null) {
            List<CrossProfileIntentFilter> matchingFilters =
                    getMatchingCrossProfileIntentFilters(intent, resolvedType, userId);
            // 检查需要跳过当前配置文件的结果。
            ResolveInfo xpResolveInfo  = querySkipCurrentProfileIntents(matchingFilters, intent,
                    resolvedType, flags, userId);
            if (xpResolveInfo != null && isUserEnabled(xpResolveInfo.targetUserId)) {
                List<ResolveInfo> result = new ArrayList<ResolveInfo>(1);
                result.add(xpResolveInfo);
                return filterIfNotPrimaryUser(result, userId);
            }

            // 检查当前配置文件中的结果。
            List<ResolveInfo> result = mActivities.queryIntent(
                    intent, resolvedType, flags, userId);

            ...
            return result;
        }
        final PackageParser.Package pkg = mPackages.get(pkgName);
        if (pkg != null) {
            return filterIfNotPrimaryUser(
                    mActivities.queryIntentForPackage(
                            intent, resolvedType, flags, pkg.activities, userId),
                    userId);
        }
        return new ArrayList<ResolveInfo>();
    }
}
```

这里根据 intent 提供的信息，根据是否包含组件名，包名等信息查询何时合适的 activity 新信息列返回。

而上面的 `chooseBestActivity` 将会根据查询到的 activity 信息，通过 priority 等相关规则寻找最终的目标 activity，这里就直接向下进行，回到上面，下一个方法是 `startActivityLocked`。

#### ActivityStackSupervisor

```java
// ActivityStackSupervisor.java

final int startActivityLocked(IApplicationThread caller,
        Intent intent, String resolvedType, ActivityInfo aInfo,
        IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
        IBinder resultTo, String resultWho, int requestCode,
        int callingPid, int callingUid, String callingPackage,
        int realCallingPid, int realCallingUid, int startFlags, Bundle options,
        boolean ignoreTargetSecurity, boolean componentSpecified, ActivityRecord[] outActivity,
        ActivityContainer container, TaskRecord inTask) {
    int err = ActivityManager.START_SUCCESS;

    ProcessRecord callerApp = null;
    if (caller != null) {
        // 根据 caller 查询进程记录。
        callerApp = mService.getRecordForAppLocked(caller);
        if (callerApp != null) {
            callingPid = callerApp.pid;
            callingUid = callerApp.info.uid;
        } else {
            Slog.w(TAG, "Unable to find app for caller " + caller
                  + " (pid=" + callingPid + ") when starting: "
                  + intent.toString());
            err = ActivityManager.START_PERMISSION_DENIED;
        }
    }

    final int userId = aInfo != null ? UserHandle.getUserId(aInfo.applicationInfo.uid) : 0;

    if (err == ActivityManager.START_SUCCESS) {
        Slog.i(TAG, "START u" + userId + " {" + intent.toShortString(true, true, true, false)
                + "} from uid " + callingUid
                + " on display " + (container == null ? (mFocusedStack == null ?
                        Display.DEFAULT_DISPLAY : mFocusedStack.mDisplayId) :
                        (container.mActivityDisplay == null ? Display.DEFAULT_DISPLAY :
                                container.mActivityDisplay.mDisplayId)));
    }

    ActivityRecord sourceRecord = null;
    // 接收结果的 activity 记录。
    ActivityRecord resultRecord = null;
    if (resultTo != null) {
        // 从 activity 栈记录中查询调用者 activity 记录。
        sourceRecord = isInAnyStackLocked(resultTo);
        if (DEBUG_RESULTS) Slog.v(TAG_RESULTS,
                "Will send result to " + resultTo + " " + sourceRecord);
        if (sourceRecord != null) {
            if (requestCode >= 0 && !sourceRecord.finishing) {
                resultRecord = sourceRecord;
            }
        }
    }

    final int launchFlags = intent.getFlags();

    // 目前流程不需要接收返回结果。
    if ((launchFlags & Intent.FLAG_ACTIVITY_FORWARD_RESULT) != 0 && sourceRecord != null) {
        // 将接收 result 的目标从源 activity 转移至正在启动的新 activity，包括任何错误。
        if (requestCode >= 0) {
            ActivityOptions.abort(options);
            return ActivityManager.START_FORWARD_AND_REQUEST_CONFLICT;
        }
        resultRecord = sourceRecord.resultTo;
        if (resultRecord != null && !resultRecord.isInStackLocked()) {
            resultRecord = null;
        }
        resultWho = sourceRecord.resultWho;
        requestCode = sourceRecord.requestCode;
        sourceRecord.resultTo = null;
        if (resultRecord != null) {
            // 从等等接收结果的 activity 记录移除源 activity 记录。
            resultRecord.removeResultsLocked(sourceRecord, resultWho, requestCode);
        }
        if (sourceRecord.launchedFromUid == callingUid) {
            // 在流程中新的 activtiy 是从上一个 uid 相同的 activity 启动的，
            // 并要求将其结果转发给上一个 activity，在这种情况下，
            // activity 充当两者的蹦床，因此我们还希望将其的 launchFromPackage
            // 更新为与之前的 activity 相同，请注意，这是安全的，
            // 因为我们知道这两个包来自同一个 uid，调用者也可以自己提供相同的包名。
            // 这专门用于处理在应用程序流程中启动的 intent 选择器的情况，以重定向到用户选择的
            // activity，我们希望最终 activity 将其视为由先前的应用程序的 activity 启动。
            callingPackage = sourceRecord.launchedFromPackage;
        }
    }

    if (err == ActivityManager.START_SUCCESS && intent.getComponent() == null) {
        // 我们找不到可以处理 intent 的类型，那就结束。
        err = ActivityManager.START_INTENT_NOT_RESOLVED;
    }

    if (err == ActivityManager.START_SUCCESS && aInfo == null) {
        // 我们找不到 intent 中指定的类型。
        err = ActivityManager.START_CLASS_NOT_FOUND;
    }

    if (err == ActivityManager.START_SUCCESS
            && !isCurrentProfileLocked(userId)
            && (aInfo.flags & FLAG_SHOW_FOR_ALL_USERS) == 0) {
        // i尝试启动对所有用户都不显示的 activity。
        err = ActivityManager.START_NOT_CURRENT_USER_ACTIVITY;
    }

    if (err == ActivityManager.START_SUCCESS && sourceRecord != null
            && sourceRecord.task.voiceSession != null) {
        // 如果此 activity 是作为语音会话的一部分启动的，我们需要确保这样做是安全的。
        // 如果即将进行的活动也将成为语音会话的一部分，我们只能在明确表示支持
        // android.intent.category.VOICE 类别时启动它，或者它是调用应用的一部分。
        if ((launchFlags & Intent.FLAG_ACTIVITY_NEW_TASK) == 0
                && sourceRecord.info.applicationInfo.uid != aInfo.applicationInfo.uid) {
            try {
                intent.addCategory(Intent.CATEGORY_VOICE);
                if (!AppGlobals.getPackageManager().activitySupportsIntent(
                        intent.getComponent(), intent, resolvedType)) {
                    Slog.w(TAG,
                            "Activity being started in current voice task does not support voice: "
                            + intent);
                    err = ActivityManager.START_NOT_VOICE_COMPATIBLE;
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Failure checking voice capabilities", e);
                err = ActivityManager.START_NOT_VOICE_COMPATIBLE;
            }
        }
    }

    if (err == ActivityManager.START_SUCCESS && voiceSession != null) {
        // 如果调用者正在启动新的语音会话，只需要确保目标允许它以这种方式运行。
        try {
            if (!AppGlobals.getPackageManager().activitySupportsIntent(intent.getComponent(),
                    intent, resolvedType)) {
                Slog.w(TAG,
                        "Activity being started in new voice task does not support: "
                        + intent);
                err = ActivityManager.START_NOT_VOICE_COMPATIBLE;
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "Failure checking voice capabilities", e);
            err = ActivityManager.START_NOT_VOICE_COMPATIBLE;
        }
    }

    // 结果 activity 栈。
    final ActivityStack resultStack = resultRecord == null ? null : resultRecord.task.stack;

    // 如果有错误，则返回。
    if (err != ActivityManager.START_SUCCESS) {
        if (resultRecord != null) {
            resultStack.sendActivityResultLocked(-1,
                resultRecord, resultWho, requestCode,
                Activity.RESULT_CANCELED, null);
        }
        ActivityOptions.abort(options);
        return err;
    }

    boolean abort = false;

    final int startAnyPerm = mService.checkPermission(
            START_ANY_ACTIVITY, callingPid, callingUid);

    // 权限检查，各种限制条件。
    if (startAnyPerm != PERMISSION_GRANTED) {
        final int componentRestriction = getComponentRestrictionForCallingPackage(
                aInfo, callingPackage, callingPid, callingUid, ignoreTargetSecurity);
        final int actionRestriction = getActionRestrictionForCallingPackage(
                intent.getAction(), callingPackage, callingPid, callingUid);

        if (componentRestriction == ACTIVITY_RESTRICTION_PERMISSION
                || actionRestriction == ACTIVITY_RESTRICTION_PERMISSION) {
            if (resultRecord != null) {
                resultStack.sendActivityResultLocked(-1,
                        resultRecord, resultWho, requestCode,
                        Activity.RESULT_CANCELED, null);
            }
            String msg;
            if (actionRestriction == ACTIVITY_RESTRICTION_PERMISSION) {
                msg = "Permission Denial: starting " + intent.toString()
                        + " from " + callerApp + " (pid=" + callingPid
                        + ", uid=" + callingUid + ")" + " with revoked permission "
                        + ACTION_TO_RUNTIME_PERMISSION.get(intent.getAction());
            } else if (!aInfo.exported) {
                msg = "Permission Denial: starting " + intent.toString()
                        + " from " + callerApp + " (pid=" + callingPid
                        + ", uid=" + callingUid + ")"
                        + " not exported from uid " + aInfo.applicationInfo.uid;
            } else {
                msg = "Permission Denial: starting " + intent.toString()
                        + " from " + callerApp + " (pid=" + callingPid
                        + ", uid=" + callingUid + ")"
                        + " requires " + aInfo.permission;
            }
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }

        if (actionRestriction == ACTIVITY_RESTRICTION_APPOP) {
            String message = "Appop Denial: starting " + intent.toString()
                    + " from " + callerApp + " (pid=" + callingPid
                    + ", uid=" + callingUid + ")"
                    + " requires " + AppOpsManager.permissionToOp(
                            ACTION_TO_RUNTIME_PERMISSION.get(intent.getAction()));
            Slog.w(TAG, message);
            abort = true;
        } else if (componentRestriction == ACTIVITY_RESTRICTION_APPOP) {
            String message = "Appop Denial: starting " + intent.toString()
                    + " from " + callerApp + " (pid=" + callingPid
                    + ", uid=" + callingUid + ")"
                    + " requires appop " + AppOpsManager.permissionToOp(aInfo.permission);
            Slog.w(TAG, message);
            abort = true;
        }
    }

    abort |= !mService.mIntentFirewall.checkStartActivity(intent, callingUid,
            callingPid, resolvedType, aInfo.applicationInfo);

    if (mService.mController != null) {
        try {
            // 我们给 activity 观察者有额外的数据，因为它可以包含私有数据。
            Intent watchIntent = intent.cloneFilter();
            abort |= !mService.mController.activityStarting(watchIntent,
                    aInfo.applicationInfo.packageName);
        } catch (RemoteException e) {
            mService.mController = null;
        }
    }

    // 不允许启动。
    if (abort) {
        if (resultRecord != null) {
            resultStack.sendActivityResultLocked(-1, resultRecord, resultWho, requestCode,
                    Activity.RESULT_CANCELED, null);
        }
        // We pretend to the caller that it was really started, but
        // they will just get a cancel result.
        ActivityOptions.abort(options);
        return ActivityManager.START_SUCCESS;
    }

    // 创建 activity 记录。
    ActivityRecord r = new ActivityRecord(mService, callerApp, callingUid, callingPackage,
            intent, resolvedType, aInfo, mService.mConfiguration, resultRecord, resultWho,
            requestCode, componentSpecified, voiceSession != null, this, container, options);
    if (outActivity != null) {
        outActivity[0] = r;
    }

    if (r.appTimeTracker == null && sourceRecord != null) {
        // If the caller didn't specify an explicit time tracker, we want to continue
        // tracking under any it has.
        r.appTimeTracker = sourceRecord.appTimeTracker;
    }

    // 将当前焦点栈赋值 activity 栈赋。
    final ActivityStack stack = mFocusedStack;
    if (voiceSession == null && (stack.mResumedActivity == null
            || stack.mResumedActivity.info.applicationInfo.uid != callingUid)) {
        // 如果前台 activity 栈还没有 resumed 状态的 activity，则检查是否允许切换。
        // 1. 允许切换的时间小于当前时间。2. 检查是否有切换权限（不影响同一个 app 内切换）。
        if (!mService.checkAppSwitchAllowedLocked(callingPid, callingUid,
                realCallingPid, realCallingUid, "Activity start")) {
            PendingActivityLaunch pal =
                    new PendingActivityLaunch(r, sourceRecord, startFlags, stack);
            // 将不允许切换的 activity 记录保存至带启动 activity 列表中。
            mPendingActivityLaunches.add(pal);
            ActivityOptions.abort(options);
            return ActivityManager.START_SWITCHES_CANCELED;
        }
    }

    if (mService.mDidAppSwitch) {
        // 这是我们禁止切换后第二次允许切换，所在现在一般只允许切换。
        // 例如：用户点击 home 键（禁止切换，切换到桌面，mDidAppSwitch 现在是 true）；
        // 用户点击一个 home 图标（来自 home 所以允许，我们点击它，那么现在允许任何人再次切换）。
        // 所以将允许启动的时间设为 0，那么上面的判断即可通过。
        mService.mAppSwitchesAllowedTime = 0;
    } else {
        mService.mDidAppSwitch = true;
    }

    // 启动等待启动列表里面的 activity。
    doPendingActivityLaunchesLocked(false);

    // 下一步启动。
    err = startActivityUncheckedLocked(r, sourceRecord, voiceSession, voiceInteractor,
            startFlags, true, options, inTask);

    if (err < 0) {
        // 如果有人要求在下一次 activity 开启时让键盘关闭，但我们实际上并没有进行
        // activity 切换……现在就关闭键盘，因为我们可能想看看它的后面不管它后面有什么。
        notifyActivityDrawnForKeyguard();
    }
    
    return err;
}
```

```java
// ActivityStackSupervisor.java

// 启动等待启动列表里面的 activity。
final void doPendingActivityLaunchesLocked(boolean doResume) {
    while (!mPendingActivityLaunches.isEmpty()) {
        // 清楚取出的待启动 activity。
        PendingActivityLaunch pal = mPendingActivityLaunches.remove(0);
        // 下一步启动，doResume 为 false，则延迟回调 onResume。
        startActivityUncheckedLocked(pal.r, pal.sourceRecord, null, null, pal.startFlags,
                doResume && mPendingActivityLaunches.isEmpty(), null, null);
    }
}
```

总结上面的 `startActivityLocked` 方法所做的工作如下：

1. 处理跳跃传递 result 的情况。

2. 检查是否具有相关组件和权限。
3. 创建 activity 记录对象。
4. 检查是否允许切换，处理待切换 activity。

下面将进入 `startActivityUncheckedLocked` 方法。

参数分析：

```java
r:ActivityRecord                      -> 启动目标 activity 记录。
sourceRecord:ActivityRecord           -> 调用者 activity 记录。
voiceSession:IVoiceInteractionSession -> 同上。
voiceInteractor:IVoiceInteractor      -> 同上。
startFlags:int                        -> 0。
doResume:boolean                      -> 是否回调 onResume。
options:Bundle                        -> 同上。
inTask:TaskRecord                     -> 同上。
```

```java
// ActivityStackSupervisor.java

final int startActivityUncheckedLocked(final ActivityRecord r, ActivityRecord sourceRecord,
        IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor, int startFlags,
        boolean doResume, Bundle options, TaskRecord inTask) {
    final Intent intent = r.intent;
    final int callingUid = r.launchedFromUid;

    // 在这个方法的一些流程中，我们检索任务的记录并且在没有 lock 的情况下保持它。
    // 然后再回到这里……所以此时的任务可能不在最近任务中，检查它，如果不在，则认为无效。
    if (inTask != null && !inTask.inRecents) {
        Slog.w(TAG, "Starting activity in task not in recents: " + inTask);
        inTask = null;
    }

    final boolean launchSingleTop = r.launchMode == ActivityInfo.LAUNCH_SINGLE_TOP;
    final boolean launchSingleInstance = r.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE;
    final boolean launchSingleTask = r.launchMode == ActivityInfo.LAUNCH_SINGLE_TASK;

    int launchFlags = intent.getFlags();
    if ((launchFlags & Intent.FLAG_ACTIVITY_NEW_DOCUMENT) != 0 &&
            (launchSingleInstance || launchSingleTask)) {
        // 如果清单设置的模式和 intent 冲突，则清单文件优先。
        Slog.i(TAG, "Ignoring FLAG_ACTIVITY_NEW_DOCUMENT, launchMode is " +
                "\"singleInstance\" or \"singleTask\"");
        launchFlags &=
                ~(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
    } else {
        switch (r.info.documentLaunchMode) {
            case ActivityInfo.DOCUMENT_LAUNCH_NONE:
                break;
            case ActivityInfo.DOCUMENT_LAUNCH_INTO_EXISTING:
                launchFlags |= Intent.FLAG_ACTIVITY_NEW_DOCUMENT;
                break;
            case ActivityInfo.DOCUMENT_LAUNCH_ALWAYS:
                launchFlags |= Intent.FLAG_ACTIVITY_NEW_DOCUMENT;
                break;
            case ActivityInfo.DOCUMENT_LAUNCH_NEVER:
                launchFlags &= ~Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
                break;
        }
    }

    final boolean launchTaskBehind = r.mLaunchTaskBehind
            && !launchSingleTask && !launchSingleInstance
            && (launchFlags & Intent.FLAG_ACTIVITY_NEW_DOCUMENT) != 0;

    if (r.resultTo != null && (launchFlags & Intent.FLAG_ACTIVITY_NEW_TASK) != 0
            && r.resultTo.task.stack != null) {
        // 不管是什么原因，此 activity 都被指定在一个新任务中启动……然而调用者已经请求返回结果。
        // 好吧，这是相当混乱的，所以立即发送一个 CANCEL，让新任务继续正常启动，而不依赖于其发起者。
        Slog.w(TAG, "Activity is launching as a new task, so cancelling activity result.");
        r.resultTo.task.stack.sendActivityResultLocked(-1,
                r.resultTo, r.resultWho, r.requestCode,
                Activity.RESULT_CANCELED, null);
        r.resultTo = null;
    }

    if ((launchFlags & Intent.FLAG_ACTIVITY_NEW_DOCUMENT) != 0 && r.resultTo == null) {
        launchFlags |= Intent.FLAG_ACTIVITY_NEW_TASK;
    }

    // 如果我们实际上要启动新的任务，在某些情况下我们还需要执行多任务。
    if ((launchFlags & Intent.FLAG_ACTIVITY_NEW_TASK) != 0) {
        if (launchTaskBehind
                || r.info.documentLaunchMode == ActivityInfo.DOCUMENT_LAUNCH_ALWAYS) {
            launchFlags |= Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
        }
    }

    // 只要 activity 没有明确声明这是自启动时，我们才会在 onPause 之前回调 onUserLeaving。
    mUserLeaving = (launchFlags & Intent.FLAG_ACTIVITY_NO_USER_ACTION) == 0;
    if (DEBUG_USER_LEAVING) Slog.v(TAG_USER_LEAVING,
            "startActivity() => mUserLeaving=" + mUserLeaving);

    // 如果调用者此时不要求回调 onResume，我们会在记录中记下来，以便我们在
    // 尝试查找最佳运行 activity 时跳过它。
    if (!doResume) {
        r.delayedResume = true;
    }

    ActivityRecord notTop =
            (launchFlags & Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP) != 0 ? r : null;

    // 如果设置了 onlyIfNeeded 标记，那么如果正在启动的 activity 与调用者 activity 相同，
    // 我们可以这样做……或者，作为一种特殊情况，如果我们不知道调用者，那么我们会
    // 计算出当前的顶部 activity 作为调用者。
    if ((startFlags&ActivityManager.START_FLAG_ONLY_IF_NEEDED) != 0) {
        ActivityRecord checkedCaller = sourceRecord;
        if (checkedCaller == null) {
            checkedCaller = mFocusedStack.topRunningNonDelayedActivityLocked(notTop);
        }
        if (!checkedCaller.realActivity.equals(r.realActivity)) {
            // 调用者与要启动的 activity 不相同时，因此总是需要此标记。
            startFlags &= ~ActivityManager.START_FLAG_ONLY_IF_NEEDED;
        }
    }

    boolean addingToTask = false;
    TaskRecord reuseTask = null;

    // 当调用者不是来自一个 activity，而是给了我们一个它们希望我们启动新 activity
    // 的明确所在任务，那么让我们看看该怎么做。
    if (sourceRecord == null && inTask != null && inTask.stack != null) {
        final Intent baseIntent = inTask.getBaseIntent();
        final ActivityRecord root = inTask.getRootActivity();
        if (baseIntent == null) {
            ActivityOptions.abort(options);
            throw new IllegalArgumentException("Launching into task without base intent: "
                    + inTask);
        }

        // 如果此任务为空，那么我们将添加第一个 activity，它来确定根元素，
        // 并且必须作为 NEW_TASK 启动。
        if (launchSingleInstance || launchSingleTask) {
            if (!baseIntent.getComponent().equals(r.intent.getComponent())) {
                ActivityOptions.abort(options);
                throw new IllegalArgumentException("Trying to launch singleInstance/Task "
                        + r + " into different task " + inTask);
            }
            if (root != null) {
                ActivityOptions.abort(options);
                throw new IllegalArgumentException("Caller with inTask " + inTask
                        + " has root " + root + " but target is singleInstance/Task");
            }
        }

        // 如果任务为空，则在正在启动的 activity 上采用感兴趣的 intent 标记。
        if (root == null) {
            final int flagsOfInterest = Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK | Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                    | Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS;
            launchFlags = (launchFlags&~flagsOfInterest)
                    | (baseIntent.getFlags()&flagsOfInterest);
            intent.setFlags(launchFlags);
            inTask.setIntent(r);
            addingToTask = true;

        // 如果任务不为空且调用者要求其作为新任务的根启动，那么我们实际上并不想在任务上
        // 启动它。我们将把任务放在前面，并可能给它一个新的 intent。
        } else if ((launchFlags & Intent.FLAG_ACTIVITY_NEW_TASK) != 0) {
            addingToTask = false;

        } else {
            addingToTask = true;
        }

        reuseTask = inTask;
    } else {
        inTask = null;
    }

    if (inTask == null) {
        if (sourceRecord == null) {
            // 这个 activity 不是从一个 activity 启动的……这种情况下，
            // 我们 -总是- 开启一个新任务。
            if ((launchFlags & Intent.FLAG_ACTIVITY_NEW_TASK) == 0 && inTask == null) {
                Slog.w(TAG, "startActivity called from non-Activity context; forcing " +
                        "Intent.FLAG_ACTIVITY_NEW_TASK for: " + intent);
                launchFlags |= Intent.FLAG_ACTIVITY_NEW_TASK;
            }
        } else if (sourceRecord.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE) {
            // 启动我们的原始 activity 是作为一个单实例模式运行的……它正在启动的这个新的
            // activity 必须继续它自己的任务。
            launchFlags |= Intent.FLAG_ACTIVITY_NEW_TASK;
        } else if (launchSingleInstance || launchSingleTask) {
            // 正在启动的 activity 是一个单例实例……它总是被启动到自己的任务中。
            launchFlags |= Intent.FLAG_ACTIVITY_NEW_TASK;
        }
    }

    ActivityInfo newTaskInfo = null;
    Intent newTaskIntent = null;
    ActivityStack sourceStack;
    if (sourceRecord != null) {
        if (sourceRecord.finishing) {
            // 如果源 activity 正在 finish，我们无法进一步将它视为我们的的源。这是因为
            // 与之关联的任务现在可能是空的并且正在被移除，所以我们不想盲目的将它投入到该
            // 任务中，相反，我们会采取 NEW_TASK 流程并尝试为它找到任务。
            // 但保存任务信息，以便在创建新任务时使用它。
            if ((launchFlags & Intent.FLAG_ACTIVITY_NEW_TASK) == 0) {
                Slog.w(TAG, "startActivity called from finishing " + sourceRecord
                        + "; forcing " + "Intent.FLAG_ACTIVITY_NEW_TASK for: " + intent);
                launchFlags |= Intent.FLAG_ACTIVITY_NEW_TASK;
                newTaskInfo = sourceRecord.info;
                newTaskIntent = sourceRecord.task.intent;
            }
            sourceRecord = null;
            sourceStack = null;
        } else {
            sourceStack = sourceRecord.task.stack;
        }
    } else {
        sourceStack = null;
    }

    boolean movedHome = false;
    ActivityStack targetStack;

    intent.setFlags(launchFlags);
    final boolean noAnimation = (launchFlags & Intent.FLAG_ACTIVITY_NO_ANIMATION) != 0;

    // 我们可能尝试希望将新的 activity 放入现有任务中。如果目标活动是 singleTask 或
    // singleInstance，我们总是这样做；如果已经请求了 NEW_TASK，我们也会这样做，
    // 并且没有额外的限定符告诉我们仍然将它放在一个新任务中：多任务，总是 DOC 模式，或
    // 被要求将其作为当前的一个新任务启动。
    if (((launchFlags & Intent.FLAG_ACTIVITY_NEW_TASK) != 0 &&
            (launchFlags & Intent.FLAG_ACTIVITY_MULTIPLE_TASK) == 0)
            || launchSingleInstance || launchSingleTask) {
        // 如果请求将任务前置，并且没有请求结果，并且我们没有得到要启动的明确的任务，
        // 我们可以找到一个用这个相同的组件启动的任务，然后把它前置而不是启动。
        if (inTask == null && r.resultTo == null) {
            // 看看是否有任务要前置。如果这是一个 singleInstance activity，则历史记录中
            // 只能有一个实例，并且它始终位于其自己的唯一任务中，因此我们进行特殊搜索。
            ActivityRecord intentActivity = !launchSingleInstance ?
                    findTaskLocked(r) : findActivityLocked(intent, r.info);
            if (intentActivity != null) {
                // When the flags NEW_TASK and CLEAR_TASK are set, then the task gets reused
                // but still needs to be a lock task mode violation since the task gets
                // cleared out and the device would otherwise leave the locked task.
                if (isLockTaskModeViolation(intentActivity.task,
                        (launchFlags & (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK))
                        == (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK))) {
                    showLockTaskToast();
                    Slog.e(TAG, "startActivityUnchecked: Attempt to violate Lock Task Mode");
                    return ActivityManager.START_RETURN_LOCK_TASK_MODE_VIOLATION;
                }
                if (r.task == null) {
                    r.task = intentActivity.task;
                }
                if (intentActivity.task.intent == null) {
                    // 这项任务的开启是因为基于亲和栈的 activity 的移动……现在
                    // 我们实际上正在启动它，我们可以分配 base intent。
                    intentActivity.task.setIntent(r);
                }
                targetStack = intentActivity.task.stack;
                targetStack.mLastPausedActivity = null;
                // 如果目标任务不在前面，那么我们需要把它前置……除了……好吧，
                // SINGLE_TASK_LAUNCH 模式并不清楚。我们希望具有与启动新实例
                // 相同的行为，这意味着如果调用者本身不在前面，就不会将其前置。
                final ActivityStack focusStack = getFocusedStack();
                ActivityRecord curTop = (focusStack == null)
                      ? null : focusStack.topRunningNonDelayedActivityLocked(notTop);
                boolean movedToFront = false;
                if (curTop != null && (curTop.task != intentActivity.task ||
                        curTop.task != focusStack.topTask())) {
                    r.intent.addFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT);
                    if (sourceRecord == null || (sourceStack.topActivity() != null &&
                            sourceStack.topActivity().task == sourceRecord.task)) {
                        // 我们现在真的立刻想把这个推到用户面前。
                        if (launchTaskBehind && sourceRecord != null) {
                            intentActivity.setTaskToAffiliateWith(sourceRecord.task);
                        }
                        movedHome = true;
                        targetStack.moveTaskToFrontLocked(intentActivity.task, noAnimation,
                                options, r.appTimeTracker, "bringingFoundTaskToFront");
                        movedToFront = true;
                        if ((launchFlags &
                                (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_TASK_ON_HOME))
                                == (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_TASK_ON_HOME)) {
                            // 调用者想出现在 home activity 中。
                            intentActivity.task.setTaskToReturnTo(HOME_ACTIVITY_TYPE);
                        }
                        options = null;
                    }
                }
                if (!movedToFront) {
                    if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Bring to front target: " + targetStack
                            + " from " + intentActivity);
                    targetStack.moveToFront("intentActivityFound");
                }

                // 如果调用者已请求重置目标任务，则执行此操作。
                if ((launchFlags&Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) != 0) {
                    intentActivity = targetStack.resetTaskIfNeededLocked(intentActivity, r);
                }
                if ((startFlags & ActivityManager.START_FLAG_ONLY_IF_NEEDED) != 0) {
                    // 我们不需要启动新的 activity，客户端说如果这样就什么都不要做。
                    // 就是这样！对于偏执者，确保我们已经正确的 resume 了最顶部的 activity。
                    if (doResume) {
                        resumeTopActivitiesLocked(targetStack, null, options);

                        // 如果我们之后没有执行应用切换，请务必通知 Keyguard。
                        if (!movedToFront) {
                            notifyActivityDrawnForKeyguard();
                        }
                    } else {
                        ActivityOptions.abort(options);
                    }
                    return ActivityManager.START_RETURN_INTENT_TO_CALLER;
                }
                if ((launchFlags & (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK))
                        == (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK)) {
                    // 调用者已请求使用其新 activity 完全替换任何现有任务。那不应该太难……
                    reuseTask = intentActivity.task;
                    reuseTask.performClearTaskLocked();
                    reuseTask.setIntent(r);
                } else if ((launchFlags & FLAG_ACTIVITY_CLEAR_TOP) != 0
                        || launchSingleInstance || launchSingleTask) {
                    // 在这种情况下，我们希望从任务中删除所有的 activity，直到有一个正在
                    // 启动的活动。在大多数情况下，这意味着我们将任务重置为其初始状态。
                    ActivityRecord top =
                            intentActivity.task.performClearTaskLocked(r, launchFlags);
                    if (top != null) {
                        if (top.frontOfTask) {
                            // activity 的别名可能意味着我们对顶部 activity 使用不同的
                            // intent，因此请确保该任务现在具有新 intent 的标记。
                            top.task.setIntent(r);
                        }
                        ActivityStack.logStartActivity(EventLogTags.AM_NEW_INTENT,
                                r, top.task);
                        top.deliverNewIntentLocked(callingUid, r.intent, r.launchedFromPackage);
                    } else {
                        // 一个特例，我们需要启动新 activity，因为它现在没有运行，并且
                        // 调用者要求清楚当前任务以将此 activity 至于顶部。
                        addingToTask = true;
                        // 现在假设这个 activity 正在其任务的顶部启动，因此它被放在正确的位置。
                        sourceRecord = intentActivity;
                        TaskRecord task = sourceRecord.task;
                        if (task != null && task.stack == null) {
                            // 当我们上面删除所有 activity 时，目标栈被清空。继续并重置它。
                            targetStack = computeStackFocus(sourceRecord, false /* newTask */);
                            targetStack.addTask(
                                    task, !launchTaskBehind /* toTop */, false /* moving */);
                        }

                    }
                } else if (r.realActivity.equals(intentActivity.task.realActivity)) {
                    // 在这种情况下，任务的顶层 activity 与正在启动的 activity 相同，
                    // 因此我们将其作为将任务前置的请求。
                    // 如果任务中的顶层 activity 是根 activity，则根据需要将此新 intent 传递给它
                    if (((launchFlags&Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0 || launchSingleTop)
                            && intentActivity.realActivity.equals(r.realActivity)) {
                        ActivityStack.logStartActivity(EventLogTags.AM_NEW_INTENT, r,
                                intentActivity.task);
                        if (intentActivity.frontOfTask) {
                            intentActivity.task.setIntent(r);
                        }
                        intentActivity.deliverNewIntentLocked(callingUid, r.intent,
                                r.launchedFromPackage);
                    } else if (!r.intent.filterEquals(intentActivity.task.intent)) {
                        // 在这种情况下，我们将启动新任务的根 activity，但具有不同的 intent。
                        // 我们应该在顶部开始一个新实例。
                        addingToTask = true;
                        sourceRecord = intentActivity;
                    }
                } else if ((launchFlags&Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) == 0) {
                    // 在这种情况下，正在将 activity 启动到现有任务中，而不重置该任务。
                    // 这通常是从通知或快捷方式启动 activity 的情况。
                    // 我们希望将新 activity 置于当前任务之上。
                    addingToTask = true;
                    sourceRecord = intentActivity;
                } else if (!intentActivity.task.rootWasReset) {
                    // 在这种情况下，我们将启动尚未从其前门启动的现有任务。
                    // 当前的任务已被前置。理想情况下，我们可能希望将这个任务放在其堆栈的底部。
                    // 但这对于当前的代码组织来说有点难以实现，所以我们只需删除它。
                    intentActivity.task.setIntent(r);
                }
                if (!addingToTask && reuseTask == null) {
                    // 我们没做任务事情……但是需要它（a.k.a，客户端不要使用那个 intent!）对于偏执者，
                    // 请确保我们已经正确地 resume 了顶层 activity。
                    if (doResume) {
                        targetStack.resumeTopActivityLocked(null, options);
                        if (!movedToFront) {
                            // 如果我们之后没有执行应用切换，请务必通知 Keyguard。
                            notifyActivityDrawnForKeyguard();
                        }
                    } else {
                        ActivityOptions.abort(options);
                    }
                    return ActivityManager.START_TASK_TO_FRONT;
                }
            }
        }
    }

    if (r.packageName != null) {
        // 如果正在启动的 activity 与当前在顶部的 activity 相同，
        // 那么我们需要检查它是否应该只启动一次。
        ActivityStack topStack = mFocusedStack;
        ActivityRecord top = topStack.topRunningNonDelayedActivityLocked(notTop);
        if (top != null && r.resultTo == null) {
            if (top.realActivity.equals(r.realActivity) && top.userId == r.userId) {
                if (top.app != null && top.app.thread != null) {
                    if ((launchFlags & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0
                        || launchSingleTop || launchSingleTask) {
                        ActivityStack.logStartActivity(EventLogTags.AM_NEW_INTENT, top,
                                top.task);
                        // 对于偏执者，请确保我们已经正确地 resume 了顶层 activity。
                        topStack.mLastPausedActivity = null;
                        if (doResume) {
                            resumeTopActivitiesLocked();
                        }
                        ActivityOptions.abort(options);
                        if ((startFlags&ActivityManager.START_FLAG_ONLY_IF_NEEDED) != 0) {
                            // 我们不需要启动新的 activity，客户端说如果这样就什么都不要做。就是这样！
                            return ActivityManager.START_RETURN_INTENT_TO_CALLER;
                        }
                        top.deliverNewIntentLocked(callingUid, r.intent, r.launchedFromPackage);
                        return ActivityManager.START_DELIVERED_TO_TOP;
                    }
                }
            }
        }

    } else {
        if (r.resultTo != null && r.resultTo.task.stack != null) {
            r.resultTo.task.stack.sendActivityResultLocked(-1, r.resultTo, r.resultWho,
                    r.requestCode, Activity.RESULT_CANCELED, null);
        }
        ActivityOptions.abort(options);
        return ActivityManager.START_CLASS_NOT_FOUND;
    }

    boolean newTask = false;
    boolean keepCurTransition = false;

    TaskRecord taskToAffiliate = launchTaskBehind && sourceRecord != null ?
            sourceRecord.task : null;

    // 这应该被视为一个新任务吗？
    if (r.resultTo == null && inTask == null && !addingToTask
            && (launchFlags & Intent.FLAG_ACTIVITY_NEW_TASK) != 0) {
        newTask = true;
        targetStack = computeStackFocus(r, newTask);
        targetStack.moveToFront("startingNewTask");

        if (reuseTask == null) {
            r.setTask(targetStack.createTaskRecord(getNextTaskId(),
                    newTaskInfo != null ? newTaskInfo : r.info,
                    newTaskIntent != null ? newTaskIntent : intent,
                    voiceSession, voiceInteractor, !launchTaskBehind /* toTop */),
                    taskToAffiliate);
            if (DEBUG_TASKS) Slog.v(TAG_TASKS,
                    "Starting new activity " + r + " in new task " + r.task);
        } else {
            r.setTask(reuseTask, taskToAffiliate);
        }
        if (isLockTaskModeViolation(r.task)) {
            Slog.e(TAG, "Attempted Lock Task Mode violation r=" + r);
            return ActivityManager.START_RETURN_LOCK_TASK_MODE_VIOLATION;
        }
        if (!movedHome) {
            if ((launchFlags &
                    (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_TASK_ON_HOME))
                    == (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_TASK_ON_HOME)) {
                // 调用者希望出现在 home activity 中，所以在启动
                // 它们自己的 activity 之前，我们将 home 前置。
                r.task.setTaskToReturnTo(HOME_ACTIVITY_TYPE);
            }
        }
    } else if (sourceRecord != null) {
        final TaskRecord sourceTask = sourceRecord.task;
        if (isLockTaskModeViolation(sourceTask)) {
            Slog.e(TAG, "Attempted Lock Task Mode violation r=" + r);
            return ActivityManager.START_RETURN_LOCK_TASK_MODE_VIOLATION;
        }
        targetStack = sourceTask.stack;
        targetStack.moveToFront("sourceStackToFront");
        final TaskRecord topTask = targetStack.topTask();
        if (topTask != sourceTask) {
            targetStack.moveTaskToFrontLocked(sourceTask, noAnimation, options,
                    r.appTimeTracker, "sourceTaskToFront");
        }
        if (!addingToTask && (launchFlags&Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0) {
            // 在这种情况下，我们将 activity 添加到现有任务中，但是如果
            // activity 已在运行，则调用者要求清除该任务。
            ActivityRecord top = sourceTask.performClearTaskLocked(r, launchFlags);
            keepCurTransition = true;
            if (top != null) {
                ActivityStack.logStartActivity(EventLogTags.AM_NEW_INTENT, r, top.task);
                top.deliverNewIntentLocked(callingUid, r.intent, r.launchedFromPackage);
                // 对于偏执者，请确保我们已经正确地 resume 了顶层 activity。
                targetStack.mLastPausedActivity = null;
                if (doResume) {
                    targetStack.resumeTopActivityLocked(null);
                }
                ActivityOptions.abort(options);
                return ActivityManager.START_DELIVERED_TO_TOP;
            }
        } else if (!addingToTask &&
                (launchFlags&Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) != 0) {
            // 在这种情况下，在我们自己的任务中运行一个 activity，该 activity 可能
            // 已经在历史中的某个地方运行，如果是这样，我们想将它前置。
            final ActivityRecord top = sourceTask.findActivityInHistoryLocked(r);
            if (top != null) {
                final TaskRecord task = top.task;
                task.moveActivityToFrontLocked(top);
                ActivityStack.logStartActivity(EventLogTags.AM_NEW_INTENT, r, task);
                top.updateOptionsLocked(options);
                top.deliverNewIntentLocked(callingUid, r.intent, r.launchedFromPackage);
                targetStack.mLastPausedActivity = null;
                if (doResume) {
                    targetStack.resumeTopActivityLocked(null);
                }
                return ActivityManager.START_DELIVERED_TO_TOP;
            }
        }
        // 一个已经存在的 activity 正在启动这个新 activity，
        // 因此我们希望将新 activity 保持在和启动它相同的任务中。
        r.setTask(sourceTask, null);
        if (DEBUG_TASKS) Slog.v(TAG_TASKS, "Starting new activity " + r
                + " in existing task " + r.task + " from source " + sourceRecord);

    } else if (inTask != null) {
        // 调用者要求新 activity 在它提供给我们的明确任务中启动。
        if (isLockTaskModeViolation(inTask)) {
            Slog.e(TAG, "Attempted Lock Task Mode violation r=" + r);
            return ActivityManager.START_RETURN_LOCK_TASK_MODE_VIOLATION;
        }
        targetStack = inTask.stack;
        targetStack.moveTaskToFrontLocked(inTask, noAnimation, options, r.appTimeTracker,
                "inTaskToFront");

        // 检查我们是否应该将新 activity 实际启动到任务中，或只是重复使用当前 activity。
        ActivityRecord top = inTask.getTopActivity();
        if (top != null && top.realActivity.equals(r.realActivity) && top.userId == r.userId) {
            if ((launchFlags & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0
                    || launchSingleTop || launchSingleTask) {
                ActivityStack.logStartActivity(EventLogTags.AM_NEW_INTENT, top, top.task);
                if ((startFlags&ActivityManager.START_FLAG_ONLY_IF_NEEDED) != 0) {
                    // 我们不需要启动新的 activity，客户端说如果这样就什么都不要做。就是这样！
                    return ActivityManager.START_RETURN_INTENT_TO_CALLER;
                }
                top.deliverNewIntentLocked(callingUid, r.intent, r.launchedFromPackage);
                return ActivityManager.START_DELIVERED_TO_TOP;
            }
        }

        if (!addingToTask) {
            // 我们实际上并不想将此 activity 添加到任务中，所以只需停在此处，
            // 但仍然告诉调用者我们消费了 intent。
            ActivityOptions.abort(options);
            return ActivityManager.START_TASK_TO_FRONT;
        }

        r.setTask(inTask, null);
        if (DEBUG_TASKS) Slog.v(TAG_TASKS, "Starting new activity " + r
                + " in explicit task " + r.task);

    } else {
        // 这不是从现有 activity 开始，也不是新任务的一部分……只是
        // 把它放在最顶层的任务中，尽管现在这种情况不应该发生。
        targetStack = computeStackFocus(r, newTask);
        targetStack.moveToFront("addingToTopTask");
        ActivityRecord prev = targetStack.topActivity();
        r.setTask(prev != null ? prev.task : targetStack.createTaskRecord(getNextTaskId(),
                        r.info, intent, null, null, true), null);
        mWindowManager.moveTaskToTop(r.task.taskId);
        if (DEBUG_TASKS) Slog.v(TAG_TASKS, "Starting new activity " + r
                + " in new guessed " + r.task);
    }

    mService.grantUriPermissionFromIntentLocked(callingUid, r.packageName,
            intent, r.getUriPermissionsLocked(), r.userId);

    if (sourceRecord != null && sourceRecord.isRecentsActivity()) {
        r.task.setTaskToReturnTo(RECENTS_ACTIVITY_TYPE);
    }
    if (newTask) {
        EventLog.writeEvent(EventLogTags.AM_CREATE_TASK, r.userId, r.task.taskId);
    }
    ActivityStack.logStartActivity(EventLogTags.AM_CREATE_ACTIVITY, r, r.task);
    targetStack.mLastPausedActivity = null;
    // 下一步启动 activity。
    targetStack.startActivityLocked(r, newTask, doResume, keepCurTransition, options);
    if (!launchTaskBehind) {
        // 不要把焦点集中在后面的 activity 上。
        mService.setFocusedActivityLocked(r, "startedActivity");
    }
    return ActivityManager.START_SUCCESS;
}
```

这个方法根据 Intent 结合清单文件中设置的 Activity 启动模式计算最终的启动标记，并根据启动标记为 activity 调整当前已存在的 activity 任务，如果需要则创建新的任务对象。上面的注释根据源码注释翻译。

接下来是 `targetStack` 的 `startActivityLocked` 方法，`targetStack` 是 `ActivityStack` 类型，它表示具体的一个 activity 栈，而 `ActivityStackSupervisor` 是 activity 栈的管理者。

#### ActivityStack

```java
// ActivtiyStack.java

final void startActivityLocked(ActivityRecord r, boolean newTask,
        boolean doResume, boolean keepCurTransition, Bundle options) {
    TaskRecord rTask = r.task;
    final int taskId = rTask.taskId;
    // 被 mLaunchTaskBehind 标记任务放置在 activity 任务栈的后面。
    if (!r.mLaunchTaskBehind && (taskForIdLocked(taskId) == null || newTask)) {
        // 任务中的上一个 activity 已被删除或 AMS 正在重用该任务。
        // 插入或替换。
        // 可能不在。
        insertTaskAtTop(rTask, r);
        // 移动至栈顶。
        mWindowManager.moveTaskToTop(taskId);
    }
    TaskRecord task = null;
    if (!newTask) {
        // 如果从已存在的任务开始，那么找到它的索引。
        boolean startIt = true;
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            task = mTaskHistory.get(taskNdx);
            if (task.getTopActivity() == null) {
                // 任务中所有的 activity 都在 finish。
                continue;
            }
            if (task == r.task) {
                // 这里！现在，如果用户还没看到它，那么只需添加它而不启动;
                // 它会在用户导航回来时启动。
                if (!startIt) {
                    if (DEBUG_ADD_REMOVE) Slog.i(TAG, "Adding activity " + r + " to task "
                            + task, new RuntimeException("here").fillInStackTrace());
                    task.addActivityToTop(r);
                    r.putInHistory();
                    mWindowManager.addAppToken(task.mActivities.indexOf(r), r.appToken,
                            r.task.taskId, mStackId, r.info.screenOrientation, r.fullscreen,
                            (r.info.flags & ActivityInfo.FLAG_SHOW_FOR_ALL_USERS) != 0,
                            r.userId, r.info.configChanges, task.voiceSession != null,
                            r.mLaunchTaskBehind);
                    if (VALIDATE_TOKENS) {
                        validateAppTokensLocked();
                    }
                    ActivityOptions.abort(options);
                    return;
                }
                break;
            } else if (task.numFullscreen > 0) {
                startIt = false;
            }
        }
    }

    // 将新的 activity 放在任务栈顶部，以便接下来与用户进行交互。

    // 如果我们不将新的 activity 放在最前面，
    // 我们不希望将 onUserLeaving 回调给实际的最前面的 activity。
    if (task == r.task && mTaskHistory.indexOf(task) != (mTaskHistory.size() - 1)) {
        mStackSupervisor.mUserLeaving = false;
        if (DEBUG_USER_LEAVING) Slog.v(TAG_USER_LEAVING,
                "startActivity() behind front, mUserLeaving=false");
    }

    task = r.task;

    // 将 activity 添加到历史任务栈中并继续。
    if (DEBUG_ADD_REMOVE) Slog.i(TAG, "Adding activity " + r + " to stack to task " + task,
            new RuntimeException("here").fillInStackTrace());
    task.addActivityToTop(r);
    task.setFrontOfTask();

    r.putInHistory();
    if (!isHomeStack() || numActivities() > 0) {
        // 如果我们要切换到新任务，或者下一个 activity 所在进程没有运行。
        // 我们希望展示 starting preview 窗口。
        boolean showStartingIcon = newTask;
        ProcessRecord proc = r.app;
        if (proc == null) {
            proc = mService.mProcessNames.get(r.processName, r.info.applicationInfo.uid);
        }
        if (proc == null || proc.thread == null) {
            showStartingIcon = true;
        }
        if (DEBUG_TRANSITION) Slog.v(TAG_TRANSITION,
                "Prepare open transition: starting " + r);
        if ((r.intent.getFlags() & Intent.FLAG_ACTIVITY_NO_ANIMATION) != 0) {
            mWindowManager.prepareAppTransition(AppTransition.TRANSIT_NONE, keepCurTransition);
            mNoAnimActivities.add(r);
        } else {
            mWindowManager.prepareAppTransition(newTask
                    ? r.mLaunchTaskBehind
                            ? AppTransition.TRANSIT_TASK_OPEN_BEHIND
                            : AppTransition.TRANSIT_TASK_OPEN
                    : AppTransition.TRANSIT_ACTIVITY_OPEN, keepCurTransition);
            mNoAnimActivities.remove(r);
        }
        mWindowManager.addAppToken(task.mActivities.indexOf(r),
                r.appToken, r.task.taskId, mStackId, r.info.screenOrientation, r.fullscreen,
                (r.info.flags & ActivityInfo.FLAG_SHOW_FOR_ALL_USERS) != 0, r.userId,
                r.info.configChanges, task.voiceSession != null, r.mLaunchTaskBehind);
        boolean doShow = true;
        if (newTask) {
            // 即使这个 activity 刚刚开始，我们仍然需要重置它，以确保我们应用的亲和栈关系
            // 将任何现有 activity 从其他任务转移到里面。如果调用者已请求重置目标任务，则执行此操作。
            if ((r.intent.getFlags() & Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) != 0) {
                resetTaskIfNeededLocked(r, r);
                doShow = topRunningNonDelayedActivityLocked(null) == r;
            }
        } else if (options != null && new ActivityOptions(options).getAnimationType()
                == ActivityOptions.ANIM_SCENE_TRANSITION) {
            doShow = false;
        }
        if (r.mLaunchTaskBehind) {
            // 不要为 mLaunchTaskBehind 启动窗口。更重要的是要确保我们告诉
            // WindowManager 及时它位于任务栈的后面，r 也是可见的。
            mWindowManager.setAppVisibility(r.appToken, true);
            ensureActivitiesVisibleLocked(null, 0);
        } else if (SHOW_APP_STARTING_PREVIEW && doShow) {
            // 了解清楚我们是否正在从另一个与下一个 activity "具有相同的启动图标"的 activity
            // 进行转换。这允许 WindowManager 保留它先前创建的窗口（如果仍有）。
            ActivityRecord prev = mResumedActivity;
            if (prev != null) {
                // 如果出现以下情况，我们不想重复使用之前的 starting preview。
                // (1) 当前 activity 在一个不同的任务中。
                if (prev.task != r.task) {
                    prev = null;
                }
                // (2) 当前 activity 已经显示。
                else if (prev.nowVisible) {
                    prev = null;
                }
            }
            mWindowManager.setAppStartingWindow(
                    r.appToken, r.packageName, r.theme,
                    mService.compatibilityInfoForPackageLocked(
                            r.info.applicationInfo), r.nonLocalizedLabel,
                    r.labelRes, r.icon, r.logo, r.windowFlags,
                    prev != null ? prev.appToken : null, showStartingIcon);
            r.mStartingWindowShown = true;
        }
    } else {
        // 如果这是第一个 activity，请不要做任何花哨的动画，
        // 因为没有任何东西可以在它上面做动画。
        mWindowManager.addAppToken(task.mActivities.indexOf(r), r.appToken,
                r.task.taskId, mStackId, r.info.screenOrientation, r.fullscreen,
                (r.info.flags & ActivityInfo.FLAG_SHOW_FOR_ALL_USERS) != 0, r.userId,
                r.info.configChanges, task.voiceSession != null, r.mLaunchTaskBehind);
        ActivityOptions.abort(options);
        options = null;
    }
    if (VALIDATE_TOKENS) {
        validateAppTokensLocked();
    }

    if (doResume) {
        // 执行 activity resume。
        mStackSupervisor.resumeTopActivitiesLocked(this, r, options);
    }
}
```

可以看到有很多和 `WindowManager` 交互的逻辑，主要是根据前面设置的 activity 任务栈，调整用户界面上相关的任务的展示。下面调用了 `ActivityStackSupervisor` 的 `resumeTopActivitiesLocked` 方法，开始 resume 顶层的前面刚启动的 activity。

#### ActivityStackSupervisor

```java
// ActivityStackSupervisor.java

boolean resumeTopActivitiesLocked(ActivityStack targetStack, ActivityRecord target,
        Bundle targetOptions) {
    if (targetStack == null) {
        targetStack = mFocusedStack;
    }
    // 首先处理 targetStack.
    boolean result = false;
    if (isFrontStack(targetStack)) {
        // 下一步分析。
        result = targetStack.resumeTopActivityLocked(target, targetOptions);
    }

    for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
        final ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
        for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = stacks.get(stackNdx);
            if (stack == targetStack) {
                // 上面已经启动过。
                continue;
            }
            if (isFrontStack(stack)) {
                stack.resumeTopActivityLocked(null);
            }
        }
    }
    return result;
}
```

#### ActivityStack

```java
// ActivityStack.java

final boolean resumeTopActivityLocked(ActivityRecord prev, Bundle options) {
    if (mStackSupervisor.inResumeTopActivity) {
        // 不要递归启动。
        return false;
    }

    boolean result = false;
    try {
        // 防止递归。
        mStackSupervisor.inResumeTopActivity = true;
        if (mService.mLockScreenShown == ActivityManagerService.LOCK_SCREEN_LEAVING) {
            mService.mLockScreenShown = ActivityManagerService.LOCK_SCREEN_HIDDEN;
            mService.updateSleepIfNeededLocked();
        }
        // 下一个方法。
        result = resumeTopActivityInnerLocked(prev, options);
    } finally {
        mStackSupervisor.inResumeTopActivity = false;
    }
    return result;
}

```

```java
// ActivityStack.java

private boolean resumeTopActivityInnerLocked(ActivityRecord prev, Bundle options) {
    if (DEBUG_LOCKSCREEN) mService.logLockScreen("");

    if (!mService.mBooting && !mService.mBooted) {
        // 还没有准备好。
        return false;
    }

    ActivityRecord parent = mActivityContainer.mParentActivity;
    if ((parent != null && parent.state != ActivityState.RESUMED) ||
            !mActivityContainer.isAttachedLocked()) {
        return false;
    }

    cancelInitializingActivities();

    // 从栈顶找到第一个没有 finish 的 activity。
    final ActivityRecord next = topRunningActivityLocked(null);

    // 记住我们将如何处理这种 pause/resume 情况，并确保重置状态。
    // 但我们最终会继续进行。
    final boolean userLeaving = mStackSupervisor.mUserLeaving;
    mStackSupervisor.mUserLeaving = false;

    final TaskRecord prevTask = prev != null ? prev.task : null;
    if (next == null) {
        // 没有更多的 actviity 了。
        final String reason = "noMoreActivities";
        if (!mFullscreen) {
            // 如果此任务栈没有覆盖整个屏幕，则将焦点移动到具有活动的
            // activity 的下一个可见任务栈。
            final ActivityStack stack = getNextVisibleStackLocked();
            if (adjustFocusToNextVisibleStackLocked(stack, reason)) {
                return mStackSupervisor.resumeTopActivitiesLocked(stack, prev, null);
            }
        }
        // 让我们启动 Launcher……
        ActivityOptions.abort(options);
        if (DEBUG_STATES) Slog.d(TAG_STATES,
                "resumeTopActivityLocked: No more activities go home");
        if (DEBUG_STACK) mStackSupervisor.validateTopActivitiesLocked();
        // 只有在桌面显示时才能 resume 桌面。
        final int returnTaskType = prevTask == null || !prevTask.isOverHomeStack() ?
                HOME_ACTIVITY_TYPE : prevTask.getTaskToReturnTo();
        return isOnHomeDisplay() &&
                mStackSupervisor.resumeHomeStackTask(returnTaskType, prev, reason);
    }

    next.delayedResume = false;

    // 如果顶层 activity 是要 resume 的，那么什么都不做。
    if (mResumedActivity == next && next.state == ActivityState.RESUMED &&
                mStackSupervisor.allResumedActivitiesComplete()) {
        // 确保我们已经执行了任何等待的切换。因为此时应该没有什么可做的了。
        mWindowManager.executeAppTransition();
        mNoAnimActivities.clear();
        ActivityOptions.abort(options);
        if (DEBUG_STATES) Slog.d(TAG_STATES,
                "resumeTopActivityLocked: Top activity resumed " + next);
        if (DEBUG_STACK) mStackSupervisor.validateTopActivitiesLocked();
        return false;
    }

    final TaskRecord nextTask = next.task;
    if (prevTask != null && prevTask.stack == this &&
            prevTask.isOverHomeStack() && prev.finishing && prev.frontOfTask) {
        if (DEBUG_STACK)  mStackSupervisor.validateTopActivitiesLocked();
        if (prevTask == nextTask) {
            prevTask.setFrontOfTask();
        } else if (prevTask != topTask()) {
            // 这个任务正在离开，但他应该返回到 home 栈。现在上面的任务必须返回到 home 任务。
            final int taskNdx = mTaskHistory.indexOf(prevTask) + 1;
            mTaskHistory.get(taskNdx).setTaskToReturnTo(HOME_ACTIVITY_TYPE);
        } else if (!isOnHomeDisplay()) {
            return false;
        } else if (!isHomeStack()){
            if (DEBUG_STATES) Slog.d(TAG_STATES,
                    "resumeTopActivityLocked: Launching home next");
            final int returnTaskType = prevTask == null || !prevTask.isOverHomeStack() ?
                    HOME_ACTIVITY_TYPE : prevTask.getTaskToReturnTo();
            return isOnHomeDisplay() &&
                    mStackSupervisor.resumeHomeStackTask(returnTaskType, prev, "prevFinished");
        }
    }

    // 如果我们正在休眠，并且没有 resume 的 activity，并且顶层 activity 处于
    // pause 状态。那么这就是我们想要的状态。
    if (mService.isSleepingOrShuttingDown()
            && mLastPausedActivity == next
            && mStackSupervisor.allPausedActivitiesComplete()) {
        // 确保我们已经执行了任何等待的切换。因为此时应该没有什么可做的了。
        mWindowManager.executeAppTransition();
        mNoAnimActivities.clear();
        ActivityOptions.abort(options);
        if (DEBUG_STATES) Slog.d(TAG_STATES,
                "resumeTopActivityLocked: Going to sleep and all paused");
        if (DEBUG_STACK) mStackSupervisor.validateTopActivitiesLocked();
        return false;
    }

    // 确保已启动拥有此 activity 的用户，如果没有，我们将保持现状，
    // 因为有人应该将另一个用户的 activity 带到任务栈顶。
    if (mService.mStartedUsers.get(next.userId) == null) {
        Slog.w(TAG, "Skipping resume of top activity " + next
                + ": user " + next.userId + " is stopped");
        if (DEBUG_STACK) mStackSupervisor.validateTopActivitiesLocked();
        return false;
    }

    // activity 可能正在等待 stop，但这不再适用于它了。
    mStackSupervisor.mStoppingActivities.remove(next);
    mStackSupervisor.mGoingToSleepActivities.remove(next);
    next.sleeping = false;
    mStackSupervisor.mWaitingVisibleActivities.remove(next);

    if (DEBUG_SWITCH) Slog.v(TAG_SWITCH, "Resuming " + next);

    // 如果我们当前正在 pause activity，那么完成之前不要做任何事。
    if (!mStackSupervisor.allPausedActivitiesComplete()) {
        if (DEBUG_SWITCH || DEBUG_PAUSE || DEBUG_STATES) Slog.v(TAG_PAUSE,
                "resumeTopActivityLocked: Skip resume: some activity pausing.");
        if (DEBUG_STACK) mStackSupervisor.validateTopActivitiesLocked();
        return false;
    }

    // Ok 我们现在要开始切换到“next”，我们可能首先必须 pause 当前的 activity，
    // 但是这是一个重要的点，我们决定切换到“next”，以便于跟踪它。
    // XXX “应用重定向” 弹框此时出现过多的误报，因此暂时关闭。
    if (false) {
        if (mLastStartedActivity != null && !mLastStartedActivity.finishing) {
            long now = SystemClock.uptimeMillis();
            final boolean inTime = mLastStartedActivity.startTime != 0
                    && (mLastStartedActivity.startTime + START_WARN_TIME) >= now;
            final int lastUid = mLastStartedActivity.info.applicationInfo.uid;
            final int nextUid = next.info.applicationInfo.uid;
            if (inTime && lastUid != nextUid
                    && lastUid != next.launchedFromUid
                    && mService.checkPermission(
                            android.Manifest.permission.STOP_APP_SWITCHES,
                            -1, next.launchedFromUid)
                    != PackageManager.PERMISSION_GRANTED) {
                mService.showLaunchWarningLocked(mLastStartedActivity, next);
            } else {
                next.startTime = now;
                mLastStartedActivity = next;
            }
        } else {
            next.startTime = SystemClock.uptimeMillis();
            mLastStartedActivity = next;
        }
    }

    mStackSupervisor.setLaunchSource(next.info.applicationInfo.uid);

    // 我们需要开始暂停当前 activity，以便可以恢复最重要的 activity。
    boolean dontWaitForPause = (next.info.flags&ActivityInfo.FLAG_RESUME_WHILE_PAUSING) != 0;
    // 1. 这里将会通知其他 activity 暂停。
    boolean pausing = mStackSupervisor.pauseBackStacks(userLeaving, true, dontWaitForPause);
    // 当前已经有 resume 的 activity，那么首先将其 pause。
    if (mResumedActivity != null) {
        if (DEBUG_STATES) Slog.d(TAG_STATES,
                "resumeTopActivityLocked: Pausing " + mResumedActivity);
        pausing |= startPausingLocked(userLeaving, false, true, dontWaitForPause);
    }
    if (pausing) {
        if (DEBUG_SWITCH || DEBUG_STATES) Slog.v(TAG_STATES,
                "resumeTopActivityLocked: Skip resume: need to start pausing");
        // 在这一点上，我们希望即将到来的 activity 放置在 LRU 列表的顶部，因为我们
        // 知道很快就会需要它，如果它正好在最后，那么让它被杀死将是一种浪费。
        if (next.app != null && next.app.thread != null) {
            mService.updateLruProcessLocked(next.app, true, null);
        }
        if (DEBUG_STACK) mStackSupervisor.validateTopActivitiesLocked();
        return true;
    }

    // 如果最近的 activity 是 noHistory 模式，但是只是因为设备进入睡眠而 stop 而不是
    // stop + destory ，我们需要确保 finish 它，因为我们正在创建一个新的 activity。
    if (mService.isSleeping() && mLastNoHistoryActivity != null &&
            !mLastNoHistoryActivity.finishing) {
        if (DEBUG_STATES) Slog.d(TAG_STATES,
                "no-history finish of " + mLastNoHistoryActivity + " on new resume");
        requestFinishActivityLocked(mLastNoHistoryActivity.appToken, Activity.RESULT_CANCELED,
                null, "resume-no-history", false);
        mLastNoHistoryActivity = null;
    }

    if (prev != null && prev != next) {
        if (!mStackSupervisor.mWaitingVisibleActivities.contains(prev)
                && next != null && !next.nowVisible) {
            mStackSupervisor.mWaitingVisibleActivities.add(prev);
            if (DEBUG_SWITCH) Slog.v(TAG_SWITCH,
                    "Resuming top, waiting visible to hide: " + prev);
        } else {
            // 下一个 activity 已经可见，因此请立刻隐藏上一个 activity 的窗口，
            // 以便我们尽快显示新的 activity。如果前一个正在 finish，我们只会这样做，
            // 这应该意味着它在被 resume 的那个之上，因此快速隐藏它是不错的。
            // 否则，我们想要执行允许显示 resume 的 activity 的正常路由，这样我们就可以
            // 决定是否应该隐藏前一个，具体取决于是否发现新的 activiy 是否全屏。
            if (prev.finishing) {
                mWindowManager.setAppVisibility(prev.appToken, false);
                if (DEBUG_SWITCH) Slog.v(TAG_SWITCH,
                        "Not waiting for visible to hide: " + prev + ", waitingVisible="
                        + mStackSupervisor.mWaitingVisibleActivities.contains(prev)
                        + ", nowVisible=" + next.nowVisible);
            } else {
                if (DEBUG_SWITCH) Slog.v(TAG_SWITCH,
                        "Previous already visible but still waiting to hide: " + prev
                        + ", waitingVisible="
                        + mStackSupervisor.mWaitingVisibleActivities.contains(prev)
                        + ", nowVisible=" + next.nowVisible);
            }
        }
    }

    // 启动此应用的 activity，确保该应用不再被视为已 stop 的。
    try {
        AppGlobals.getPackageManager().setPackageStoppedState(
                next.packageName, false, next.userId); /* TODO: Verify if correct userid */
    } catch (RemoteException e1) {
    } catch (IllegalArgumentException e) {
        Slog.w(TAG, "Failed trying to unstop package "
                + next.packageName + ": " + e);
    }

    // 我们正在启动下一个 activity，因此告诉 WindowManager，前一个 activity 很快就会被隐藏。
    // 这样，在计算所需的屏幕方向时，它可以知道忽略它。
    boolean anim = true;
    if (prev != null) {
        if (prev.finishing) {
            if (DEBUG_TRANSITION) Slog.v(TAG_TRANSITION,
                    "Prepare close transition: prev=" + prev);
            if (mNoAnimActivities.contains(prev)) {
                anim = false;
                mWindowManager.prepareAppTransition(AppTransition.TRANSIT_NONE, false);
            } else {
                mWindowManager.prepareAppTransition(prev.task == next.task
                        ? AppTransition.TRANSIT_ACTIVITY_CLOSE
                        : AppTransition.TRANSIT_TASK_CLOSE, false);
            }
            mWindowManager.setAppWillBeHidden(prev.appToken);
            mWindowManager.setAppVisibility(prev.appToken, false);
        } else {
            if (DEBUG_TRANSITION) Slog.v(TAG_TRANSITION,
                    "Prepare open transition: prev=" + prev);
            if (mNoAnimActivities.contains(next)) {
                anim = false;
                mWindowManager.prepareAppTransition(AppTransition.TRANSIT_NONE, false);
            } else {
                mWindowManager.prepareAppTransition(prev.task == next.task
                        ? AppTransition.TRANSIT_ACTIVITY_OPEN
                        : next.mLaunchTaskBehind
                                ? AppTransition.TRANSIT_TASK_OPEN_BEHIND
                                : AppTransition.TRANSIT_TASK_OPEN, false);
            }
        }
        if (false) {
            mWindowManager.setAppWillBeHidden(prev.appToken);
            mWindowManager.setAppVisibility(prev.appToken, false);
        }
    } else {
        if (DEBUG_TRANSITION) Slog.v(TAG_TRANSITION, "Prepare open transition: no previous");
        if (mNoAnimActivities.contains(next)) {
            anim = false;
            mWindowManager.prepareAppTransition(AppTransition.TRANSIT_NONE, false);
        } else {
            mWindowManager.prepareAppTransition(AppTransition.TRANSIT_ACTIVITY_OPEN, false);
        }
    }

    Bundle resumeAnimOptions = null;
    if (anim) {
        ActivityOptions opts = next.getOptionsForTargetActivityLocked();
        if (opts != null) {
            resumeAnimOptions = opts.toBundle();
        }
        next.applyOptionsLocked();
    } else {
        next.clearOptionsLocked();
    }

    ActivityStack lastStack = mStackSupervisor.getLastStack();
    if (next.app != null && next.app.thread != null) {
        if (DEBUG_SWITCH) Slog.v(TAG_SWITCH, "Resume running: " + next);

        // 此 activity 现在变得可见。
        mWindowManager.setAppVisibility(next.appToken, true);

        // 安排启动计时器以收集有关应用启动延时的信息。
        next.startLaunchTickingLocked();

        ActivityRecord lastResumedActivity =
                lastStack == null ? null :lastStack.mResumedActivity;
        ActivityState lastState = next.state;

        mService.updateCpuStats();

        if (DEBUG_STATES) Slog.v(TAG_STATES, "Moving to RESUMED: " + next + " (in existing)");
        next.state = ActivityState.RESUMED;
        mResumedActivity = next;
        next.task.touchActiveTime();
        mRecentTasks.addLocked(next.task);
        mService.updateLruProcessLocked(next.app, true, null);
        updateLRUListLocked(next);
        mService.updateOomAdjLocked();

        // 让 WindowManager根据新的 activity 顺序重新计算屏幕的方向。
        boolean notUpdated = true;
        if (mStackSupervisor.isFrontStack(this)) {
            Configuration config = mWindowManager.updateOrientationFromAppTokens(
                    mService.mConfiguration,
                    next.mayFreezeScreenLocked(next.app) ? next.appToken : null);
            if (config != null) {
                next.frozenBeforeDestroy = true;
            }
            notUpdated = !mService.updateConfigurationLocked(config, next, false, false);
        }

        if (notUpdated) {
            // 配置更新无法保留 activity 的现有实例，而是启动了一个新实例。
            // 我们应该完成所有工作，但是我们只是确保我们的 activity 仍处于最顶层
            // 如果出现了一些奇怪的事情，那么安排另一次运行，
            ActivityRecord nextNext = topRunningActivityLocked(null);
            if (DEBUG_SWITCH || DEBUG_STATES) Slog.i(TAG_STATES,
                    "Activity config changed during resume: " + next
                    + ", new next: " + nextNext);
            if (nextNext != next) {
                // 做完！
                mStackSupervisor.scheduleResumeTopActivities();
            }
            if (mStackSupervisor.reportResumedActivityLocked(next)) {
                mNoAnimActivities.clear();
                if (DEBUG_STACK) mStackSupervisor.validateTopActivitiesLocked();
                return true;
            }
            if (DEBUG_STACK) mStackSupervisor.validateTopActivitiesLocked();
            return false;
        }

        try {
            // 发送所有等待处理的结果。
            ArrayList<ResultInfo> a = next.results;
            if (a != null) {
                final int N = a.size();
                if (!next.finishing && N > 0) {
                    if (DEBUG_RESULTS) Slog.v(TAG_RESULTS,
                            "Delivering results to " + next + ": " + a);
                    next.app.thread.scheduleSendResult(next.appToken, a);
                }
            }

            if (next.newIntents != null) {
                next.app.thread.scheduleNewIntent(next.newIntents, next.appToken);
            }

            EventLog.writeEvent(EventLogTags.AM_RESUME_ACTIVITY, next.userId,
                    System.identityHashCode(next), next.task.taskId, next.shortComponentName);

            next.sleeping = false;
            mService.showAskCompatModeDialogLocked(next);
            next.app.pendingUiClean = true;
            next.app.forceProcessStateUpTo(mService.mTopProcessState);
            next.clearOptionsLocked();
            // 执行 activity 的 resume。
            next.app.thread.scheduleResumeActivity(next.appToken, next.app.repProcState,
                    mService.isNextTransitionForward(), resumeAnimOptions);

            mStackSupervisor.checkReadyForSleepLocked();

            if (DEBUG_STATES) Slog.d(TAG_STATES, "resumeTopActivityLocked: Resumed " + next);
        } catch (Exception e) {
            // 哎呀，需要重启这个 activity！
            if (DEBUG_STATES) Slog.v(TAG_STATES, "Resume failed; resetting state to "
                    + lastState + ": " + next);
            next.state = lastState;
            if (lastStack != null) {
                lastStack.mResumedActivity = lastResumedActivity;
            }
            Slog.i(TAG, "Restarting because process died: " + next);
            if (!next.hasBeenLaunched) {
                next.hasBeenLaunched = true;
            } else  if (SHOW_APP_STARTING_PREVIEW && lastStack != null &&
                    mStackSupervisor.isFrontStack(lastStack)) {
                mWindowManager.setAppStartingWindow(
                        next.appToken, next.packageName, next.theme,
                        mService.compatibilityInfoForPackageLocked(next.info.applicationInfo),
                        next.nonLocalizedLabel, next.labelRes, next.icon, next.logo,
                        next.windowFlags, null, true);
            }
            mStackSupervisor.startSpecificActivityLocked(next, true, false);
            if (DEBUG_STACK) mStackSupervisor.validateTopActivitiesLocked();
            return true;
        }

        // 从这里开始，如果出现问题，就无法 resume activity 了。
        try {
            next.visible = true;
            completeResumeLocked(next);
        } catch (Exception e) {
            // 如果抛出任何异常，请丢弃此 activity 并尝试下一个。
            Slog.w(TAG, "Exception thrown during resume of " + next, e);
            requestFinishActivityLocked(next.appToken, Activity.RESULT_CANCELED, null,
                    "resume-exception", true);
            if (DEBUG_STACK) mStackSupervisor.validateTopActivitiesLocked();
            return true;
        }
        next.stopped = false;

    } else {
        // 哎呀，需要重启这个 activity！
        if (!next.hasBeenLaunched) {
            next.hasBeenLaunched = true;
        } else {
            if (SHOW_APP_STARTING_PREVIEW) {
                mWindowManager.setAppStartingWindow(
                        next.appToken, next.packageName, next.theme,
                        mService.compatibilityInfoForPackageLocked(
                                next.info.applicationInfo),
                        next.nonLocalizedLabel,
                        next.labelRes, next.icon, next.logo, next.windowFlags,
                        null, true);
            }
            if (DEBUG_SWITCH) Slog.v(TAG_SWITCH, "Restarting: " + next);
        }
        if (DEBUG_STATES) Slog.d(TAG_STATES, "resumeTopActivityLocked: Restarting " + next);
        // 开始启动指定的 activity。
        mStackSupervisor.startSpecificActivityLocked(next, true, true);
    }

    if (DEBUG_STACK) mStackSupervisor.validateTopActivitiesLocked();
    return true;
}
```

这里主要是为了 resume 前面刚准备好的栈顶 activity，首先需要先 pause 其它的 activity，然后 resume 栈顶的 activity，如果需要则重启 activity。

上面此句将回调其他 activity：

```java
boolean pausing = mStackSupervisor.pauseBackStacks(userLeaving, true, dontWaitForPause);
...
```

#### ActivityStackSupervisor

```java
// ActivityStackSupervisor.java

boolean pauseBackStacks(boolean userLeaving, boolean resuming, boolean dontWait) {
    boolean someActivityPaused = false;
    for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
        ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
        for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = stacks.get(stackNdx);
            if (!isFrontStack(stack) && stack.mResumedActivity != null) {
                if (DEBUG_STATES) Slog.d(TAG_STATES, "pauseBackStacks: stack=" + stack +
                        " mResumedActivity=" + stack.mResumedActivity);
                someActivityPaused |= stack.startPausingLocked(userLeaving, false, resuming,
                        dontWait);
            }
        }
    }
    return someActivityPaused;
}
```

这里遍历栈并调用 `startPausingLocaked` 方法，pause 所有已经 resume 的 activity。

#### ActivityStack

```java
// ActivityStack.java

final boolean startPausingLocked(boolean userLeaving, boolean uiSleeping, boolean resuming,
        boolean dontWait) {
    if (mPausingActivity != null) {
        Slog.wtf(TAG, "Going to pause when pause is already pending for " + mPausingActivity
                + " state=" + mPausingActivity.state);
        if (!mService.isSleeping()) {
            completePauseLocked(false);
        }
    }
    ActivityRecord prev = mResumedActivity;
    if (prev == null) {
        if (!resuming) {
            Slog.wtf(TAG, "Trying to pause when nothing is resumed");
            mStackSupervisor.resumeTopActivitiesLocked();
        }
        return false;
    }

    if (mActivityContainer.mParentActivity == null) {
        mStackSupervisor.pauseChildStacks(prev, userLeaving, uiSleeping, resuming, dontWait);
    }

    if (DEBUG_STATES) Slog.v(TAG_STATES, "Moving to PAUSING: " + prev);
    else if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Start pausing: " + prev);
    mResumedActivity = null;
    mPausingActivity = prev;
    mLastPausedActivity = prev;
    mLastNoHistoryActivity = (prev.intent.getFlags() & Intent.FLAG_ACTIVITY_NO_HISTORY) != 0
            || (prev.info.flags & ActivityInfo.FLAG_NO_HISTORY) != 0 ? prev : null;
    prev.state = ActivityState.PAUSING;
    prev.task.touchActiveTime();
    clearLaunchTime(prev);
    final ActivityRecord next = mStackSupervisor.topRunningActivityLocked();
    if (mService.mHasRecents && (next == null || next.noDisplay || next.task != prev.task || uiSleeping)) {
        prev.updateThumbnailLocked(screenshotActivities(prev), null);
    }
    stopFullyDrawnTraceIfNeeded();

    mService.updateCpuStats();

    if (prev.app != null && prev.app.thread != null) {
        if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Enqueueing pending pause: " + prev);
        try {
            EventLog.writeEvent(EventLogTags.AM_PAUSE_ACTIVITY,
                    prev.userId, System.identityHashCode(prev),
                    prev.shortComponentName);
            mService.updateUsageStats(prev, false);
            // 回调目标 activity 的 onPause。
            prev.app.thread.schedulePauseActivity(prev.appToken, prev.finishing,
                    userLeaving, prev.configChangeFlags, dontWait);
        } catch (Exception e) {
            // 忽略异常，如果进程死亡，其他代码会做清理工作。
            Slog.w(TAG, "Exception thrown during pause", e);
            mPausingActivity = null;
            mLastPausedActivity = null;
            mLastNoHistoryActivity = null;
        }
    } else {
        mPausingActivity = null;
        mLastPausedActivity = null;
        mLastNoHistoryActivity = null;
    }

    // 如果我们不打算休眠，我们希望确保设备处于唤醒状态，
    // 直到下一个 activity 开始。
    if (!uiSleeping && !mService.isSleepingOrShuttingDown()) {
        mStackSupervisor.acquireLaunchWakelock();
    }

    if (mPausingActivity != null) {
        if (!uiSleeping) {
            prev.pauseKeyDispatchingLocked();
        } else if (DEBUG_PAUSE) {
             Slog.v(TAG_PAUSE, "Key dispatch not paused for screen off");
        }

        if (dontWait) {
            // 如果调用者表示它们不想等待暂停，那么现在就完成暂停。
            completePauseLocked(false);
            return false;

        } else {
            // 如果应用没有响应，请执行暂停超时。 
            // 我们不会花太多时间，因为这会直接影响用户看到的界面响应效果。
            Message msg = mHandler.obtainMessage(PAUSE_TIMEOUT_MSG);
            msg.obj = prev;
            prev.pauseTime = SystemClock.uptimeMillis();
            // 发送暂停超时。
            mHandler.sendMessageDelayed(msg, PAUSE_TIMEOUT);
            if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Waiting for pause to complete...");
            return true;
        }

    } else {
        // 此 activity 未能安排暂停，那么请将它其视为暂停。
        if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Activity not running, resuming next.");
        if (!resuming) {
            mStackSupervisor.getFocusedStack().resumeTopActivityLocked(null);
        }
        return false;
    }
}
```

回到上面，如果栈顶目标 activity 不需要重新启动，则执行 onResume 回调即可。

```java
next.app.thread.scheduleResumeActivity(next.appToken, next.app.repProcState,
                    mService.isNextTransitionForward(), resumeAnimOptions);
...
```

否则需要重新启动 activity，即：

```java
mStackSupervisor.startSpecificActivityLocked(next, true, true);
...
```

那么这里继续从这分析完整的 activity 启动过程。

#### ActivityStackSupervisor

```java
// ActivityStackSupervisor.java

void startSpecificActivityLocked(ActivityRecord r,
        boolean andResume, boolean checkConfig) {
    // 这个 activity 所在应用进程是否运行?
    ProcessRecord app = mService.getProcessRecordLocked(r.processName,
            r.info.applicationInfo.uid, true);

    r.task.stack.setLaunchTime(r);

    if (app != null && app.thread != null) {
        try {
            if ((r.info.flags&ActivityInfo.FLAG_MULTIPROCESS) == 0
                    || !"android".equals(r.info.packageName)) {
                // 如果它是被标记为在多进程中运行的 android 平台组件，请不要添加它。
                // 因为这实际上是框架的一部分，因此在进程中跟踪单独的 apk 没有意义。
                app.addPackage(r.info.packageName, r.info.applicationInfo.versionCode,
                        mService.mProcessStats);
            }
            // 这里去启动 activity。
            realStartActivityLocked(r, app, andResume, checkConfig);
            return;
        } catch (RemoteException e) {
            Slog.w(TAG, "Exception when starting activity "
                    + r.intent.getComponent().flattenToShortString(), e);
        }

        // 如果抛出了 DeadObjectException -- 请重新启动应用。
    }

    // 确保应用进程启动。
    mService.startProcessLocked(r.processName, r.info.applicationInfo, true, 0,
            "activity", r.intent.getComponent(), false, false, true);
}
```

上面首先获取要启动的 activity 的应用进程记录，如果存在则启动 activity，否则确保应用进程启动。

如果应用进程未启动，则 `startProcessLocaked` 会启动进程，最终辗转调用到 AMS 的 `attachApplication ` 方法绑定应用，这时会判断如果有 activtiy 正在等待启动，会调用 `ActivityStackSUpervisor` 的 `attachApplicationLocked` 方法，它内部会调用 `realStartActivityLocked` 完成和上面进程已存在分支相同的启动。

```java
// ActivityStackSupervisor.java

final boolean realStartActivityLocked(ActivityRecord r,
        ProcessRecord app, boolean andResume, boolean checkConfig)
        throws RemoteException {

    if (andResume) {
        r.startFreezingScreenLocked(app, 0);
        mWindowManager.setAppVisibility(r.appToken, true);

        // 安排计时器以手机应用启动延迟的信息。
        r.startLaunchTickingLocked();
    }

    // 让 WindonManager 根据新的 activity 顺序重新计算屏幕的方向。
    // 请注意，因此，它可以将新的方向信息回调到 ActivityManager。
    // 我们不关心这一点，因为 activity 当前没有运行所以我们无论如何都只是重新启动它。
    if (checkConfig) {
        Configuration config = mWindowManager.updateOrientationFromAppTokens(
                mService.mConfiguration,
                r.mayFreezeScreenLocked(app) ? r.appToken : null);
        mService.updateConfigurationLocked(config, r, false, false);
    }

    r.app = app;
    app.waitingToKill = null;
    r.launchCount++;
    r.lastLaunchTime = SystemClock.uptimeMillis();

    if (DEBUG_ALL) Slog.v(TAG, "Launching: " + r);

    int idx = app.activities.indexOf(r);
    if (idx < 0) {
        app.activities.add(r);
    }
    // 更新 LRU 进程记录和进程优先级。
    mService.updateLruProcessLocked(app, true, null);
    mService.updateOomAdjLocked();

    final TaskRecord task = r.task;
    if (task.mLockTaskAuth == LOCK_TASK_AUTH_LAUNCHABLE ||
            task.mLockTaskAuth == LOCK_TASK_AUTH_LAUNCHABLE_PRIV) {
        setLockTaskModeLocked(task, LOCK_TASK_MODE_LOCKED, "mLockTaskAuth==LAUNCHABLE", false);
    }

    final ActivityStack stack = task.stack;
    try {
        if (app.thread == null) {
            throw new RemoteException();
        }
        List<ResultInfo> results = null;
        List<ReferrerIntent> newIntents = null;
        if (andResume) {
            results = r.results;
            newIntents = r.newIntents;
        }
        if (DEBUG_SWITCH) Slog.v(TAG_SWITCH,
                "Launching: " + r + " icicle=" + r.icicle + " with results=" + results
                + " newIntents=" + newIntents + " andResume=" + andResume);
        if (andResume) {
            EventLog.writeEvent(EventLogTags.AM_RESTART_ACTIVITY,
                    r.userId, System.identityHashCode(r),
                    task.taskId, r.shortComponentName);
        }
        if (r.isHomeActivity() && r.isNotResolverActivity()) {
            // Home 进程是任务的根进程。
            mService.mHomeProcess = task.mActivities.get(0).app;
        }
        mService.ensurePackageDexOpt(r.intent.getComponent().getPackageName());
        r.sleeping = false;
        r.forceNewConfig = false;
        mService.showAskCompatModeDialogLocked(r);
        r.compat = mService.compatibilityInfoForPackageLocked(r.info.applicationInfo);
        ProfilerInfo profilerInfo = null;
        if (mService.mProfileApp != null && mService.mProfileApp.equals(app.processName)) {
            if (mService.mProfileProc == null || mService.mProfileProc == app) {
                mService.mProfileProc = app;
                final String profileFile = mService.mProfileFile;
                if (profileFile != null) {
                    ParcelFileDescriptor profileFd = mService.mProfileFd;
                    if (profileFd != null) {
                        try {
                            profileFd = profileFd.dup();
                        } catch (IOException e) {
                            if (profileFd != null) {
                                try {
                                    profileFd.close();
                                } catch (IOException o) {
                                }
                                profileFd = null;
                            }
                        }
                    }

                    profilerInfo = new ProfilerInfo(profileFile, profileFd,
                            mService.mSamplingInterval, mService.mAutoStopProfiler);
                }
            }
        }

        if (andResume) {
            app.hasShownUi = true;
            app.pendingUiClean = true;
        }
        app.forceProcessStateUpTo(mService.mTopProcessState);
        // 这里通知客户端 ApplicationThread 执行启动 activity。
        app.thread.scheduleLaunchActivity(new Intent(r.intent), r.appToken,
                System.identityHashCode(r), r.info, new Configuration(mService.mConfiguration),
                new Configuration(stack.mOverrideConfig), r.compat, r.launchedFromPackage,
                task.voiceInteractor, app.repProcState, r.icicle, r.persistentState, results,
                newIntents, !andResume, mService.isNextTransitionForward(), profilerInfo);

        if ((app.info.privateFlags&ApplicationInfo.PRIVATE_FLAG_CANT_SAVE_STATE) != 0) {
            // 重量级过程。
        }

    } catch (RemoteException e) {
        if (r.launchFailed) {
            // 这是我们第二次失败 - finish activity 然后放弃。
            Slog.e(TAG, "Second failure launching "
                  + r.intent.getComponent().flattenToShortString()
                  + ", giving up", e);
            mService.appDiedLocked(app);
            stack.requestFinishActivityLocked(r.appToken, Activity.RESULT_CANCELED, null,
                    "2nd-crash", false);
            return false;
        }

        // 这是我们第一次失败 - 重启进程重试
        app.activities.remove(r);
        throw e;
    }

    r.launchFailed = false;
    if (stack.updateLRUListLocked(r)) {
        Slog.w(TAG, "Activity " + r
              + " being launched, but already in LRU list");
    }

    if (andResume) {
        // 作为启动过程的一部分，ActivityThread 也会执行 resume。
        stack.minimalResumeActivityLocked(r);
    } else {
        // 这个 activity 没有在 resume 的状态下开始……
        // 这看起来应该像我们要求它 pause + stop（但保持可见），
        // 并且它已经这样做并报告回当前的冰柱（onRestoreInstanceState 的参数）和其他状态。
        if (DEBUG_STATES) Slog.v(TAG_STATES,
                "Moving to STOPPED: " + r + " (starting in stopped state)");
        r.state = STOPPED;
        r.stopped = true;
    }

    // 如果需要，启动新版本设置屏幕。 我们在启动初始 activity（即 home）
    // 之后执行此操作，以便它可以在后台进行初始化，从而使切换回来更快，看起来更好。
    if (isFrontStack(stack)) {
        mService.startSetupActivityLocked();
    }

    // 更新我们绑定的任何服务，这些服务可能关心他们的客户端是否有 activity。
    mService.mServices.updateServiceConnectionActivitiesLocked(r.app);

    return true;
}
```

此句将通过 IPC 通知客户端进程进行 activity 的启动工作，`app.thread` 为应用的客户端进程的表示类型 `ApplicationThread`，它是 `ActivityThread` 的内部类。

```java
app.thread.scheduleLaunchActivity
```

进程间通信将通过 `ApplicationThreadProxy` 转发至客户端 `ApplicationThreadNative`，然后至 `ApplicationThread` 方法

#### ApplicationThreadProxy

```java
// ApplicationThreadNative.java - class ApplicationThreadProxy

public final void scheduleLaunchActivity(Intent intent, IBinder token, int ident,
        ActivityInfo info, Configuration curConfig, Configuration overrideConfig,
        CompatibilityInfo compatInfo, String referrer, IVoiceInteractor voiceInteractor,
        int procState, Bundle state, PersistableBundle persistentState,
        List<ResultInfo> pendingResults, List<ReferrerIntent> pendingNewIntents,
        boolean notResumed, boolean isForward, ProfilerInfo profilerInfo) throws RemoteException {
    Parcel data = Parcel.obtain();
    data.writeInterfaceToken(IApplicationThread.descriptor);
    intent.writeToParcel(data, 0);
    data.writeStrongBinder(token);
    data.writeInt(ident);
    info.writeToParcel(data, 0);
    curConfig.writeToParcel(data, 0);
    if (overrideConfig != null) {
        data.writeInt(1);
        overrideConfig.writeToParcel(data, 0);
    } else {
        data.writeInt(0);
    }
    compatInfo.writeToParcel(data, 0);
    data.writeString(referrer);
    data.writeStrongBinder(voiceInteractor != null ? voiceInteractor.asBinder() : null);
    data.writeInt(procState);
    data.writeBundle(state);
    data.writePersistableBundle(persistentState);
    data.writeTypedList(pendingResults);
    data.writeTypedList(pendingNewIntents);
    data.writeInt(notResumed ? 1 : 0);
    data.writeInt(isForward ? 1 : 0);
    if (profilerInfo != null) {
        data.writeInt(1);
        profilerInfo.writeToParcel(data, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
    } else {
        data.writeInt(0);
    }
    // 发送至应用进程。
    mRemote.transact(SCHEDULE_LAUNCH_ACTIVITY_TRANSACTION, data, null,
            IBinder.FLAG_ONEWAY);
    data.recycle();
}
```

#### ApplicationThreadNative

```java
@Override
public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
        throws RemoteException {
    switch (code) {
    case SCHEDULE_LAUNCH_ACTIVITY_TRANSACTION:
    {
        data.enforceInterface(IApplicationThread.descriptor);
        Intent intent = Intent.CREATOR.createFromParcel(data);
        IBinder b = data.readStrongBinder();
        int ident = data.readInt();
        ActivityInfo info = ActivityInfo.CREATOR.createFromParcel(data);
        Configuration curConfig = Configuration.CREATOR.createFromParcel(data);
        Configuration overrideConfig = null;
        if (data.readInt() != 0) {
            overrideConfig = Configuration.CREATOR.createFromParcel(data);
        }
        CompatibilityInfo compatInfo = CompatibilityInfo.CREATOR.createFromParcel(data);
        String referrer = data.readString();
        IVoiceInteractor voiceInteractor = IVoiceInteractor.Stub.asInterface(
                data.readStrongBinder());
        int procState = data.readInt();
        Bundle state = data.readBundle();
        PersistableBundle persistentState = data.readPersistableBundle();
        List<ResultInfo> ri = data.createTypedArrayList(ResultInfo.CREATOR);
        List<ReferrerIntent> pi = data.createTypedArrayList(ReferrerIntent.CREATOR);
        boolean notResumed = data.readInt() != 0;
        boolean isForward = data.readInt() != 0;
        ProfilerInfo profilerInfo = data.readInt() != 0
                ? ProfilerInfo.CREATOR.createFromParcel(data) : null;
        scheduleLaunchActivity(intent, b, ident, info, curConfig, overrideConfig, compatInfo,
                referrer, voiceInteractor, procState, state, persistentState, ri, pi,
                notResumed, isForward, profilerInfo);
        return true;
    }
    ...
}
```

### Client

#### ApplicationThread

```java
// ActivityThread.java - class ApplicationThrad

// 我们使用 token 来识别此 activity，而无需将 activity 本身发送回 ActivityManager。
@Override
public final void scheduleLaunchActivity(Intent intent, IBinder token, int ident,
        ActivityInfo info, Configuration curConfig, Configuration overrideConfig,
        CompatibilityInfo compatInfo, String referrer, IVoiceInteractor voiceInteractor,
        int procState, Bundle state, PersistableBundle persistentState,
        List<ResultInfo> pendingResults, List<ReferrerIntent> pendingNewIntents,
        boolean notResumed, boolean isForward, ProfilerInfo profilerInfo) {

    updateProcessState(procState, false);

    ActivityClientRecord r = new ActivityClientRecord();

    r.token = token;
    r.ident = ident;
    r.intent = intent;
    r.referrer = referrer;
    r.voiceInteractor = voiceInteractor;
    r.activityInfo = info;
    r.compatInfo = compatInfo;
    r.state = state;
    r.persistentState = persistentState;

    r.pendingResults = pendingResults;
    r.pendingIntents = pendingNewIntents;

    r.startsNotResumed = notResumed;
    r.isForward = isForward;

    r.profilerInfo = profilerInfo;

    r.overrideConfig = overrideConfig;
    updatePendingConfiguration(curConfig);

    sendMessage(H.LAUNCH_ACTIVITY, r);
}
```

这里将信息打包发送给`ActivityThread` 内部 `H` 类（`Handler` 子类）的 `mH` 对象。

#### H

```java
// ActivityThread.java - class H

public void handleMessage(Message msg) {
    if (DEBUG_MESSAGES) Slog.v(TAG, ">>> handling: " + codeToString(msg.what));
    switch (msg.what) {
        case LAUNCH_ACTIVITY: {
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "activityStart");
            final ActivityClientRecord r = (ActivityClientRecord) msg.obj;

            r.packageInfo = getPackageInfoNoCheck(
                    r.activityInfo.applicationInfo, r.compatInfo);
            handleLaunchActivity(r, null);
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        } break;
        ...
    }
    ...
}         
```

#### ActivityThread

```java
// ActivityThread.java

private void handleLaunchActivity(ActivityClientRecord r, Intent customIntent) {
    // 如果我们在转到后台后准备好 gc，我们又回来了，那么跳过它。
    unscheduleGcIdler();
    mSomeActivitiesChanged = true;

    if (r.profilerInfo != null) {
        mProfiler.setProfiler(r.profilerInfo);
        mProfiler.startProfiling();
    }

    // 确保我们使用最新的配置运行。
    handleConfigurationChanged(null, null);

    if (localLOGV) Slog.v(
        TAG, "Handling launch of " + r);

    // 在创建 activity 之前初始化。
    WindowManagerGlobal.initialize();

    // 创建 activity 回调目标 activity 的 onCreate。
    Activity a = performLaunchActivity(r, customIntent);

    if (a != null) {
        r.createdConfig = new Configuration(mConfiguration);
        Bundle oldState = r.state;
        // 回调目标 activity 的 onStart，onResume。
        handleResumeActivity(r.token, false, r.isForward,
                !r.activity.mFinished && !r.startsNotResumed);

        if (!r.activity.mFinished && r.startsNotResumed) {
            // 实际上，ActivityManager 希望此 activity 在开始时 pause，
            // 因为它需要可见，但不在前台。我们通过正常的启动（因为 activity 
            // 希望在第一次运行时在窗口显示之前执行 onResume），然后 pause 
            // 它来完成这一任务。但是，在这种情况下，我们不需要执行完整的 pause 
            // 周期 【冷冻（onSaveInstance）等】，因为 实际上，ActivityManager 
            // 假定它可以保留它所具有的当前状态。
            try {
                r.activity.mCalled = false;
                // 调用目标 activity 的 onPause。
                mInstrumentation.callActivityOnPause(r.activity);
                // 我们需要保持原始状态，防止我们再次创建。 
                // 但我们只针对 pre-Honeycomb 应用执行此操作，
                // 这些应用程序在 pause 时始终保存其状态，
                // 因此在从 pause 状态重新启动时我们无法保存它们的状态。 
                // 对于 HC 及以后的版本，我们希望（并且可以）将状态保存为
                // stop 的活动的正常部分。
                if (r.isPreHoneycomb()) {
                    r.state = oldState;
                }
                if (!r.activity.mCalled) {
                    throw new SuperNotCalledException(
                        "Activity " + r.intent.getComponent().toShortString() +
                        " did not call through to super.onPause()");
                }

            } catch (SuperNotCalledException e) {
                throw e;

            } catch (Exception e) {
                if (!mInstrumentation.onException(r.activity, e)) {
                    throw new RuntimeException(
                            "Unable to pause activity "
                            + r.intent.getComponent().toShortString()
                            + ": " + e.toString(), e);
                }
            }
            r.paused = true;
        }
    } else {
        // 如果出现任何原因的错误，请告诉 ActivityManager 停止我们。
        try {
            ActivityManagerNative.getDefault()
                .finishActivity(r.token, Activity.RESULT_CANCELED, null, false);
        } catch (RemoteException ex) {
            // Ignore
        }
    }
}
```

看一下 `performLaunchActivity` 方法是如何创建 activity 的。

```java
// ActivityThread.java

private Activity performLaunchActivity(ActivityClientRecord r, Intent customIntent) {
    // System.out.println("##### [" + System.currentTimeMillis() + "] ActivityThread.performLaunchActivity(" + r + ")");

    ActivityInfo aInfo = r.activityInfo;
    if (r.packageInfo == null) {
        r.packageInfo = getPackageInfo(aInfo.applicationInfo, r.compatInfo,
                Context.CONTEXT_INCLUDE_CODE);
    }

    ComponentName component = r.intent.getComponent();
    if (component == null) {
        component = r.intent.resolveActivity(
            mInitialApplication.getPackageManager());
        r.intent.setComponent(component);
    }

    if (r.activityInfo.targetActivity != null) {
        component = new ComponentName(r.activityInfo.packageName,
                r.activityInfo.targetActivity);
    }

    Activity activity = null;
    try {
        java.lang.ClassLoader cl = r.packageInfo.getClassLoader();
        // 创建 activity 实例。
        activity = mInstrumentation.newActivity(
                cl, component.getClassName(), r.intent);
        StrictMode.incrementExpectedActivityCount(activity.getClass());
        r.intent.setExtrasClassLoader(cl);
        r.intent.prepareToEnterProcess();
        if (r.state != null) {
            r.state.setClassLoader(cl);
        }
    } catch (Exception e) {
        if (!mInstrumentation.onException(activity, e)) {
            throw new RuntimeException(
                "Unable to instantiate activity " + component
                + ": " + e.toString(), e);
        }
    }

    try {
        // 确定 application 被创建。
        Application app = r.packageInfo.makeApplication(false, mInstrumentation);

        if (localLOGV) Slog.v(TAG, "Performing launch of " + r);
        if (localLOGV) Slog.v(
                TAG, r + ": app=" + app
                + ", appName=" + app.getPackageName()
                + ", pkg=" + r.packageInfo.getPackageName()
                + ", comp=" + r.intent.getComponent().toShortString()
                + ", dir=" + r.packageInfo.getAppDir());

        if (activity != null) {
            // 创建 activity Context。
            Context appContext = createBaseContextForActivity(r, activity);
            CharSequence title = r.activityInfo.loadLabel(appContext.getPackageManager());
            Configuration config = new Configuration(mCompatConfiguration);
            if (DEBUG_CONFIGURATION) Slog.v(TAG, "Launching activity "
                    + r.activityInfo.name + " with config " + config);
            activity.attach(appContext, this, getInstrumentation(), r.token,
                    r.ident, app, r.intent, r.activityInfo, title, r.parent,
                    r.embeddedID, r.lastNonConfigurationInstances, config,
                    r.referrer, r.voiceInteractor);

            if (customIntent != null) {
                activity.mIntent = customIntent;
            }
            r.lastNonConfigurationInstances = null;
            activity.mStartedActivity = false;
            int theme = r.activityInfo.getThemeResource();
            if (theme != 0) {
                activity.setTheme(theme);
            }

            activity.mCalled = false;
            if (r.isPersistable()) {
                mInstrumentation.callActivityOnCreate(activity, r.state, r.persistentState);
            } else {
                // 回调 activity 的 onCreate 方法。
                mInstrumentation.callActivityOnCreate(activity, r.state);
            }
            if (!activity.mCalled) {
                throw new SuperNotCalledException(
                    "Activity " + r.intent.getComponent().toShortString() +
                    " did not call through to super.onCreate()");
            }
            r.activity = activity;
            r.stopped = true;
            if (!r.activity.mFinished) {
                activity.performStart();
                r.stopped = false;
            }
            if (!r.activity.mFinished) {
                if (r.isPersistable()) {
                    if (r.state != null || r.persistentState != null) {
                        mInstrumentation.callActivityOnRestoreInstanceState(activity, r.state,
                                r.persistentState);
                    }
                } else if (r.state != null) {
                    mInstrumentation.callActivityOnRestoreInstanceState(activity, r.state);
                }
            }
            if (!r.activity.mFinished) {
                activity.mCalled = false;
                if (r.isPersistable()) {
                    mInstrumentation.callActivityOnPostCreate(activity, r.state,
                            r.persistentState);
                } else {
                    mInstrumentation.callActivityOnPostCreate(activity, r.state);
                }
                if (!activity.mCalled) {
                    throw new SuperNotCalledException(
                        "Activity " + r.intent.getComponent().toShortString() +
                        " did not call through to super.onPostCreate()");
                }
            }
        }
        r.paused = true;

        // 添加到 activity 列表。
        mActivities.put(r.token, r);

    } catch (SuperNotCalledException e) {
        throw e;

    } catch (Exception e) {
        if (!mInstrumentation.onException(activity, e)) {
            throw new RuntimeException(
                "Unable to start activity " + component
                + ": " + e.toString(), e);
        }
    }

    return activity;
}

```

