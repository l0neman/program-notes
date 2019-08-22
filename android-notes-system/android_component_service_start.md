# Android Service 启动流程分析

[TOC]

## 前言

Service 是 Android 系统的四大组件 之一，相对于 Activity 来说，Service 运行于后台，不需要界面的支持，因此

相比 Activity 的启动流程更简单，分析起来相对容易一些，这里基于 Android 6.0.1 系统分析 Service 的启动流程。

## 启动方式

通常启动一个 Service 有两种方法，`startService` 或 `bindService`，它们都是 Context 的方法，`startService` 可启动一个 Service，`bindService` 在启动 Service 的同时还可提供绑定 Service 的功能，绑定后可持有 Service 端提供的 IBinder 类型的句柄向 Service 端发送请求。

### startService

```java
// ConctextImpl.java

@Override
public ComponentName startService(Intent service) {
    // 判断系统 UID 则打印警告日志。
    warnIfCallingFromSystemProcess();
    return startServiceCommon(service, mUser);
}
```

### bindService

```java
// ContextImpl.java

@Override
public boolean bindService(Intent service, ServiceConnection conn,
        int flags) {
    warnIfCallingFromSystemProcess();
    return bindServiceCommon(service, conn, flags, Process.myUserHandle());
}
```

下面开始分析，首先分析 `startService` 方法，它调用了内部的 `startServiceCommon` 方法。

## 启动流程

### ContextImpl

```java
// ContextImpl.java

private ComponentName startServiceCommon(Intent service, UserHandle user) {
    try {
        validateServiceIntent(service);
        service.prepareToLeaveProcess();
        ComponentName cn = ActivityManagerNative.getDefault().startService(
            mMainThread.getApplicationThread(), service, service.resolveTypeIfNeeded(
                        getContentResolver()), getOpPackageName(), user.getIdentifier());
        if (cn != null) {
            if (cn.getPackageName().equals("!")) {
                throw new SecurityException(
                        "Not allowed to start service " + service
                        + " without permission " + cn.getClassName());
            } else if (cn.getPackageName().equals("!!")) {
                throw new SecurityException(
                        "Unable to start service " + service
                        + ": " + cn.getClassName());
            }
        }
        return cn;
    } catch (RemoteException e) {
        throw new RuntimeException("Failure from system", e);
    }
}
```

直接进入了 `ActivityManagerProxy` 的方法：

### ActivityManagerProxy

```java
// ActivityManagerNative.java - class ActivityManagerProxy

public ComponentName startService(IApplicationThread caller, Intent service,
        String resolvedType, String callingPackage, int userId) throws RemoteException
{
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    data.writeInterfaceToken(IActivityManager.descriptor);
    data.writeStrongBinder(caller != null ? caller.asBinder() : null);
    service.writeToParcel(data, 0);
    data.writeString(resolvedType);
    data.writeString(callingPackage);
    data.writeInt(userId);
    mRemote.transact(START_SERVICE_TRANSACTION, data, reply, 0);
    reply.readException();
    ComponentName res = ComponentName.readFromParcel(reply);
    data.recycle();
    reply.recycle();
    return res;
}
```

发送至 `ActivityManagerNative`：

### ActivityManagerNative

```java
// ActivityManagerNative.java

@Override
public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
        throws RemoteException {
    switch (code) {
    ...
    case START_SERVICE_TRANSACTION: {
        data.enforceInterface(IActivityManager.descriptor);
        IBinder b = data.readStrongBinder();
        IApplicationThread app = ApplicationThreadNative.asInterface(b);
        Intent service = Intent.CREATOR.createFromParcel(data);
        String resolvedType = data.readString();
        String callingPackage = data.readString();
        int userId = data.readInt();
        ComponentName cn = startService(app, service, resolvedType, callingPackage, userId);
        reply.writeNoException();
        ComponentName.writeToParcel(cn, reply);
        return true;
    }
    ...
}
```

进入 `ActivityManagerService`：

### ActivityManagerService

```java
// ActivityManagerService.java

@Override
public ComponentName startService(IApplicationThread caller, Intent service,
        String resolvedType, String callingPackage, int userId)
        throws TransactionTooLargeException {
    enforceNotIsolatedCaller("startService");
    // 拒绝 Intent 携带文件描述符。
    if (service != null && service.hasFileDescriptors() == true) {
        throw new IllegalArgumentException("File descriptors passed in Intent");
    }

    if (callingPackage == null) {
        throw new IllegalArgumentException("callingPackage cannot be null");
    }

    if (DEBUG_SERVICE) Slog.v(TAG_SERVICE,
            "startService: " + service + " type=" + resolvedType);
    synchronized(this) {
        final int callingPid = Binder.getCallingPid();
        final int callingUid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        // 调用了 ActiveServices 的 startServiceLocaked 方法。
        ComponentName res = mServices.startServiceLocked(caller, service,
                resolvedType, callingPid, callingUid, callingPackage, userId);
        Binder.restoreCallingIdentity(origId);
        return res;
    }
}
```

直接调用了 `ActiveServices` 的 `startServiceLocked` 方法。

### ActiveServices

```java
// ActiveServices.java

ComponentName startServiceLocked(IApplicationThread caller, Intent service, String resolvedType,
        int callingPid, int callingUid, String callingPackage, int userId)
        throws TransactionTooLargeException {
    if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE, "startService: " + service
            + " type=" + resolvedType + " args=" + service.getExtras());

    // 是否在前台启动。
    final boolean callerFg;
    if (caller != null) {
        // 获得调用者进程记录。
        final ProcessRecord callerApp = mAm.getRecordForAppLocked(caller);
        if (callerApp == null) {
            throw new SecurityException(
                    "Unable to find app for caller " + caller
                    + " (pid=" + Binder.getCallingPid()
                    + ") when starting service " + service);
        }
        callerFg = callerApp.setSchedGroup != Process.THREAD_GROUP_BG_NONINTERACTIVE;
    } else {
        callerFg = true;
    }
    
    // 查询 service 信息。
    ServiceLookupResult res =
        retrieveServiceLocked(service, resolvedType, callingPackage,
                callingPid, callingUid, userId, true, callerFg);
    if (res == null) {
        return null;
    }
    if (res.record == null) {
        return new ComponentName("!", res.permission != null
                ? res.permission : "private to package");
    }

    ServiceRecord r = res.record;

    // 检查是否存在服务对应 user。
    if (!mAm.getUserManagerLocked().exists(r.userId)) {
        Slog.d(TAG, "Trying to start service with non-existent user! " + r.userId);
        return null;
    }

    NeededUriGrants neededGrants = mAm.checkGrantUriPermissionFromIntentLocked(
            callingUid, r.packageName, service, service.getFlags(), null, r.userId);
    if (unscheduleServiceRestartLocked(r, callingUid, false)) {
        if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "START SERVICE WHILE RESTART PENDING: " + r);
    }
    // 记录最后活动时间。
    r.lastActivity = SystemClock.uptimeMillis();
    r.startRequested = true;
    r.delayedStop = false;
    // 添加到待启动列表。
    r.pendingStarts.add(new ServiceRecord.StartItem(r, false, r.makeNextStartId(),
            service, neededGrants));

    final ServiceMap smap = getServiceMap(r.userId);
    boolean addToStarting = false;
    if (!callerFg && r.app == null && mAm.mStartedUsers.get(r.userId) != null) {
        // 获得目标 service 所在进程记录。
        ProcessRecord proc = mAm.getProcessRecordLocked(r.processName, r.appInfo.uid, false);
        if (proc == null || proc.curProcState > ActivityManager.PROCESS_STATE_RECEIVER) {
            // 如果这不是来自前台的调用者，如果已经有其他后台服务正在启动，我们可能希望延迟启动。
            // 这是为了避免在许多应用程序都处理诸如广播连接之类的事情时进程启动 spam。
            // 我们只只针对缓存进程执行此操作，因为不这样的话程序假设可以调用，startService() 以
            // 使 Service 在自己的进程中运行，并且该进程在启动之前不会被终止。Reveicer 尤其如此，
            // 它可以在 onReceiver() 中启动 Service 以执行额外的工作，并初始化一些全局状态作为
            // 其中的一部分。
            if (DEBUG_DELAYED_SERVICE) Slog.v(TAG_SERVICE, "Potential start delay of "
                    + r + " in " + proc);
            if (r.delayed) {
                // 此 service 已安排延迟启动，只是还让它继续等待。
                if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE, "Continuing to delay: " + r);
                return r.name;
            }
            if (smap.mStartingBackground.size() >= mMaxStartingBackground) {
                // 有别的事情要开始了，需要延迟。
                Slog.i(TAG_SERVICE, "Delaying start of: " + r);
                smap.mDelayedStartList.add(r);
                r.delayed = true;
                return r.name;
            }
            if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE, "Not delaying: " + r);
            addToStarting = true;
        } else if (proc.curProcState >= ActivityManager.PROCESS_STATE_SERVICE) {
            // 当我们将这项新 Service 排入我们正在等待启动的后台 Service 时，
            // 我们稍微休眠一下，还包括当前正在运行其他 Service 或 Receiver 的进程。
            addToStarting = true;
            if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE,
                    "Not delaying, but counting as bg: " + r);
        } else if (DEBUG_DELAYED_STARTS) {
            StringBuilder sb = new StringBuilder(128);
            sb.append("Not potential delay (state=").append(proc.curProcState)
                    .append(' ').append(proc.adjType);
            String reason = proc.makeAdjReason();
            if (reason != null) {
                sb.append(' ');
                sb.append(reason);
            }
            sb.append("): ");
            sb.append(r.toString());
            Slog.v(TAG_SERVICE, sb.toString());
        }
    } else if (DEBUG_DELAYED_STARTS) {
        if (callerFg) {
            Slog.v(TAG_SERVICE, "Not potential delay (callerFg=" + callerFg + " uid="
                    + callingUid + " pid=" + callingPid + "): " + r);
        } else if (r.app != null) {
            Slog.v(TAG_SERVICE, "Not potential delay (cur app=" + r.app + "): " + r);
        } else {
            Slog.v(TAG_SERVICE,
                    "Not potential delay (user " + r.userId + " not started): " + r);
        }
    }

    return startServiceInnerLocked(smap, service, r, callerFg, addToStarting);
}
```

上面首先查询现有服务记录，判断是否延迟启动，然后进入下一步 `startServiceInnerLocked`，这里先看下是如何查询的。

```java
// ActiveServices.java

private ServiceLookupResult retrieveServiceLocked(Intent service,
        String resolvedType, String callingPackage, int callingPid, int callingUid, int userId,
        boolean createIfNeeded, boolean callingFromFg) {
    ServiceRecord r = null;
    if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "retrieveServiceLocked: " + service
            + " type=" + resolvedType + " callingUid=" + callingUid);

    userId = mAm.handleIncomingUser(callingPid, callingUid, userId,
            false, ActivityManagerService.ALLOW_NON_FULL_IN_PROFILE, "service", null);

    // 获取 SerivceMap 结构。
    ServiceMap smap = getServiceMap(userId);
    final ComponentName comp = service.getComponent();
    if (comp != null) {
        // 通过 componetName 查询 ServiceRecord。
        r = smap.mServicesByName.get(comp);
    }
    if (r == null) {
        // // 通过 FilterComparison 查询 ServiceRecord。
        Intent.FilterComparison filter = new Intent.FilterComparison(service);
        r = smap.mServicesByIntent.get(filter);
    }
    if (r == null) {
        try {
            // 通过 Intente 提供的信息从已安装应用中查询。
            ResolveInfo rInfo =
                AppGlobals.getPackageManager().resolveService(
                            service, resolvedType,
                            ActivityManagerService.STOCK_PM_FLAGS, userId);
            ServiceInfo sInfo =
                rInfo != null ? rInfo.serviceInfo : null;
            if (sInfo == null) {
                Slog.w(TAG_SERVICE, "Unable to start service " + service + " U=" + userId +
                      ": not found");
                return null;
            }
            // 构造新的 ServiceInfo。
            ComponentName name = new ComponentName(
                    sInfo.applicationInfo.packageName, sInfo.name);
            if (userId > 0) {
                if (mAm.isSingleton(sInfo.processName, sInfo.applicationInfo,
                        sInfo.name, sInfo.flags)
                        && mAm.isValidSingletonCall(callingUid, sInfo.applicationInfo.uid)) {
                    userId = 0;
                    smap = getServiceMap(0);
                }
                sInfo = new ServiceInfo(sInfo);
                sInfo.applicationInfo = mAm.getAppInfoForUser(sInfo.applicationInfo, userId);
            }
            r = smap.mServicesByName.get(name);
            if (r == null && createIfNeeded) {
                Intent.FilterComparison filter
                        = new Intent.FilterComparison(service.cloneFilter());
                ServiceRestarter res = new ServiceRestarter();
                BatteryStatsImpl.Uid.Pkg.Serv ss = null;
                BatteryStatsImpl stats = mAm.mBatteryStatsService.getActiveStatistics();
                synchronized (stats) {
                    ss = stats.getServiceStatsLocked(
                            sInfo.applicationInfo.uid, sInfo.packageName,
                            sInfo.name);
                }
                // 构造新的 ServiceRecord。
                r = new ServiceRecord(mAm, ss, name, filter, sInfo, callingFromFg, res);
                res.setService(r);
                // 写入记录结构。
                smap.mServicesByName.put(name, r);
                smap.mServicesByIntent.put(filter, r);

                // 确认不在待启动列表中。
                for (int i=mPendingServices.size()-1; i>=0; i--) {
                    ServiceRecord pr = mPendingServices.get(i);
                    if (pr.serviceInfo.applicationInfo.uid == sInfo.applicationInfo.uid
                            && pr.name.equals(name)) {
                        mPendingServices.remove(i);
                    }
                }
            }
        } catch (RemoteException ex) {
            // pm 在同一个进程中，这永远不会发生。
        }
    }
    if (r != null) {
        // 权限检查。
        if (mAm.checkComponentPermission(r.permission,
                callingPid, callingUid, r.appInfo.uid, r.exported)
                != PackageManager.PERMISSION_GRANTED) {
            if (!r.exported) {
                Slog.w(TAG, "Permission Denial: Accessing service " + r.name
                        + " from pid=" + callingPid
                        + ", uid=" + callingUid
                        + " that is not exported from uid " + r.appInfo.uid);
                return new ServiceLookupResult(null, "not exported from uid "
                        + r.appInfo.uid);
            }
            Slog.w(TAG, "Permission Denial: Accessing service " + r.name
                    + " from pid=" + callingPid
                    + ", uid=" + callingUid
                    + " requires " + r.permission);
            return new ServiceLookupResult(null, r.permission);
        } else if (r.permission != null && callingPackage != null) {
            final int opCode = AppOpsManager.permissionToOpCode(r.permission);
            if (opCode != AppOpsManager.OP_NONE && mAm.mAppOpsService.noteOperation(
                    opCode, callingUid, callingPackage) != AppOpsManager.MODE_ALLOWED) {
                Slog.w(TAG, "Appop Denial: Accessing service " + r.name
                        + " from pid=" + callingPid
                        + ", uid=" + callingUid
                        + " requires appop " + AppOpsManager.opToName(opCode));
                return null;
            }
        }

        if (!mAm.mIntentFirewall.checkService(r.name, service, callingUid, callingPid,
                resolvedType, r.appInfo)) {
            return null;
        }
        return new ServiceLookupResult(r, null);
    }
    return null;
}
```

继续往下看：

```java
// ActiveServices.java

ComponentName startServiceInnerLocked(ServiceMap smap, Intent service, ServiceRecord r,
        boolean callerFg, boolean addToStarting) throws TransactionTooLargeException {
    ProcessStats.ServiceState stracker = r.getTracker();
    if (stracker != null) {
        // 跟踪 service 状态。
        stracker.setStarted(true, mAm.mProcessStats.getMemFactorLocked(), r.lastActivity);
    }
    r.callStart = false;
    synchronized (r.stats.getBatteryStats()) {
        // 电池统计。
        r.stats.startRunningLocked();
    }
    // 启动服务。
    String error = bringUpServiceLocked(r, service.getFlags(), callerFg, false);
    if (error != null) {
        return new ComponentName("!!", error);
    }

    if (r.startRequested && addToStarting) {
        boolean first = smap.mStartingBackground.size() == 0;
        // 加入后台启动列表。
        smap.mStartingBackground.add(r);
        r.startingBgTimeout = SystemClock.uptimeMillis() + BG_START_TIMEOUT;
        if (DEBUG_DELAYED_SERVICE) {
            RuntimeException here = new RuntimeException("here");
            here.fillInStackTrace();
            Slog.v(TAG_SERVICE, "Starting background (first=" + first + "): " + r, here);
        } else if (DEBUG_DELAYED_STARTS) {
            Slog.v(TAG_SERVICE, "Starting background (first=" + first + "): " + r);
        }
        if (first) {
            smap.rescheduleDelayedStarts();
        }
    } else if (callerFg) {
        smap.ensureNotStartingBackground(r);
    }

    return r.name;
}
```

继续往下看。

```java
// ActiveServices.java

private final String bringUpServiceLocked(ServiceRecord r, int intentFlags, boolean execInFg,
        boolean whileRestarting) throws TransactionTooLargeException {
    //Slog.i(TAG, "Bring up service:");
    //r.dump("  ");

    if (r.app != null && r.app.thread != null) {
        // 通知 service 进程回调 Service 的 onStartCommand 方法。
        sendServiceArgsLocked(r, execInFg, false);
        return null;
    }

    if (!whileRestarting && r.restartDelay > 0) {
        // 如果等待重启，则什么也不做。
        return null;
    }

    if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Bringing up " + r + " " + r.intent);

    // 我们现在正在启动 Service，所以不再处于重启状态。
    if (mRestartingServices.remove(r)) {
        r.resetRestartCounter();
        clearRestartingIfNeededLocked(r);
    }

    // 确保此 Service 不再被视为延迟，我们现在就开始启动。
    if (r.delayed) {
        if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE, "REM FR DELAY LIST (bring up): " + r);
        getServiceMap(r.userId).mDelayedStartList.remove(r);
        r.delayed = false;
    }

    // 确保已启动拥有此服务的用户，如果没有，那我们不希望允许它运行。
    if (mAm.mStartedUsers.get(r.userId) == null) {
        String msg = "Unable to launch app "
                + r.appInfo.packageName + "/"
                + r.appInfo.uid + " for service "
                + r.intent.getIntent() + ": user " + r.userId + " is stopped";
        Slog.w(TAG, msg);
        bringDownServiceLocked(r);
        return msg;
    }

    // Service 现在正在启动，Package 现在不能被停止。
    try {
        AppGlobals.getPackageManager().setPackageStoppedState(
                r.packageName, false, r.userId);
    } catch (RemoteException e) {
    } catch (IllegalArgumentException e) {
        Slog.w(TAG, "Failed trying to unstop package "
                + r.packageName + ": " + e);
    }

    final boolean isolated = (r.serviceInfo.flags&ServiceInfo.FLAG_ISOLATED_PROCESS) != 0;
    final String procName = r.processName;
    ProcessRecord app;

    if (!isolated) {
        // 查询 Service 所在进程记录。
        app = mAm.getProcessRecordLocked(procName, r.appInfo.uid, false);
        if (DEBUG_MU) Slog.v(TAG_MU, "bringUpServiceLocked: appInfo.uid=" + r.appInfo.uid
                    + " app=" + app);
        if (app != null && app.thread != null) {
            try {
                app.addPackage(r.appInfo.packageName, r.appInfo.versionCode, mAm.mProcessStats);
                // 1. 下一步启动。
                realStartServiceLocked(r, app, execInFg);
                return null;
            } catch (TransactionTooLargeException e) {
                throw e;
            } catch (RemoteException e) {
                Slog.w(TAG, "Exception when starting service " + r.shortName, e);
            }

            // 如果抛出了 DeadObjectException - 请重新启动应用程序。
        }
    } else {
        // 如果此 Service 在一个独立的进程中运行，那么每次调用 startProcessLocked() 时，
        // 我们将获得一个新的隔离进程，如果我们正在等待一个进程出现，则启动另一个进程。
        // 为了解决这个问题，我们在服务中存储它正在运行或等待出现的任何当前隔离进程。
        app = r.isolatedProc;
    }

    // 没有运行 - 则启动它，并将此 ServiceRecord 加入队列，以便在应用程序启动时执行。
    if (app == null) {
        if ((app=mAm.startProcessLocked(procName, r.appInfo, true, intentFlags,
                "service", r.name, false, isolated, false)) == null) {
            String msg = "Unable to launch app "
                    + r.appInfo.packageName + "/"
                    + r.appInfo.uid + " for service "
                    + r.intent.getIntent() + ": process is bad";
            Slog.w(TAG, msg);
            bringDownServiceLocked(r);
            return msg;
        }
        if (isolated) {
            r.isolatedProc = app;
        }
    }

    if (!mPendingServices.contains(r)) {
        mPendingServices.add(r);
    }

    if (r.delayedStop) {
        // 哦，嘿，我们被要求被停止！
        r.delayedStop = false;
        if (r.startRequested) {
            if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE,
                    "Applying delayed stop (in bring up): " + r);
            stopServiceLocked(r);
        }
    }

    return null;
}
```

如果 Service 所在进程已启动，则进入下一步 `realStartServiceLocked` 启动 Service。否则使用 `mAm.startProcessLocked` 方法启动进程，进程启动后，会辗转调用到 `ActivityManagerService` 的 `attachApplicationLocked` 方法，它会通知目标进程绑定 Application，如果检查到有等待启动的 Service，则进入 `ActiveServices` 的 `attachApplicationLocked` 方法中，最终会进入 `realStartServiceLocked` 方法启动。

```java
// ActiveServices.java

private final void realStartServiceLocked(ServiceRecord r,
        ProcessRecord app, boolean execInFg) throws RemoteException {
    if (app.thread == null) {
        throw new RemoteException();
    }
    if (DEBUG_MU)
        Slog.v(TAG_MU, "realStartServiceLocked, ServiceRecord.uid = " + r.appInfo.uid
                + ", ProcessRecord.uid = " + app.uid);
    r.app = app;
    r.restartTime = r.lastActivity = SystemClock.uptimeMillis();

    final boolean newService = app.services.add(r);
    // 1. 检查 Service 启动延迟。
    bumpServiceExecutingLocked(r, execInFg, "create");
    mAm.updateLruProcessLocked(app, false, null);
    mAm.updateOomAdjLocked();

    boolean created = false;
    try {
        if (LOG_SERVICE_START_STOP) {
            String nameTerm;
            int lastPeriod = r.shortName.lastIndexOf('.');
            nameTerm = lastPeriod >= 0 ? r.shortName.substring(lastPeriod) : r.shortName;
            EventLogTags.writeAmCreateService(
                    r.userId, System.identityHashCode(r), nameTerm, r.app.uid, r.app.pid);
        }
        synchronized (r.stats.getBatteryStats()) {
            r.stats.startLaunchedLocked();
        }
        mAm.ensurePackageDexOpt(r.serviceInfo.packageName);
        app.forceProcessStateUpTo(ActivityManager.PROCESS_STATE_SERVICE);
        // 2. 安排 Service 所在进程执行创建服务的任务。
        app.thread.scheduleCreateService(r, r.serviceInfo,
                mAm.compatibilityInfoForPackageLocked(r.serviceInfo.applicationInfo),
                app.repProcState);
        r.postNotification();
        created = true;
    } catch (DeadObjectException e) {
        Slog.w(TAG, "Application dead when creating service " + r);
        mAm.appDiedLocked(app);
        throw e;
    } finally {
        if (!created) {
            final boolean inDestroying = mDestroyingServices.contains(r);
            serviceDoneExecutingLocked(r, inDestroying, inDestroying);

            // 清理.
            if (newService) {
                app.services.remove(r);
                r.app = null;
            }

            // 重启。
            if (!inDestroying) {
                scheduleServiceRestartLocked(r, false);
            }
        }
    }

    // 请求绑定绑定服务。
    requestServiceBindingsLocked(r, execInFg);

    updateServiceClientActivitiesLocked(app, null, true);

    // 如果服务处于启动状态，并且没有挂起的参数，则伪造一个，以便调用其 onStartCommand()。
    if (r.startRequested && r.callStart && r.pendingStarts.size() == 0) {
        r.pendingStarts.add(new ServiceRecord.StartItem(r, false, r.makeNextStartId(),
                null, null));
    }

    // 4. 发送参数至 onServiceCommand()
    sendServiceArgsLocked(r, execInFg, true);

    if (r.delayed) {
        if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE, "REM FR DELAY LIST (new proc): " + r);
        getServiceMap(r.userId).mDelayedStartList.remove(r);
        r.delayed = false;
    }

    if (r.delayedStop) {
        // 哦，嘿，我们已被要求停止！
        r.delayedStop = false;
        if (r.startRequested) {
            if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE,
                    "Applying delayed stop (from start): " + r);
            stopServiceLocked(r);
        }
    }
} 
```

这里就开始通知应用端进程创建 Service 了，首先看 `bumpServiceExecutingLocked` 做了什么：

```java
// ActiveServices.java

private final void bumpServiceExecutingLocked(ServiceRecord r, boolean fg, String why) {
    if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, ">>> EXECUTING "
            + why + " of " + r + " in app " + r.app);
    else if (DEBUG_SERVICE_EXECUTING) Slog.v(TAG_SERVICE_EXECUTING, ">>> EXECUTING "
            + why + " of " + r.shortName);
    long now = SystemClock.uptimeMillis();
    if (r.executeNesting == 0) {
        r.executeFg = fg;
        ProcessStats.ServiceState stracker = r.getTracker();
        if (stracker != null) {
            stracker.setExecuting(true, mAm.mProcessStats.getMemFactorLocked(), now);
        }
        if (r.app != null) {
            r.app.executingServices.add(r);
            r.app.execServicesFg |= fg;
            if (r.app.executingServices.size() == 1) {
                // 安排 Service 延时通知。
                scheduleServiceTimeoutLocked(r.app);
            }
        }
    } else if (r.app != null && fg && !r.app.execServicesFg) {
        r.app.execServicesFg = true;
        // 安排 Service 延时通知。
        scheduleServiceTimeoutLocked(r.app);
    }
    r.executeFg |= fg;
    r.executeNesting++;
    r.executingStart = now;
}
```

```java
// ActiveServices.java

void scheduleServiceTimeoutLocked(ProcessRecord proc) {
    if (proc.executingServices.size() == 0 || proc.thread == null) {
        return;
    }
    
    long now = SystemClock.uptimeMillis();
    Message msg = mAm.mHandler.obtainMessage(
            ActivityManagerService.SERVICE_TIMEOUT_MSG);
    msg.obj = proc;
    // 发送超时 msg，如果在发送时间之前没有移除此消息，
    // 则会执行 SERVICE_TIMEOUT_MSG 任务。
    mAm.mHandler.sendMessageAtTime(msg,
            proc.execServicesFg ? (now+SERVICE_TIMEOUT) : (now+ SERVICE_BACKGROUND_TIMEOUT));
}
```

看一下启动超时会做什么：

### ActivityManagerService

```java
// ActivityManagerService.java - class MainHandler

@Override
public void handleMessage(Message msg) {
    switch (msg.what) {
    ...
    case SERVICE_TIMEOUT_MSG: {
        if (mDidDexOpt) {
            mDidDexOpt = false;
            Message nmsg = mHandler.obtainMessage(SERVICE_TIMEOUT_MSG);
            nmsg.obj = msg.obj;
            mHandler.sendMessageDelayed(nmsg, ActiveServices.SERVICE_TIMEOUT);
            return;
        }
        mServices.serviceTimeout((ProcessRecord)msg.obj);
    } break;
    ...
}
```

### ActiveServices

```java
// ActiveServices.java

void serviceTimeout(ProcessRecord proc) {
    String anrMessage = null;

    synchronized(mAm) {
        if (proc.executingServices.size() == 0 || proc.thread == null) {
            return;
        }
        final long now = SystemClock.uptimeMillis();
        final long maxTime =  now -
                (proc.execServicesFg ? SERVICE_TIMEOUT : SERVICE_BACKGROUND_TIMEOUT);
        ServiceRecord timeout = null;
        long nextTime = 0;
        for (int i=proc.executingServices.size()-1; i>=0; i--) {
            ServiceRecord sr = proc.executingServices.valueAt(i);
            if (sr.executingStart < maxTime) {
                timeout = sr;
                break;
            }
            if (sr.executingStart > nextTime) {
                nextTime = sr.executingStart;
            }
        }
        if (timeout != null && mAm.mLruProcesses.contains(proc)) {
            Slog.w(TAG, "Timeout executing service: " + timeout);
            // 记录相关日志。
            StringWriter sw = new StringWriter();
            PrintWriter pw = new FastPrintWriter(sw, false, 1024);
            pw.println(timeout);
            timeout.dump(pw, "    ");
            pw.close();
            mLastAnrDump = sw.toString();
            mAm.mHandler.removeCallbacks(mLastAnrDumpClearer);
            mAm.mHandler.postDelayed(mLastAnrDumpClearer, LAST_ANR_LIFETIME_DURATION_MSECS);
            anrMessage = "executing service " + timeout.shortName;
        } else {
            Message msg = mAm.mHandler.obtainMessage(
                    ActivityManagerService.SERVICE_TIMEOUT_MSG);
            msg.obj = proc;
            mAm.mHandler.sendMessageAtTime(msg, proc.execServicesFg
                    ? (nextTime+SERVICE_TIMEOUT) : (nextTime + SERVICE_BACKGROUND_TIMEOUT));
        }
    }

    if (anrMessage != null) {
        // 通知 app 未响应。
        mAm.appNotResponding(proc, null, null, false, anrMessage);
    }
}
```

回到上面，`app.thread.scheduleCreateService(...)`，继续看是如何安排客户端创建 Service 实例的：

这里会和应用进程进行 IPC 沟通。

### ApplicationThreadProxy

```java
// ApplicationThreadNative.java - class ApplicationThreadProxy

public final void scheduleCreateService(IBinder token, ServiceInfo info,
         CompatibilityInfo compatInfo, int processState) throws RemoteException {
     Parcel data = Parcel.obtain();
     data.writeInterfaceToken(IApplicationThread.descriptor);
     data.writeStrongBinder(token);
     info.writeToParcel(data, 0);
     compatInfo.writeToParcel(data, 0);
     data.writeInt(processState);
     try {
         // 发送 SCHEDULE_CREATE_SERVICE_TRANSACTION 指令。
         mRemote.transact(SCHEDULE_CREATE_SERVICE_TRANSACTION, data, null,
         IBinder.FLAG_ONEWAY);
     } catch (TransactionTooLargeException e) {
         Log.e("CREATE_SERVICE", "Binder failure starting service; service=" + info);
         throw e;
     }
     data.recycle();
}
```

### ApplicationThreadNative

```java
// ApplicationThreadNative.java

public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
    switch (code) {
    case SCHEDULE_CREATE_SERVICE_TRANSACTION: {
        data.enforceInterface(IApplicationThread.descriptor);
        IBinder token = data.readStrongBinder();
        ServiceInfo info = ServiceInfo.CREATOR.createFromParcel(data);
        CompatibilityInfo compatInfo = CompatibilityInfo.CREATOR.createFromParcel(data);
        int processState = data.readInt();
        scheduleCreateService(token, info, compatInfo, processState);
        return true;
    }
    ...
}
```

### ApplicationThread

```java
// ActivityThread.java - class ApplicationThread

public final void scheduleCreateService(IBinder token,
        ServiceInfo info, CompatibilityInfo compatInfo, int processState) {
    updateProcessState(processState, false);
    CreateServiceData s = new CreateServiceData();
    s.token = token;
    s.info = info;
    s.compatInfo = compatInfo;

    sendMessage(H.CREATE_SERVICE, s);
}
```

### ActivityThread.H

```java
// ActivityThread.java - class H

public void handleMessage(Message msg) {
    if (DEBUG_MESSAGES) Slog.v(TAG, ">>> handling: " + codeToString(msg.what));
    switch (msg.what) {
    ...
    case CREATE_SERVICE:
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "serviceCreate");
        handleCreateService((CreateServiceData)msg.obj);
        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        break;
    ...
}
```

### ActivityThread

```java
// ActivityThread.java

private void handleCreateService(CreateServiceData data) {
    // 如果我们在转到后台后准备好 gc，我们又回来了，那么跳过它。
    unscheduleGcIdler();

    // 获取 Apk 信息。
    LoadedApk packageInfo = getPackageInfoNoCheck(
            data.info.applicationInfo, data.compatInfo);
    Service service = null;
    try {
        // 创建 service 实例。
        java.lang.ClassLoader cl = packageInfo.getClassLoader();
        service = (Service) cl.loadClass(data.info.name).newInstance();
    } catch (Exception e) {
        if (!mInstrumentation.onException(service, e)) {
            throw new RuntimeException(
                "Unable to instantiate service " + data.info.name
                + ": " + e.toString(), e);
        }
    }

    try {
        if (localLOGV) Slog.v(TAG, "Creating service " + data.info.name);

        // 创建 Context。
        ContextImpl context = ContextImpl.createAppContext(this, packageInfo);
        context.setOuterContext(service);

        // 确保创建 Application。
        Application app = packageInfo.makeApplication(false, mInstrumentation);
        service.attach(context, this, data.info.name, data.token, app,
                ActivityManagerNative.getDefault());
        // 回调 Service 的 onCreate 方法。
        service.onCreate();
        // 记录 Service。
        mServices.put(data.token, service);
        try {
            // 通知完成启动，将结束前面发送的启动延迟的消息。
            ActivityManagerNative.getDefault().serviceDoneExecuting(
                    data.token, SERVICE_DONE_EXECUTING_ANON, 0, 0);
        } catch (RemoteException e) {
            // nothing to do.
        }
    } catch (Exception e) {
        if (!mInstrumentation.onException(service, e)) {
            throw new RuntimeException(
                "Unable to create service " + data.info.name
                + ": " + e.toString(), e);
        }
    }
}
```

可以看到，逻辑还是比较清晰的。

看一下 `ActivityManagerNative.getDefault().serviceDoneExecuting` 方法，它会通过 IPC 进入 AMS。

```java
ActivityManagerProxy -> ActivityManagerNative -> ActivityManagerService
```

### ActivityManagerService

```java
// ActivityManagerService.java

public void serviceDoneExecuting(IBinder token, int type, int startId, int res) {
    synchronized(this) {
        if (!(token instanceof ServiceRecord)) {
            Slog.e(TAG, "serviceDoneExecuting: Invalid service token=" + token);
            throw new IllegalArgumentException("Invalid service token");
        }
        mServices.serviceDoneExecutingLocked((ServiceRecord)token, type, startId, res);
    }
}

```

### ActiveServices

```java
// ActiveServices.java

void serviceDoneExecutingLocked(ServiceRecord r, int type, int startId, int res) {
    boolean inDestroying = mDestroyingServices.contains(r);
    if (r != null) {
        if (type == ActivityThread.SERVICE_DONE_EXECUTING_START) {
            r.callStart = true;
            switch (res) {
                case Service.START_STICKY_COMPATIBILITY:
                case Service.START_STICKY: {
                    ...
                }
                case Service.START_NOT_STICKY: {
                    ...
                }
                case Service.START_REDELIVER_INTENT: {
                    ...
                }
                case Service.START_TASK_REMOVED_COMPLETE: {
                    ...
                }
                default:
                    throw new IllegalArgumentException(
                            "Unknown service start result: " + res);
            }
            if (res == Service.START_STICKY_COMPATIBILITY) {
                r.callStart = false;
            }
        } else if (type == ActivityThread.SERVICE_DONE_EXECUTING_STOP) {
            ...
        }
        final long origId = Binder.clearCallingIdentity();
        // 看这里。
        serviceDoneExecutingLocked(r, inDestroying, inDestroying);
        Binder.restoreCallingIdentity(origId);
    } else {
        Slog.w(TAG, "Done executing unknown service from pid "
                + Binder.getCallingPid());
    }
}
```

```java
// ActiveServices.java

private void serviceDoneExecutingLocked(ServiceRecord r, boolean inDestroying,
        boolean finishing) {
    if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "<<< DONE EXECUTING " + r
            + ": nesting=" + r.executeNesting
            + ", inDestroying=" + inDestroying + ", app=" + r.app);
    else if (DEBUG_SERVICE_EXECUTING) Slog.v(TAG_SERVICE_EXECUTING,
            "<<< DONE EXECUTING " + r.shortName);
    r.executeNesting--;
    if (r.executeNesting <= 0) {
        if (r.app != null) {
            if (DEBUG_SERVICE) Slog.v(TAG_SERVICE,
                    "Nesting at 0 of " + r.shortName);
            r.app.execServicesFg = false;
            r.app.executingServices.remove(r);
            if (r.app.executingServices.size() == 0) {
                if (DEBUG_SERVICE || DEBUG_SERVICE_EXECUTING) Slog.v(TAG_SERVICE_EXECUTING,
                        "No more executingServices of " + r.shortName);
                // 移除了延时任务执行的消息。
                mAm.mHandler.removeMessages(ActivityManagerService.SERVICE_TIMEOUT_MSG, r.app);
            } else if (r.executeFg) {
                // 需要重新评估应用程序是否仍需要处于前台。
                for (int i=r.app.executingServices.size()-1; i>=0; i--) {
                    if (r.app.executingServices.valueAt(i).executeFg) {
                        r.app.execServicesFg = true;
                        break;
                    }
                }
            }
            if (inDestroying) {
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE,
                        "doneExecuting remove destroying " + r);
                mDestroyingServices.remove(r);
                r.bindings.clear();
            }
            mAm.updateOomAdjLocked(r.app);
        }
        r.executeFg = false;
        if (r.tracker != null) {
            r.tracker.setExecuting(false, mAm.mProcessStats.getMemFactorLocked(),
                    SystemClock.uptimeMillis());
            if (finishing) {
                r.tracker.clearCurrentOwner(r, false);
                r.tracker = null;
            }
        }
        if (finishing) {
            if (r.app != null && !r.app.persistent) {
                r.app.services.remove(r);
            }
            r.app = null;
        }
    }
}
```

这里如果在启动允许的延时时间内移除，就不会执行应用启动延迟的任务，系统不会弹出应用未响应的提示。

最后回到上面，看 `sendServiceArgsLocked` 如何通知 Service 的 `onStartCommond` 方法：

```java
// ActiveServices.java

private final void sendServiceArgsLocked(ServiceRecord r, boolean execInFg,
        boolean oomAdjusted) throws TransactionTooLargeException {
    final int N = r.pendingStarts.size();
    if (N == 0) {
        return;
    }

    while (r.pendingStarts.size() > 0) {
        Exception caughtException = null;
        ServiceRecord.StartItem si;
        try {
            si = r.pendingStarts.remove(0);
            if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Sending arguments to: "
                    + r + " " + r.intent + " args=" + si.intent);
            if (si.intent == null && N > 1) {
                // If somehow we got a dummy null intent in the middle,
                // then skip it.  DO NOT skip a null intent when it is
                // the only one in the list -- this is to support the
                // onStartCommand(null) case.
                continue;
            }
            si.deliveredTime = SystemClock.uptimeMillis();
            r.deliveredStarts.add(si);
            si.deliveryCount++;
            if (si.neededGrants != null) {
                mAm.grantUriPermissionUncheckedFromIntentLocked(si.neededGrants,
                        si.getUriPermissionsLocked());
            }
            bumpServiceExecutingLocked(r, execInFg, "start");
            if (!oomAdjusted) {
                oomAdjusted = true;
                mAm.updateOomAdjLocked(r.app);
            }
            int flags = 0;
            if (si.deliveryCount > 1) {
                flags |= Service.START_FLAG_RETRY;
            }
            if (si.doneExecutingCount > 0) {
                flags |= Service.START_FLAG_REDELIVERY;
            }
            // 安排应用进程执行 onStartCommond 的回调任务。
            r.app.thread.scheduleServiceArgs(r, si.taskRemoved, si.id, flags, si.intent);
        } catch (TransactionTooLargeException e) {
            if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Transaction too large: intent="
                    + si.intent);
            caughtException = e;
        } catch (RemoteException e) {
            // Remote process gone...  we'll let the normal cleanup take care of this.
            if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Crashed while sending args: " + r);
            caughtException = e;
        } catch (Exception e) {
            Slog.w(TAG, "Unexpected exception", e);
            caughtException = e;
        }

        if (caughtException != null) {
            // Keep nesting count correct
            final boolean inDestroying = mDestroyingServices.contains(r);
            serviceDoneExecutingLocked(r, inDestroying, inDestroying);
            if (caughtException instanceof TransactionTooLargeException) {
                throw (TransactionTooLargeException)caughtException;
            }
            break;
        }
    }
}
```

上面的 `app.thread.scheduleServiceArgs` 最终会到达 `ActivityThread` 的 `handleServiceArgs` 方法执行 `onStartCommond` 的回调。

### ActivityThread

```java
// ActivityThread.java

private void handleServiceArgs(ServiceArgsData data) {
    Service s = mServices.get(data.token);
    if (s != null) {
        try {
            if (data.args != null) {
                data.args.setExtrasClassLoader(s.getClassLoader());
                data.args.prepareToEnterProcess();
            }
            int res;
            if (!data.taskRemoved) {
                // 回调 Service 的 onStartCommond 方法。
                res = s.onStartCommand(data.args, data.flags, data.startId);
            } else {
                s.onTaskRemoved(data.args);
                res = Service.START_TASK_REMOVED_COMPLETE;
            }

            QueuedWork.waitToFinish();

            try {
                ActivityManagerNative.getDefault().serviceDoneExecuting(
                        data.token, SERVICE_DONE_EXECUTING_START, data.startId, res);
            } catch (RemoteException e) {
                // nothing to do.
            }
            ensureJitEnabled();
        } catch (Exception e) {
            if (!mInstrumentation.onException(s, e)) {
                throw new RuntimeException(
                        "Unable to start service " + s
                        + " with " + data.args + ": " + e.toString(), e);
            }
        }
    }
}
```

到这里就走完了启动服务的流程，Service 的启动流程相比 Activtiy 的启动流程要简单，因为不需要 UI 的支持，不过 Service 还有一个绑定的流程。

## 时序图

