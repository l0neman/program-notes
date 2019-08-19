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
            // 
            if (DEBUG_DELAYED_SERVICE) Slog.v(TAG_SERVICE, "Potential start delay of "
                    + r + " in " + proc);
            if (r.delayed) {
                // This service is already scheduled for a delayed start; just leave
                // it still waiting.
                if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE, "Continuing to delay: " + r);
                return r.name;
            }
            if (smap.mStartingBackground.size() >= mMaxStartingBackground) {
                // Something else is starting, delay!
                Slog.i(TAG_SERVICE, "Delaying start of: " + r);
                smap.mDelayedStartList.add(r);
                r.delayed = true;
                return r.name;
            }
            if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE, "Not delaying: " + r);
            addToStarting = true;
        } else if (proc.curProcState >= ActivityManager.PROCESS_STATE_SERVICE) {
            // We slightly loosen when we will enqueue this new service as a background
            // starting service we are waiting for, to also include processes that are
            // currently running other services or receivers.
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

    ServiceMap smap = getServiceMap(userId);
    final ComponentName comp = service.getComponent();
    if (comp != null) {
        r = smap.mServicesByName.get(comp);
    }
    if (r == null) {
        Intent.FilterComparison filter = new Intent.FilterComparison(service);
        r = smap.mServicesByIntent.get(filter);
    }
    if (r == null) {
        try {
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
                r = new ServiceRecord(mAm, ss, name, filter, sInfo, callingFromFg, res);
                res.setService(r);
                smap.mServicesByName.put(name, r);
                smap.mServicesByIntent.put(filter, r);

                // Make sure this component isn't in the pending list.
                for (int i=mPendingServices.size()-1; i>=0; i--) {
                    ServiceRecord pr = mPendingServices.get(i);
                    if (pr.serviceInfo.applicationInfo.uid == sInfo.applicationInfo.uid
                            && pr.name.equals(name)) {
                        mPendingServices.remove(i);
                    }
                }
            }
        } catch (RemoteException ex) {
            // pm is in same process, this will never happen.
        }
    }
    if (r != null) {
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

