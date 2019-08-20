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

如果 Service 所在进程已启动，则进入下一步 `realStartServiceLocked` 启动 Service。否则使用 `mAm.startProcessLocked` 方法启动进程，进程启动后，会辗转调用到 `ActivityManagerService` 的 `attachApplicationLocked` 方法，它会通知目标进程绑定 Application，如果检查到有等待启动的 