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
    ActivityRecord resultRecord = null;
    if (resultTo != null) {
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

    if ((launchFlags & Intent.FLAG_ACTIVITY_FORWARD_RESULT) != 0 && sourceRecord != null) {
        // Transfer the result target from the source activity to the new
        // one being started, including any failures.
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
            resultRecord.removeResultsLocked(sourceRecord, resultWho, requestCode);
        }
        if (sourceRecord.launchedFromUid == callingUid) {
            // The new activity is being launched from the same uid as the previous
            // activity in the flow, and asking to forward its result back to the
            // previous.  In this case the activity is serving as a trampoline between
            // the two, so we also want to update its launchedFromPackage to be the
            // same as the previous activity.  Note that this is safe, since we know
            // these two packages come from the same uid; the caller could just as
            // well have supplied that same package name itself.  This specifially
            // deals with the case of an intent picker/chooser being launched in the app
            // flow to redirect to an activity picked by the user, where we want the final
            // activity to consider it to have been launched by the previous app activity.
            callingPackage = sourceRecord.launchedFromPackage;
        }
    }

    if (err == ActivityManager.START_SUCCESS && intent.getComponent() == null) {
        // We couldn't find a class that can handle the given Intent.
        // That's the end of that!
        err = ActivityManager.START_INTENT_NOT_RESOLVED;
    }

    if (err == ActivityManager.START_SUCCESS && aInfo == null) {
        // We couldn't find the specific class specified in the Intent.
        // Also the end of the line.
        err = ActivityManager.START_CLASS_NOT_FOUND;
    }

    if (err == ActivityManager.START_SUCCESS
            && !isCurrentProfileLocked(userId)
            && (aInfo.flags & FLAG_SHOW_FOR_ALL_USERS) == 0) {
        // Trying to launch a background activity that doesn't show for all users.
        err = ActivityManager.START_NOT_CURRENT_USER_ACTIVITY;
    }

    if (err == ActivityManager.START_SUCCESS && sourceRecord != null
            && sourceRecord.task.voiceSession != null) {
        // If this activity is being launched as part of a voice session, we need
        // to ensure that it is safe to do so.  If the upcoming activity will also
        // be part of the voice session, we can only launch it if it has explicitly
        // said it supports the VOICE category, or it is a part of the calling app.
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
        // If the caller is starting a new voice session, just make sure the target
        // is actually allowing it to run this way.
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

    final ActivityStack resultStack = resultRecord == null ? null : resultRecord.task.stack;

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
            // The Intent we give to the watcher has the extra data
            // stripped off, since it can contain private information.
            Intent watchIntent = intent.cloneFilter();
            abort |= !mService.mController.activityStarting(watchIntent,
                    aInfo.applicationInfo.packageName);
        } catch (RemoteException e) {
            mService.mController = null;
        }
    }

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

    final ActivityStack stack = mFocusedStack;
    if (voiceSession == null && (stack.mResumedActivity == null
            || stack.mResumedActivity.info.applicationInfo.uid != callingUid)) {
        if (!mService.checkAppSwitchAllowedLocked(callingPid, callingUid,
                realCallingPid, realCallingUid, "Activity start")) {
            PendingActivityLaunch pal =
                    new PendingActivityLaunch(r, sourceRecord, startFlags, stack);
            mPendingActivityLaunches.add(pal);
            ActivityOptions.abort(options);
            return ActivityManager.START_SWITCHES_CANCELED;
        }
    }

    if (mService.mDidAppSwitch) {
        // This is the second allowed switch since we stopped switches,
        // so now just generally allow switches.  Use case: user presses
        // home (switches disabled, switch to home, mDidAppSwitch now true);
        // user taps a home icon (coming from home so allowed, we hit here
        // and now allow anyone to switch again).
        mService.mAppSwitchesAllowedTime = 0;
    } else {
        mService.mDidAppSwitch = true;
    }

    doPendingActivityLaunchesLocked(false);

    err = startActivityUncheckedLocked(r, sourceRecord, voiceSession, voiceInteractor,
            startFlags, true, options, inTask);

    if (err < 0) {
        // If someone asked to have the keyguard dismissed on the next
        // activity start, but we are not actually doing an activity
        // switch...  just dismiss the keyguard now, because we
        // probably want to see whatever is behind it.
        notifyActivityDrawnForKeyguard();
    }
    return err;
}
```



