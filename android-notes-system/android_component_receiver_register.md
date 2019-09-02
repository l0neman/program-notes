# Android Receiver 注册流程分析d

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
                scheduler = mMainThread.getHandler();
            }
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

