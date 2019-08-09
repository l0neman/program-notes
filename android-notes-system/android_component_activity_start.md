# Android Activity 启动流程分析

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

### Instrumentation

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

### ActivityManagerProxy

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

### ActivityManagerNative

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

### ActivityManagerService

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

### ActivityStackSupervisor

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
            // 因为此时我们正在等待当前 activity 暂停（因为不会销毁它），并并且还没有
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

### PackageManagerService

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
            // Check for results that need to skip the current profile.
            ResolveInfo xpResolveInfo  = querySkipCurrentProfileIntents(matchingFilters, intent,
                    resolvedType, flags, userId);
            if (xpResolveInfo != null && isUserEnabled(xpResolveInfo.targetUserId)) {
                List<ResolveInfo> result = new ArrayList<ResolveInfo>(1);
                result.add(xpResolveInfo);
                return filterIfNotPrimaryUser(result, userId);
            }

            // Check for results in the current profile.
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

### ActivityStackSupervisor

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
        // activity 切换......现在就关闭键盘，因为我们可能想看看它的后面不管它后面有什么。
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
        // 下一步启动。
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

参数分析：todo

```java
// ActivityStackSupervisor.java

final int startActivityUncheckedLocked(final ActivityRecord r, ActivityRecord sourceRecord,
        IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor, int startFlags,
        boolean doResume, Bundle options, TaskRecord inTask) {
    final Intent intent = r.intent;
    final int callingUid = r.launchedFromUid;

    // In some flows in to this function, we retrieve the task record and hold on to it
    // without a lock before calling back in to here...  so the task at this point may
    // not actually be in recents.  Check for that, and if it isn't in recents just
    // consider it invalid.
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
        // We have a conflict between the Intent and the Activity manifest, manifest wins.
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
        // For whatever reason this activity is being launched into a new
        // task...  yet the caller has requested a result back.  Well, that
        // is pretty messed up, so instead immediately send back a cancel
        // and let the new task continue launched as normal without a
        // dependency on its originator.
        Slog.w(TAG, "Activity is launching as a new task, so cancelling activity result.");
        r.resultTo.task.stack.sendActivityResultLocked(-1,
                r.resultTo, r.resultWho, r.requestCode,
                Activity.RESULT_CANCELED, null);
        r.resultTo = null;
    }

    if ((launchFlags & Intent.FLAG_ACTIVITY_NEW_DOCUMENT) != 0 && r.resultTo == null) {
        launchFlags |= Intent.FLAG_ACTIVITY_NEW_TASK;
    }

    // If we are actually going to launch in to a new task, there are some cases where
    // we further want to do multiple task.
    if ((launchFlags & Intent.FLAG_ACTIVITY_NEW_TASK) != 0) {
        if (launchTaskBehind
                || r.info.documentLaunchMode == ActivityInfo.DOCUMENT_LAUNCH_ALWAYS) {
            launchFlags |= Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
        }
    }

    // We'll invoke onUserLeaving before onPause only if the launching
    // activity did not explicitly state that this is an automated launch.
    mUserLeaving = (launchFlags & Intent.FLAG_ACTIVITY_NO_USER_ACTION) == 0;
    if (DEBUG_USER_LEAVING) Slog.v(TAG_USER_LEAVING,
            "startActivity() => mUserLeaving=" + mUserLeaving);

    // If the caller has asked not to resume at this point, we make note
    // of this in the record so that we can skip it when trying to find
    // the top running activity.
    if (!doResume) {
        r.delayedResume = true;
    }

    ActivityRecord notTop =
            (launchFlags & Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP) != 0 ? r : null;

    // If the onlyIfNeeded flag is set, then we can do this if the activity
    // being launched is the same as the one making the call...  or, as
    // a special case, if we do not know the caller then we count the
    // current top activity as the caller.
    if ((startFlags&ActivityManager.START_FLAG_ONLY_IF_NEEDED) != 0) {
        ActivityRecord checkedCaller = sourceRecord;
        if (checkedCaller == null) {
            checkedCaller = mFocusedStack.topRunningNonDelayedActivityLocked(notTop);
        }
        if (!checkedCaller.realActivity.equals(r.realActivity)) {
            // Caller is not the same as launcher, so always needed.
            startFlags &= ~ActivityManager.START_FLAG_ONLY_IF_NEEDED;
        }
    }

    boolean addingToTask = false;
    TaskRecord reuseTask = null;

    // If the caller is not coming from another activity, but has given us an
    // explicit task into which they would like us to launch the new activity,
    // then let's see about doing that.
    if (sourceRecord == null && inTask != null && inTask.stack != null) {
        final Intent baseIntent = inTask.getBaseIntent();
        final ActivityRecord root = inTask.getRootActivity();
        if (baseIntent == null) {
            ActivityOptions.abort(options);
            throw new IllegalArgumentException("Launching into task without base intent: "
                    + inTask);
        }

        // If this task is empty, then we are adding the first activity -- it
        // determines the root, and must be launching as a NEW_TASK.
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

        // If task is empty, then adopt the interesting intent launch flags in to the
        // activity being started.
        if (root == null) {
            final int flagsOfInterest = Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK | Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                    | Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS;
            launchFlags = (launchFlags&~flagsOfInterest)
                    | (baseIntent.getFlags()&flagsOfInterest);
            intent.setFlags(launchFlags);
            inTask.setIntent(r);
            addingToTask = true;

        // If the task is not empty and the caller is asking to start it as the root
        // of a new task, then we don't actually want to start this on the task.  We
        // will bring the task to the front, and possibly give it a new intent.
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
            // This activity is not being started from another...  in this
            // case we -always- start a new task.
            if ((launchFlags & Intent.FLAG_ACTIVITY_NEW_TASK) == 0 && inTask == null) {
                Slog.w(TAG, "startActivity called from non-Activity context; forcing " +
                        "Intent.FLAG_ACTIVITY_NEW_TASK for: " + intent);
                launchFlags |= Intent.FLAG_ACTIVITY_NEW_TASK;
            }
        } else if (sourceRecord.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE) {
            // The original activity who is starting us is running as a single
            // instance...  this new activity it is starting must go on its
            // own task.
            launchFlags |= Intent.FLAG_ACTIVITY_NEW_TASK;
        } else if (launchSingleInstance || launchSingleTask) {
            // The activity being started is a single instance...  it always
            // gets launched into its own task.
            launchFlags |= Intent.FLAG_ACTIVITY_NEW_TASK;
        }
    }

    ActivityInfo newTaskInfo = null;
    Intent newTaskIntent = null;
    ActivityStack sourceStack;
    if (sourceRecord != null) {
        if (sourceRecord.finishing) {
            // If the source is finishing, we can't further count it as our source.  This
            // is because the task it is associated with may now be empty and on its way out,
            // so we don't want to blindly throw it in to that task.  Instead we will take
            // the NEW_TASK flow and try to find a task for it. But save the task information
            // so it can be used when creating the new task.
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

    // We may want to try to place the new activity in to an existing task.  We always
    // do this if the target activity is singleTask or singleInstance; we will also do
    // this if NEW_TASK has been requested, and there is not an additional qualifier telling
    // us to still place it in a new task: multi task, always doc mode, or being asked to
    // launch this as a new task behind the current one.
    if (((launchFlags & Intent.FLAG_ACTIVITY_NEW_TASK) != 0 &&
            (launchFlags & Intent.FLAG_ACTIVITY_MULTIPLE_TASK) == 0)
            || launchSingleInstance || launchSingleTask) {
        // If bring to front is requested, and no result is requested and we have not
        // been given an explicit task to launch in to, and
        // we can find a task that was started with this same
        // component, then instead of launching bring that one to the front.
        if (inTask == null && r.resultTo == null) {
            // See if there is a task to bring to the front.  If this is
            // a SINGLE_INSTANCE activity, there can be one and only one
            // instance of it in the history, and it is always in its own
            // unique task, so we do a special search.
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
                    // This task was started because of movement of
                    // the activity based on affinity...  now that we
                    // are actually launching it, we can assign the
                    // base intent.
                    intentActivity.task.setIntent(r);
                }
                targetStack = intentActivity.task.stack;
                targetStack.mLastPausedActivity = null;
                // If the target task is not in the front, then we need
                // to bring it to the front...  except...  well, with
                // SINGLE_TASK_LAUNCH it's not entirely clear.  We'd like
                // to have the same behavior as if a new instance was
                // being started, which means not bringing it to the front
                // if the caller is not itself in the front.
                final ActivityStack focusStack = getFocusedStack();
                ActivityRecord curTop = (focusStack == null)
                        ? null : focusStack.topRunningNonDelayedActivityLocked(notTop);
                boolean movedToFront = false;
                if (curTop != null && (curTop.task != intentActivity.task ||
                        curTop.task != focusStack.topTask())) {
                    r.intent.addFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT);
                    if (sourceRecord == null || (sourceStack.topActivity() != null &&
                            sourceStack.topActivity().task == sourceRecord.task)) {
                        // We really do want to push this one into the user's face, right now.
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
                            // Caller wants to appear on home activity.
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

                // If the caller has requested that the target task be
                // reset, then do so.
                if ((launchFlags&Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) != 0) {
                    intentActivity = targetStack.resetTaskIfNeededLocked(intentActivity, r);
                }
                if ((startFlags & ActivityManager.START_FLAG_ONLY_IF_NEEDED) != 0) {
                    // We don't need to start a new activity, and
                    // the client said not to do anything if that
                    // is the case, so this is it!  And for paranoia, make
                    // sure we have correctly resumed the top activity.
                    if (doResume) {
                        resumeTopActivitiesLocked(targetStack, null, options);

                        // Make sure to notify Keyguard as well if we are not running an app
                        // transition later.
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
                    // The caller has requested to completely replace any
                    // existing task with its new activity.  Well that should
                    // not be too hard...
                    reuseTask = intentActivity.task;
                    reuseTask.performClearTaskLocked();
                    reuseTask.setIntent(r);
                } else if ((launchFlags & FLAG_ACTIVITY_CLEAR_TOP) != 0
                        || launchSingleInstance || launchSingleTask) {
                    // In this situation we want to remove all activities
                    // from the task up to the one being started.  In most
                    // cases this means we are resetting the task to its
                    // initial state.
                    ActivityRecord top =
                            intentActivity.task.performClearTaskLocked(r, launchFlags);
                    if (top != null) {
                        if (top.frontOfTask) {
                            // Activity aliases may mean we use different
                            // intents for the top activity, so make sure
                            // the task now has the identity of the new
                            // intent.
                            top.task.setIntent(r);
                        }
                        ActivityStack.logStartActivity(EventLogTags.AM_NEW_INTENT,
                                r, top.task);
                        top.deliverNewIntentLocked(callingUid, r.intent, r.launchedFromPackage);
                    } else {
                        // A special case: we need to start the activity because it is not
                        // currently running, and the caller has asked to clear the current
                        // task to have this activity at the top.
                        addingToTask = true;
                        // Now pretend like this activity is being started by the top of its
                        // task, so it is put in the right place.
                        sourceRecord = intentActivity;
                        TaskRecord task = sourceRecord.task;
                        if (task != null && task.stack == null) {
                            // Target stack got cleared when we all activities were removed
                            // above. Go ahead and reset it.
                            targetStack = computeStackFocus(sourceRecord, false /* newTask */);
                            targetStack.addTask(
                                    task, !launchTaskBehind /* toTop */, false /* moving */);
                        }

                    }
                } else if (r.realActivity.equals(intentActivity.task.realActivity)) {
                    // In this case the top activity on the task is the
                    // same as the one being launched, so we take that
                    // as a request to bring the task to the foreground.
                    // If the top activity in the task is the root
                    // activity, deliver this new intent to it if it
                    // desires.
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
                        // In this case we are launching the root activity
                        // of the task, but with a different intent.  We
                        // should start a new instance on top.
                        addingToTask = true;
                        sourceRecord = intentActivity;
                    }
                } else if ((launchFlags&Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) == 0) {
                    // In this case an activity is being launched in to an
                    // existing task, without resetting that task.  This
                    // is typically the situation of launching an activity
                    // from a notification or shortcut.  We want to place
                    // the new activity on top of the current task.
                    addingToTask = true;
                    sourceRecord = intentActivity;
                } else if (!intentActivity.task.rootWasReset) {
                    // In this case we are launching in to an existing task
                    // that has not yet been started from its front door.
                    // The current task has been brought to the front.
                    // Ideally, we'd probably like to place this new task
                    // at the bottom of its stack, but that's a little hard
                    // to do with the current organization of the code so
                    // for now we'll just drop it.
                    intentActivity.task.setIntent(r);
                }
                if (!addingToTask && reuseTask == null) {
                    // We didn't do anything...  but it was needed (a.k.a., client
                    // don't use that intent!)  And for paranoia, make
                    // sure we have correctly resumed the top activity.
                    if (doResume) {
                        targetStack.resumeTopActivityLocked(null, options);
                        if (!movedToFront) {
                            // Make sure to notify Keyguard as well if we are not running an app
                            // transition later.
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

    //String uri = r.intent.toURI();
    //Intent intent2 = new Intent(uri);
    //Slog.i(TAG, "Given intent: " + r.intent);
    //Slog.i(TAG, "URI is: " + uri);
    //Slog.i(TAG, "To intent: " + intent2);

    if (r.packageName != null) {
        // If the activity being launched is the same as the one currently
        // at the top, then we need to check if it should only be launched
        // once.
        ActivityStack topStack = mFocusedStack;
        ActivityRecord top = topStack.topRunningNonDelayedActivityLocked(notTop);
        if (top != null && r.resultTo == null) {
            if (top.realActivity.equals(r.realActivity) && top.userId == r.userId) {
                if (top.app != null && top.app.thread != null) {
                    if ((launchFlags & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0
                        || launchSingleTop || launchSingleTask) {
                        ActivityStack.logStartActivity(EventLogTags.AM_NEW_INTENT, top,
                                top.task);
                        // For paranoia, make sure we have correctly
                        // resumed the top activity.
                        topStack.mLastPausedActivity = null;
                        if (doResume) {
                            resumeTopActivitiesLocked();
                        }
                        ActivityOptions.abort(options);
                        if ((startFlags&ActivityManager.START_FLAG_ONLY_IF_NEEDED) != 0) {
                            // We don't need to start a new activity, and
                            // the client said not to do anything if that
                            // is the case, so this is it!
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

    // Should this be considered a new task?
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
                // Caller wants to appear on home activity, so before starting
                // their own activity we will bring home to the front.
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
            // In this case, we are adding the activity to an existing
            // task, but the caller has asked to clear that task if the
            // activity is already running.
            ActivityRecord top = sourceTask.performClearTaskLocked(r, launchFlags);
            keepCurTransition = true;
            if (top != null) {
                ActivityStack.logStartActivity(EventLogTags.AM_NEW_INTENT, r, top.task);
                top.deliverNewIntentLocked(callingUid, r.intent, r.launchedFromPackage);
                // For paranoia, make sure we have correctly
                // resumed the top activity.
                targetStack.mLastPausedActivity = null;
                if (doResume) {
                    targetStack.resumeTopActivityLocked(null);
                }
                ActivityOptions.abort(options);
                return ActivityManager.START_DELIVERED_TO_TOP;
            }
        } else if (!addingToTask &&
                (launchFlags&Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) != 0) {
            // In this case, we are launching an activity in our own task
            // that may already be running somewhere in the history, and
            // we want to shuffle it to the front of the stack if so.
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
        // An existing activity is starting this new activity, so we want
        // to keep the new one in the same task as the one that is starting
        // it.
        r.setTask(sourceTask, null);
        if (DEBUG_TASKS) Slog.v(TAG_TASKS, "Starting new activity " + r
                + " in existing task " + r.task + " from source " + sourceRecord);

    } else if (inTask != null) {
        // The caller is asking that the new activity be started in an explicit
        // task it has provided to us.
        if (isLockTaskModeViolation(inTask)) {
            Slog.e(TAG, "Attempted Lock Task Mode violation r=" + r);
            return ActivityManager.START_RETURN_LOCK_TASK_MODE_VIOLATION;
        }
        targetStack = inTask.stack;
        targetStack.moveTaskToFrontLocked(inTask, noAnimation, options, r.appTimeTracker,
                "inTaskToFront");

        // Check whether we should actually launch the new activity in to the task,
        // or just reuse the current activity on top.
        ActivityRecord top = inTask.getTopActivity();
        if (top != null && top.realActivity.equals(r.realActivity) && top.userId == r.userId) {
            if ((launchFlags & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0
                    || launchSingleTop || launchSingleTask) {
                ActivityStack.logStartActivity(EventLogTags.AM_NEW_INTENT, top, top.task);
                if ((startFlags&ActivityManager.START_FLAG_ONLY_IF_NEEDED) != 0) {
                    // We don't need to start a new activity, and
                    // the client said not to do anything if that
                    // is the case, so this is it!
                    return ActivityManager.START_RETURN_INTENT_TO_CALLER;
                }
                top.deliverNewIntentLocked(callingUid, r.intent, r.launchedFromPackage);
                return ActivityManager.START_DELIVERED_TO_TOP;
            }
        }

        if (!addingToTask) {
            // We don't actually want to have this activity added to the task, so just
            // stop here but still tell the caller that we consumed the intent.
            ActivityOptions.abort(options);
            return ActivityManager.START_TASK_TO_FRONT;
        }

        r.setTask(inTask, null);
        if (DEBUG_TASKS) Slog.v(TAG_TASKS, "Starting new activity " + r
                + " in explicit task " + r.task);

    } else {
        // This not being started from an existing activity, and not part
        // of a new task...  just put it in the top task, though these days
        // this case should never happen.
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
    targetStack.startActivityLocked(r, newTask, doResume, keepCurTransition, options);
    if (!launchTaskBehind) {
        // Don't set focus on an activity that's going to the back.
        mService.setFocusedActivityLocked(r, "startedActivity");
    }
    return ActivityManager.START_SUCCESS;
}
```

