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

### Instrumentation

由于 activity 这种启动方式参数较为全面，那么现在从它开始分析 activity 启动流程。

```java
// Instrumentation.java

public ActivityResult execStartActivity(
        Context who, IBinder contextThread, IBinder token, Activity target,
        Intent intent, int requestCode, Bundle options) {
    IApplicationThread whoThread = (IApplicationThread) contextThread;
    Uri referrer = target != null ? target.onProvideReferrer() : null;
    
    if (referrer != null) {
        intent.putExtra(Intent.EXTRA_REFERRER, referrer);
    }
    
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