# ActivityManagerService 初始化分析

- [前言](#前言)
- [startBootstrapServices](#startbootstrapservices)
  - [SystemServer](#systemserver)
  - [SystemServiceManager](#systemservicemanager)
  - [AMS.Lifecycle](#ams.lifecycle)
  - [ActivityManagerService](#activitymanagerservice)
  - [ActivityThread](#activitythread)
  - [ContextImpl](#contextimpl)
  - [LoadedApk](#loadedapk)
- [startCoreServices](#startcoreservices)
- [startOtherServices](#startotherservices)

## 前言

ActivityManagerService(AMS) 服务是 Android 系统核心服务之一，它负责管理四大组件。如果需要深入了解四大组件的初始化以及启动过程，那么首先需要了解 AMS 的工作原理。

AMS 服务在 Android 系统中持续运行，动态管理四大组件之间的交互和一些子服务的初始化工作，在 Android 系统启动过程中，AMS 也会随之启动，在处理这些任务之前需要做一些初始化工作，了解这些工作时分析 AMS 运行原理的基础。

下面基于 Android6.0 的源码分析 AMS 的初始化过程中做了哪些工作。

## startBootstrapServices 

负责初始化 AMS 的重要方法有三个，下面分三部分分析。

首先是 `startBootstrapServices` 方法

### SystemServer

SystemServer 进程由 Zygote fork 而来，它承载了 android framework 的核心服务，AMS 将在它的 `main` 方法中进行初始化。

AMS 的初始化起始点位于 `SystemServer` 类中的 `startBootstrapServices` 方法中。`SystemServer` 进程被创建后，最后会进入它的入口 `public static void main(String[] args)` 方法。

```java
// SystemServer.java - class SystemServer

public static void main(String[] args) {
    new SystemServer().run();
}
```

```java
// SystemServer.java - class SystemServer

private void run() {
    ...
    // Start services.
    try {
        startBootstrapServices();
        startCoreServices();
        startOtherServices();
    } catch (Throwable ex) {
        Slog.e("System", "******************************************");
        Slog.e("System", "************ Failure starting system services", ex);
        throw ex;
    }
    ...
}
```

AMS 的主要初始化工作都在 `try..catch` 所包含的 3 个方法中，首先看第一个方法：

```java
// SystemServer.java - class SystemServer

private void startBootstrapServices() {
    // Wait for installd to finish starting up so that it has a chance to
    // create critical directories such as /data/user with the appropriate
    // permissions.  We need this to complete before we initialize other services.
    Installer installer = mSystemServiceManager.startService(Installer.class);

    // AMS 开始初始化。
    mActivityManagerService = mSystemServiceManager.startService(
            ActivityManagerService.Lifecycle.class).getService();
    mActivityManagerService.setSystemServiceManager(mSystemServiceManager);
    mActivityManagerService.setInstaller(installer);

    // Power manager needs to be started early because other services need it.
    // Native daemons may be watching for it to be registered so it must be ready
    // to handle incoming binder calls immediately (including being able to verify
    // the permissions for those calls).
    mPowerManagerService = mSystemServiceManager.startService(PowerManagerService.class);

    // 激活电源管理服务后，让 AMS 去初始化电源管理服务。
    mActivityManagerService.initPowerManagement();

    // Manages LEDs and display backlight so we need it to bring up the display.
    mSystemServiceManager.startService(LightsService.class);

    // Display manager is needed to provide display metrics before package manager
    // starts up.
    mDisplayManagerService = mSystemServiceManager.startService(DisplayManagerService.class);

    // We need the default display before we can initialize the package manager.
    mSystemServiceManager.startBootPhase(SystemService.PHASE_WAIT_FOR_DEFAULT_DISPLAY);

    // Only run "core" apps if we're encrypting the device.
    String cryptState = SystemProperties.get("vold.decrypt");
    if (ENCRYPTING_STATE.equals(cryptState)) {
        Slog.w(TAG, "Detected encryption in progress - only parsing core apps");
        mOnlyCore = true;
    } else if (ENCRYPTED_STATE.equals(cryptState)) {
        Slog.w(TAG, "Device encrypted - only parsing core apps");
        mOnlyCore = true;
    }

    // Start the package manager.
    Slog.i(TAG, "Package Manager");
    mPackageManagerService = PackageManagerService.main(mSystemContext, installer,
            mFactoryTestMode != FactoryTest.FACTORY_TEST_OFF, mOnlyCore);
    mFirstBoot = mPackageManagerService.isFirstBoot();
    mPackageManager = mSystemContext.getPackageManager();

    Slog.i(TAG, "User Service");
    ServiceManager.addService(Context.USER_SERVICE, UserManagerService.getInstance());

    // Initialize attribute cache used to cache resources from packages.
    AttributeCache.init(mSystemContext);

    // 初始化其他相关系统服务。
    mActivityManagerService.setSystemProcess();

    // The sensor service needs access to package manager service, app ops
    // service, and permissions service, therefore we start it after them.
    startSensorService();
}
```

### SystemServiceManager

首先看 `mSystemServiceManager.startService`。

```java
// SystemServiceManager.java - class SystemServiceManager

@SuppressWarnings("unchecked")
public SystemService startService(String className) {
    final Class<SystemService> serviceClass;
    try {
        serviceClass = (Class<SystemService>)Class.forName(className);
    } catch (ClassNotFoundException ex) {
        Slog.i(TAG, "Starting " + className);
        throw new RuntimeException("Failed to create service " + className
                + ": service class not found, usually indicates that the caller should "
                + "have called PackageManager.hasSystemFeature() to check whether the "
                + "feature is available on this device before trying to start the "
                + "services that implement it", ex);
    }
    return startService(serviceClass);
}
```

```java
// SystemServiceManager.java - class SystemServiceManager
// 需要接收生命周期时间的服务。
private final ArrayList<SystemService> mServices = new ArrayList<SystemService>();

...
@SuppressWarnings("unchecked")
public <T extends SystemService> T startService(Class<T> serviceClass) {
    final String name = serviceClass.getName();
    Slog.i(TAG, "Starting " + name);

    // Create the service.
    if (!SystemService.class.isAssignableFrom(serviceClass)) {
        throw new RuntimeException("Failed to create " + name
                + ": service must extend " + SystemService.class.getName());
    }
    final T service;
    try {
        // 创建 service 的实例。
        Constructor<T> constructor = serviceClass.getConstructor(Context.class);
        service = constructor.newInstance(mContext);
    } catch (InstantiationException ex) {
        throw new RuntimeException("Failed to create service " + name
                + ": service could not be instantiated", ex);
    } catch (IllegalAccessException ex) {
        throw new RuntimeException("Failed to create service " + name
                + ": service must have a public constructor with a Context argument", ex);
    } catch (NoSuchMethodException ex) {
        throw new RuntimeException("Failed to create service " + name
                + ": service must have a public constructor with a Context argument", ex);
    } catch (InvocationTargetException ex) {
        throw new RuntimeException("Failed to create service " + name
                + ": service constructor threw an exception", ex);
    }

    // 加入到 mServices 列表。
    mServices.add(service);

    // 调用 server 的 onStart 方法。
    try {
        service.onStart();
    } catch (RuntimeException ex) {
        throw new RuntimeException("Failed to start service " + name
                + ": onStart threw an exception", ex);
    }
    return service;
}
```

上面做了两件事，创建 `service` 对象并调用它的 `onStart` 方法，`service` 是一个 `ActivityManagerService.Lifecycle` 类型，看一下它的实现。

### AMS.Lifecycle

```java
// ActivityManagerService.Lifecycle

public static final class Lifecycle extends SystemService {
    private final ActivityManagerService mService;

    public Lifecycle(Context context) {
        super(context);
        mService = new ActivityManagerService(context);
    }

    @Override
    public void onStart() {
        mService.start();
    }

    public ActivityManagerService getService() {
        return mService;
    }
}
```

它是对 `ActivityManagerService` 的包装，所以上面的工作即，创建了 AMS 服务的对象，以及调用了 AMS 的 `start` 方法。

回到上面的 `startBootstrapServices` 方法，在 `getService` 之后，又调用了如下与 AMS 相关方法，精简上面的代码如下：

```java
// startBootstrapServices

mActivityManagerService = new ActivityManagerSerivce(context);
mActivityManagerService.start();

// 设置服务管理器对象。
mActivityManagerService.setSystemServiceManager(mSystemServiceManager);
// 设置安装服务。
mActivityManagerService.setInstaller(installer);
...
mActivityManagerService.initPowerManagement();
...
mActivityManagerService.setSystemProcess();
...
```

那么开始逐一分析，首先是  AMS 的构造器

### ActivityManagerService

```java
// ActivityManagerService.java

// Note: This method is invoked on the main thread but may need to attach various
// handlers to other threads.  So take care to be explicit about the looper.
public ActivityManagerService(Context systemContext) {
    mContext = systemContext;
    // 是否为工厂测试模式。
    mFactoryTest = FactoryTest.getMode();
    mSystemThread = ActivityThread.currentActivityThread();

    Slog.i(TAG, "Memory class: " + ActivityManager.staticGetMemoryClass());

    // 创建以 TAG 为名字的 HandlerThread， TAG = "ActivityManager"，并启动。
    mHandlerThread = new ServiceThread(TAG,
            android.os.Process.THREAD_PRIORITY_FOREGROUND, false /*allowIo*/);
    mHandlerThread.start();
    
    // 创建一个运行在 "ActivityManager" 线程的 Handler。
    mHandler = new MainHandler(mHandlerThread.getLooper());
    
    // 创建 UI Handler，内部创建 UiThread（HandlerThread）线程，名称为 "android.ui"。
    mUiHandler = new UiHandler();

    // 创建名前台广播处理队列，设置 10 秒的超时时间。
    mFgBroadcastQueue = new BroadcastQueue(this, mHandler,
            "foreground", BROADCAST_FG_TIMEOUT, false);
    // 创建名后台广播处理队列，设置 60 秒的超时时间。
    mBgBroadcastQueue = new BroadcastQueue(this, mHandler,
            "background", BROADCAST_BG_TIMEOUT, true);
    // 保存两个广播处理队列。
    mBroadcastQueues[0] = mFgBroadcastQueue;
    mBroadcastQueues[1] = mBgBroadcastQueue;

    // 创建 ActiveServices 对象。
    mServices = new ActiveServices(this);
    // 创建以权限（名称）和类为键的 ContentProvider 集合，用以分辨系统和用户 provider。 
    mProviderMap = new ProviderMap(this);

    // TODO: Move creation of battery stats service outside of activity manager service.
    // 创建 /data/system/ 目录。
    File dataDir = Environment.getDataDirectory();
    File systemDir = new File(dataDir, "system");
    systemDir.mkdirs();
    // 创建电池管理服务。
    mBatteryStatsService = new BatteryStatsService(systemDir, mHandler);
    mBatteryStatsService.getActiveStatistics().readLocked();
    mBatteryStatsService.scheduleWriteToDisk();
    mOnBattery = DEBUG_POWER ? true
            : mBatteryStatsService.getActiveStatistics().getIsOnBattery();
    mBatteryStatsService.getActiveStatistics().setCallback(this);

    // 创建进程管理服务，映射在 /data/system/procstats/ 目录中。
    mProcessStats = new ProcessStatsService(this, new File(systemDir, "procstats"));

    // 权限管理服务。
    mAppOpsService = new AppOpsService(new File(systemDir, "appops.xml"), mHandler);

    mGrantFile = new AtomicFile(new File(systemDir, "urigrants.xml"));

    // 用户 0 是第一个也是唯一一个在系统启动时运行的用户.
    mStartedUsers.put(UserHandle.USER_OWNER, new UserState(UserHandle.OWNER, true));
    mUserLru.add(UserHandle.USER_OWNER);
    updateStartedUserArrayLocked();

    GL_ES_VERSION = SystemProperties.getInt("ro.opengles.version",
        ConfigurationInfo.GL_ES_VERSION_UNDEFINED);

    mTrackingAssociations = "1".equals(SystemProperties.get("debug.track-associations"));

    // 设置 Configuration 为默认配置。
    mConfiguration.setToDefaults();
    mConfiguration.setLocale(Locale.getDefault());

    mConfigurationSeq = mConfiguration.seq = 1;
    // 初始化 CPU 跟踪器。
    mProcessCpuTracker.init();
    mCompatModePackages = new CompatModePackages(this, systemDir, mHandler);
    // 网络防火墙。
    mIntentFirewall = new IntentFirewall(new IntentFirewallInterface(), mHandler);
    // 最近任务任务列表管理。
    mRecentTasks = new RecentTasks(this);
    // Activity 栈管理。
    mStackSupervisor = new ActivityStackSupervisor(this, mRecentTasks);
    mTaskPersister = new TaskPersister(systemDir, mStackSupervisor, mRecentTasks);

    // 创建了 CpuTracker 线程用于跟踪 Cpu 状态。
    mProcessCpuThread = new Thread("CpuTracker") {
        @Override
        public void run() {
            while (true) {
                try {
                    try {
                        synchronized(this) {
                            final long now = SystemClock.uptimeMillis();
                            long nextCpuDelay = (mLastCpuTime.get()+MONITOR_CPU_MAX_TIME)-now;
                            long nextWriteDelay = (mLastWriteTime+BATTERY_STATS_TIME)-now;
                            //Slog.i(TAG, "Cpu delay=" + nextCpuDelay
                            //        + ", write delay=" + nextWriteDelay);
                            if (nextWriteDelay < nextCpuDelay) {
                                nextCpuDelay = nextWriteDelay;
                            }
                            if (nextCpuDelay > 0) {
                                mProcessCpuMutexFree.set(true);
                                this.wait(nextCpuDelay);
                            }
                        }
                    } catch (InterruptedException e) {
                    }
                    updateCpuStatsNow();
                } catch (Exception e) {
                    Slog.e(TAG, "Unexpected exception collecting process stats", e);
                }
            }
        }
    };

    // 初始化看门狗，它是监测系统程序正常运行的服务。
    Watchdog.getInstance().addMonitor(this);
    Watchdog.getInstance().addThread(mHandler);
}
```

了解到 AMS 的构造器中做了如下工作:

1. 启动了 `ActivtityManager`，`android.ui`，`CpuTracker` 三个线程。
2. 创建第一个用户，以及电池，权限，进程管理相关服务对象，activity 任务管理相关。
3. 创建前台和后台广播处理队列，CPU 监控以及负责进程错误管理的看门狗服务。

现在回到上面的 `startBootstrapServices` 方法中，下一句代码是：

```java
mActivityManagerService.setSystemServiceManager(mSystemServiceManager).
```

首先，`mSystemServiceManager` 是一个 `SystemServiceManager` 对象，它是在 `startBootstrapServices` 方法之前被创建的，它负责创建服务，并管理服务的生命周期事件，例如前面 `startService` 方法做的工作。

```java
// Create the system service manager.
mSystemServiceManager = new SystemServiceManager(mSystemContext);
```

```java
public void setSystemServiceManager(SystemServiceManager mgr) {
    mSystemServiceManager = mgr;
}
```

`setSystemServiceManager` 只是保存了对象到 AMS 中。

继续下一句是：

```java
mActivityManagerService.setInstaller(installer);
```

其中的 `installer`，在上面进行了初始化：

```java
Installer installer = mSystemServiceManager.startService(Installer.class);
```

它是系统负责安装的服务。

```java
public void setInstaller(Installer installer) {
    mInstaller = installer;
}
```

`setInstaller` 也是保存了 `installer` 的对象，那么先继续往下看，至于这些类的具体作用，需要等到用到他们时再做具体分析。

```java
// 初始化电源管理。
mActivityManagerService.initPowerManagement();
```

```java
// ActivityManagerService.java

public void initPowerManagement() {
    mStackSupervisor.initPowerManagement();
    mBatteryStatsService.initPowerManagement();
    mLocalPowerManager = LocalServices.getService(PowerManagerInternal.class);
    PowerManager pm = (PowerManager)mContext.getSystemService(Context.POWER_SERVICE);
    mVoiceWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "*voice*");
    mVoiceWakeLock.setReferenceCounted(false);
}
```

最后看一下：

```java
mActivityManagerService.setSystemProcess();
```

```java
// ActivityManagerService.java

public void setSystemProcess() {
    try {
        // 注册自身和若干服务。
        ServiceManager.addService(Context.ACTIVITY_SERVICE, this, true);
        ServiceManager.addService(ProcessStats.SERVICE_NAME, mProcessStats);
        ServiceManager.addService("meminfo", new MemBinder(this));
        ServiceManager.addService("gfxinfo", new GraphicsBinder(this));
        ServiceManager.addService("dbinfo", new DbBinder(this));
        if (MONITOR_CPU_USAGE) {
            ServiceManager.addService("cpuinfo", new CpuBinder(this));
        }
        ServiceManager.addService("permission", new PermissionController(this));
        ServiceManager.addService("processinfo", new ProcessInfoService(this));

        // 获取系统应用包信息。
        ApplicationInfo info = mContext.getPackageManager().getApplicationInfo(
                "android", STOCK_PM_FLAGS);
        // 安装系统应用信息。
        mSystemThread.installSystemApplicationInfo(info, getClass().getClassLoader());

        synchronized (this) {
            // 创建正在运行的进程状态信息存储对象。
            ProcessRecord app = newProcessRecordLocked(info, info.processName, false, 0);
            // 设置进程信息。
            app.persistent = true;
            app.pid = MY_PID;
            app.maxAdj = ProcessList.SYSTEM_ADJ;
            app.makeActive(mSystemThread.getApplicationThread(), mProcessStats);
            synchronized (mPidsSelfLocked) {
                // 保存 ProcessRecord 进程记录对象。
                mPidsSelfLocked.put(app.pid, app);
            }
            // 更新进程 Lru 列表。
            updateLruProcessLocked(app, false, null);
            updateOomAdjLocked();
        }
    } catch (PackageManager.NameNotFoundException e) {
        throw new RuntimeException(
                "Unable to find android system package", e);
    }
}
```

前面向 `ServiceManager` 注册了若干系统服务，直接看下面的逻辑：

`info` 为系统包 `"android"` 的应用包信息。

```java
mSystemThread.installSystemApplicationInfo(info, getClass().getClassLoader());
```

其中 `mSystemThread` 在前面初始化如下，为当前主线程类型。

```java
mSystemThread = ActivityThread.currentActivityThread();
```

### ActivityThread

```java
// ActivityThread.java

public void installSystemApplicationInfo(ApplicationInfo info, ClassLoader classLoader) {
    synchronized (this) {
        getSystemContext().installSystemApplicationInfo(info, classLoader);

        // give ourselves a default profiler
        mProfiler = new Profiler();
    }
}
```

其中 `getSystemContext` 是一个 `ContextImpl` 对象，使用了单例模式进行创建：

```java
// ActivityThread.java

public ContextImpl getSystemContext() {
    synchronized (this) {
        if (mSystemContext == null) {
            mSystemContext = ContextImpl.createSystemContext(this);
        }
        return mSystemContext;
    }
}
```

### ContextImpl

```java
// ContextImpl.java

static ContextImpl createSystemContext(ActivityThread mainThread) {
    LoadedApk packageInfo = new LoadedApk(mainThread);
    ContextImpl context = new ContextImpl(null, mainThread, packageInfo, null, null, false, null, null, Display.INVALID_DISPLAY);
    context.mResources.updateConfiguration(context.mResourcesManager.getConfiguration(), context.mResourcesManager.getDisplayMetricsLocked());
    return context;
}
```

继续看它的 `installSystemApplicationInfo` 方法实现：

```java
// ContextImpl.java

void installSystemApplicationInfo(ApplicationInfo info, ClassLoader classLoader) {
    mPackageInfo.installSystemApplicationInfo(info, classLoader);
}
```

### LoadedApk

```java
// LoadedApk.java 

void installSystemApplicationInfo(ApplicationInfo info, ClassLoader classLoader) {
    assert info.packageName.equals("android");
    mApplicationInfo = info;
    mClassLoader = classLoader;
}
```

其中的 `mPackageInfo` 对象是一个 `LoadedApk` 类型，它在 `ContextImpl` 的构造器中被初始化。

```java
private ContextImpl(ContextImpl container, ActivityThread mainThread,
                    LoadedApk packageInfo, IBinder activityToken, UserHandle user, boolean restricted,
                    Display display, Configuration overrideConfiguration, int createDisplayWithId) {
    ...
    mPackageInfo = packageInfo;
    ...
}
```

在上面的 `ContextImpl` 的 `createSystemContext` 方法被创建：

```java
LoadedApk packageInfo = new LoadedApk(mainThread);
```

`installSystemApplicationInfo` 方法是为了将系统包（名称为 "android"）的信息保存起来，

```java
void installSystemApplicationInfo(ApplicationInfo info, ClassLoader classLoader) {
    assert info.packageName.equals("android");
    mApplicationInfo = info;
    mClassLoader = classLoader;
}
```

总结 AMS 的 `setSystemProcess` 方法，做了如下工作：

1. 注册相关系统服务；2. 保存系统包信息；3. 设置进程状态。

## startCoreServices

下面看 `startCoreServices` 方法。

```java
// 启动一些在系统启动过程中非必要的服务。
private void startCoreServices() {
    // 电池电量服务。
    mSystemServiceManager.startService(BatteryService.class);

    // 应用使用信息统计服务。
    mSystemServiceManager.startService(UsageStatsService.class);
    mActivityManagerService.setUsageStatsManager(
        LocalServices.getService(UsageStatsManagerInternal.class));
    mPackageManagerService.getUsageStatsIfNoPackageUsageInfo();

    // 监控系统 WebView 状态。
    mSystemServiceManager.startService(WebViewUpdateService.class);
}

```

`startCoreSerices` 方法 AMS 相关的不多。

## startOtherServices

看第 3 个方法，这个方法代码行数较较多，800 行左右，大部分都是为了注册其他系统服务，这里省略部分逻辑，凸出 AMS 所做的初始化工作。

```java
// SystemServer.java

private void startOtherServices() {
    // 声明相关服务对象。
    AccountManagerService accountManager = null;
    ContentService contentService = null;
    VibratorService vibrator = null;
    IAlarmManager alarm = null;
    ...
    // 获取相关配置。
    boolean disableStorage = SystemProperties.getBoolean("config.disable_storage", false);
    boolean disableBluetooth = SystemProperties.getBoolean("config.disable_bluetooth", false);
    ...
    // 注册系统服务。
    Slog.i(TAG, "Reading configuration...");
    SystemConfig.getInstance();

    Slog.i(TAG, "Scheduling Policy");
    ServiceManager.addService("scheduling_policy", new SchedulingPolicyService());

    mSystemServiceManager.startService(TelecomLoaderService.class);

    Slog.i(TAG, "Telephony Registry");
    telephonyRegistry = new TelephonyRegistry(context);
    ServiceManager.addService("telephony.registry", telephonyRegistry);

    Slog.i(TAG, "Entropy Mixer");
    entropyMixer = new EntropyMixer(context);

    mContentResolver = context.getContentResolver();

    Slog.i(TAG, "Camera Service");
    mSystemServiceManager.startService(CameraService.class);
 	...
    // 1. 安装系统 provider。
    mActivityManagerService.installSystemProviders();
    ...
    // Before things start rolling, be sure we have decided whether
    // we are in safe mode.
    final boolean safeMode = wm.detectSafeMode();
    if (safeMode) {
        mActivityManagerService.enterSafeMode();
        // Disable the JIT for the system_server process
        VMRuntime.getRuntime().disableJitCompilation();
    } else {
        // Enable the JIT for the system_server process
        VMRuntime.getRuntime().startJitCompilation();
    }
    ...
    // Needed by DevicePolicyManager for initialization
    // 设置系统启动引导阶段。
    mSystemServiceManager.startBootPhase(SystemService.PHASE_LOCK_SETTINGS_READY);
    mSystemServiceManager.startBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);
    mActivityManagerService.systemReady(new Runnable() {
        @Override
        public void run() {
            Slog.i(TAG, "Making services ready");
            mSystemServiceManager.startBootPhase(
                SystemService.PHASE_ACTIVITY_MANAGER_READY);

            // 监控 native 异常。
            try {
                mActivityManagerService.startObservingNativeCrashes();
            } catch (Throwable e) {
                reportWtf("observing native crashes", e);
            }
            ...
            // 启动 Watchdog
            Watchdog.getInstance().start();

            // It is now okay to let the various system services start their
            // third party code...
            mSystemServiceManager.startBootPhase(
                SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);

            // 调用各个服务的 systemRunning 方法。
            try {
                if (wallpaperF != null) wallpaperF.systemRunning();
            } catch (Throwable e) {
                reportWtf("Notifying WallpaperService running", e);
            }
            ...

            try {
                if (mmsServiceF != null) mmsServiceF.systemRunning();
            } catch (Throwable e) {
                reportWtf("Notifying MmsService running", e);
            }
        }
    });
}
```

这个方法内注册化了大量的服务，这里重点看一下几个方法的调用，首先在 1 处：

```java
mActivityManagerService.installSystemProviders();
```

```java
public final void installSystemProviders() {
    List<ProviderInfo> providers;
    synchronized (this) {
        ProcessRecord app = mProcessNames.get("system", Process.SYSTEM_UID);
        providers = generateApplicationProvidersLocked(app);
        if (providers != null) {
            for (int i=providers.size()-1; i>=0; i--) {
                ProviderInfo pi = (ProviderInfo)providers.get(i);
                // 移除非系统的 provider
                if ((pi.applicationInfo.flags&ApplicationInfo.FLAG_SYSTEM) == 0) {
                    Slog.w(TAG, "Not installing system proc provider " + pi.name
                            + ": not system .apk");
                    providers.remove(i);
                }
            }
        }
    }
    // 安装系统 provider。
    if (providers != null) {
        mSystemThread.installSystemProviders(providers);
    }

    mCoreSettingsObserver = new CoreSettingsObserver(this);

    //mUsageStatsService.monitorPackages();
}
```

大概看一下 `installSystemProviders` 方法

```java
// ActivityThread.java

public final void installSystemProviders(List<ProviderInfo> providers) {
    if (providers != null) {
        installContentProviders(mInitialApplication, providers);
    }
}
```

```java
// ActivityThread.java

private void installContentProviders(
    Context context, List<ProviderInfo> providers) {
    final ArrayList<IActivityManager.ContentProviderHolder> results =
        new ArrayList<IActivityManager.ContentProviderHolder>();

    for (ProviderInfo cpi : providers) {
        if (DEBUG_PROVIDER) {
            StringBuilder buf = new StringBuilder(128);
            buf.append("Pub ");
            buf.append(cpi.authority);
            buf.append(": ");
            buf.append(cpi.name);
            Log.i(TAG, buf.toString());
        }
        IActivityManager.ContentProviderHolder cph = installProvider(context, null, cpi,
                                                                     false /*noisy*/, true /*noReleaseNeeded*/, true /*stable*/);
        if (cph != null) {
            cph.noReleaseNeeded = true;
            results.add(cph);
        }
    }

    try {
        // 发布 ContentProvider。
        ActivityManagerNative.getDefault().publishContentProviders(
            getApplicationThread(), results);
    } catch (RemoteException ex) {
    }
}
```

安装 provider 后，即可在应用程序中访问系统 provider。

下面再看一下 `mActivityManagerService.systemReady` 这个方法：

```java
// ActivityManagerService.java

public void systemReady(final Runnable goingCallback) {
    synchronized(this) {
        if (mSystemReady) {
            // If we're done calling all the receivers, run the next "boot phase" passed in
            // by the SystemServer
            // 第一次调用不会进入此分支。
            if (goingCallback != null) {
                goingCallback.run();
            }
            return;
        }

        mLocalDeviceIdleController
                = LocalServices.getService(DeviceIdleController.LocalService.class);

        // Make sure we have the current profile info, since it is needed for
        // security checks.
        updateCurrentProfileIdsLocked();

        // 清理然后恢复最近任务。
        mRecentTasks.clear();
        mRecentTasks.addAll(mTaskPersister.restoreTasksLocked());
        mRecentTasks.cleanupLocked(UserHandle.USER_ALL);
        mTaskPersister.startPersisting();

        // Check to see if there are any update receivers to run.
        if (!mDidUpdate) {
            if (mWaitingUpdate) {
                return;
            }
            final ArrayList<ComponentName> doneReceivers = new ArrayList<ComponentName>();
            // 是否正在等待升级。
            mWaitingUpdate = deliverPreBootCompleted(new Runnable() {
                public void run() {
                    synchronized (ActivityManagerService.this) {
                        mDidUpdate = true;
                    }
                    showBootMessage(mContext.getText(
                            R.string.android_upgrading_complete),
                            false);
                    writeLastDonePreBootReceivers(doneReceivers);
                    systemReady(goingCallback);
                }
            }, doneReceivers, UserHandle.USER_OWNER);

            if (mWaitingUpdate) {
                return;
            }
            mDidUpdate = true;
        }

        mAppOpsService.systemReady();
        mSystemReady = true;
    }

    ArrayList<ProcessRecord> procsToKill = null;
    synchronized(mPidsSelfLocked) {
        // 非 persistent 进程，加入待杀进程列表。
        for (int i=mPidsSelfLocked.size()-1; i>=0; i--) {
            ProcessRecord proc = mPidsSelfLocked.valueAt(i);
            if (!isAllowedWhileBooting(proc.info)){
                if (procsToKill == null) {
                    procsToKill = new ArrayList<ProcessRecord>();
                }
                procsToKill.add(proc);
            }
        }
    }

    synchronized(this) {
        if (procsToKill != null) {
            // 杀死列表中的进程。
            for (int i=procsToKill.size()-1; i>=0; i--) {
                ProcessRecord proc = procsToKill.get(i);
                Slog.i(TAG, "Removing system update proc: " + proc);
                removeProcessLocked(proc, true, false, "system update done");
            }
        }

        // Now that we have cleaned up any update processes, we
        // are ready to start launching real processes and know that
        // we won't trample on them any more.
        mProcessesReady = true;
    }

    Slog.i(TAG, "System now ready");
    EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_AMS_READY,
        SystemClock.uptimeMillis());

    synchronized(this) {
        // Make sure we have no pre-ready processes sitting around.

        if (mFactoryTest == FactoryTest.FACTORY_TEST_LOW_LEVEL) {
            ResolveInfo ri = mContext.getPackageManager()
                    .resolveActivity(new Intent(Intent.ACTION_FACTORY_TEST),
                            STOCK_PM_FLAGS);
            CharSequence errorMsg = null;
            if (ri != null) {
                ActivityInfo ai = ri.activityInfo;
                ApplicationInfo app = ai.applicationInfo;
                if ((app.flags&ApplicationInfo.FLAG_SYSTEM) != 0) {
                    mTopAction = Intent.ACTION_FACTORY_TEST;
                    mTopData = null;
                    mTopComponent = new ComponentName(app.packageName,
                            ai.name);
                } else {
                    errorMsg = mContext.getResources().getText(
                            com.android.internal.R.string.factorytest_not_system);
                }
            } else {
                errorMsg = mContext.getResources().getText(
                        com.android.internal.R.string.factorytest_no_action);
            }
            if (errorMsg != null) {
                mTopAction = null;
                mTopData = null;
                mTopComponent = null;
                Message msg = Message.obtain();
                msg.what = SHOW_FACTORY_ERROR_MSG;
                msg.getData().putCharSequence("msg", errorMsg);
                mUiHandler.sendMessage(msg);
            }
        }
    }

    retrieveSettings();
    loadResourcesOnSystemReady();

    synchronized (this) {
        readGrantedUriPermissionsLocked();
    }

    if (goingCallback != null) goingCallback.run();

    mBatteryStatsService.noteEvent(BatteryStats.HistoryItem.EVENT_USER_RUNNING_START,
            Integer.toString(mCurrentUserId), mCurrentUserId);
    mBatteryStatsService.noteEvent(BatteryStats.HistoryItem.EVENT_USER_FOREGROUND_START,
            Integer.toString(mCurrentUserId), mCurrentUserId);
    mSystemServiceManager.startUser(mCurrentUserId);

    synchronized (this) {
        if (mFactoryTest != FactoryTest.FACTORY_TEST_LOW_LEVEL) {
            try {
                // 获取所有 persistent 进程并启动。
                List apps = AppGlobals.getPackageManager().
                    getPersistentApplications(STOCK_PM_FLAGS);
                if (apps != null) {
                    int N = apps.size();
                    int i;
                    for (i=0; i<N; i++) {
                        ApplicationInfo info = (ApplicationInfo)apps.get(i);
                        if (info != null && !info.packageName.equals("android")) {
                            // 启动 persistent 进程。
                            addAppLocked(info, false, null /* ABI override */);
                        }
                    }
                }
            } catch (RemoteException ex) {
                // pm is in same process, this will never happen.
            }
        }

        // Start up initial activity.
        mBooting = true;
        // 启动桌面启动器。
        startHomeActivityLocked(mCurrentUserId, "systemReady");

        try {
            if (AppGlobals.getPackageManager().hasSystemUidErrors()) {
                Slog.e(TAG, "UIDs on the system are inconsistent, you need to wipe your"
                        + " data partition or your device will be unstable.");
                mUiHandler.obtainMessage(SHOW_UID_ERROR_MSG).sendToTarget();
            }
        } catch (RemoteException e) {
        }

        if (!Build.isBuildConsistent()) {
            Slog.e(TAG, "Build fingerprint is not consistent, warning user");
            mUiHandler.obtainMessage(SHOW_FINGERPRINT_ERROR_MSG).sendToTarget();
        }

        long ident = Binder.clearCallingIdentity();
        try {
            // 发送 USER_STARTED 广播。
            Intent intent = new Intent(Intent.ACTION_USER_STARTED);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                    | Intent.FLAG_RECEIVER_FOREGROUND);
            intent.putExtra(Intent.EXTRA_USER_HANDLE, mCurrentUserId);
            broadcastIntentLocked(null, null, intent,
                    null, null, 0, null, null, null, AppOpsManager.OP_NONE,
                    null, false, false, MY_PID, Process.SYSTEM_UID, mCurrentUserId);
            // 发送 USER_STARTING 广播。
            intent = new Intent(Intent.ACTION_USER_STARTING);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            intent.putExtra(Intent.EXTRA_USER_HANDLE, mCurrentUserId);
            broadcastIntentLocked(null, null, intent,
                    null, new IIntentReceiver.Stub() {
                        @Override
                        public void performReceive(Intent intent, int resultCode, String data,
                                Bundle extras, boolean ordered, boolean sticky, int sendingUser)
                                throws RemoteException {
                        }
                    }, 0, null, null,
                    new String[] {INTERACT_ACROSS_USERS}, AppOpsManager.OP_NONE,
                    null, true, false, MY_PID, Process.SYSTEM_UID, UserHandle.USER_ALL);
        } catch (Throwable t) {
            Slog.wtf(TAG, "Failed sending first user broadcasts", t);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        mStackSupervisor.resumeTopActivitiesLocked();
        sendUserSwitchBroadcastsLocked(-1, mCurrentUserId);
    }
}
```

回到前面的 `goingCallback` 的执行内容：

```java
// SystemServer

Slog.i(TAG, "WebViewFactory preparation");
// 初始化 webView。
WebViewFactory.prepareWebViewInSystemServer();

try {
    // 启动系统 ui。
    startSystemUi(context);
} catch (Throwable e) {
    reportWtf("starting System UI", e);
}

// 一系列服务的 systemReady。
try {
    if (networkScoreF != null) networkScoreF.systemReady();
} catch (Throwable e) {
    reportWtf("making Network Score Service ready", e);
}
try {
    if (networkManagementF != null) networkManagementF.systemReady();
} catch (Throwable e) {
    reportWtf("making Network Managment Service ready", e);
}
...
// 启动看门狗。
Watchdog.getInstance().start();
// It is now okay to let the various system services start their
// third party code...
mSystemServiceManager.startBootPhase(
    SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);
```

其中启动启动 ui 实现如下：

```java
// SystemServer.java

static final void startSystemUi(Context context) {
    // 启动了 SystemUIService 服务。
    Intent intent = new Intent();
    intent.setComponent(new ComponentName("com.android.systemui",
                                          "com.android.systemui.SystemUIService"));
    //Slog.d(TAG, "Starting service: " + intent);
    context.startServiceAsUser(intent, UserHandle.OWNER);
}
```

总结 `startOtherService` 方法所做的工作：

```java
1. 注册大量系统服务；
2. 安装系统 provider；
3. 清理进程；
4. 通知系统处于 ready 状态；
5. 启动系统 WebView，启动系统 UI；
6. 发送用户启动广播。
```

