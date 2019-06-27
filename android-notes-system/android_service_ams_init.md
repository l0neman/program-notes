# ActivityManagerService 服务初始化分析

## 前言

ActivityManagerService(AMS) 服务是 Android 系统核心服务之一，它负责管理四大组件。如果需要深入了解四大组件的初始化以及启动过程，那么首先需要了解 AMS 的工作原理。

下面基于 Android6.0 的源码分析 AMS 的初始化过程中做了哪些工作。

## SystemServer

AMS 的初始化起始点位于 `SystemServer` 类中的 `startBootstrapServices` 方法中。`SystemServer` 的入口为 `public static void main(String[] args)` 方法。

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

AMS 的主要初始化工作都在 `startBootstrapServices` 方法中：

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

## SystemServiceManager

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

## AMS.Lifecycle

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

回到上面的 `startBootstrapServices` 方法，在 `getService` 之后，又调用了如下与 AMS 相关方法：

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

那么开始逐行分析，首先是  AMS 的构造器

## ActivityManagerService

```java
// ActivityManagerService.java

// Note: This method is invoked on the main thread but may need to attach various
// handlers to other threads.  So take care to be explicit about the looper.
public ActivityManagerService(Context systemContext) {
    mContext = systemContext;
    mFactoryTest = FactoryTest.getMode();
    mSystemThread = ActivityThread.currentActivityThread();

    Slog.i(TAG, "Memory class: " + ActivityManager.staticGetMemoryClass());

    mHandlerThread = new ServiceThread(TAG,
            android.os.Process.THREAD_PRIORITY_FOREGROUND, false /*allowIo*/);
    mHandlerThread.start();
    mHandler = new MainHandler(mHandlerThread.getLooper());
    mUiHandler = new UiHandler();

    mFgBroadcastQueue = new BroadcastQueue(this, mHandler,
            "foreground", BROADCAST_FG_TIMEOUT, false);
    mBgBroadcastQueue = new BroadcastQueue(this, mHandler,
            "background", BROADCAST_BG_TIMEOUT, true);
    mBroadcastQueues[0] = mFgBroadcastQueue;
    mBroadcastQueues[1] = mBgBroadcastQueue;

    mServices = new ActiveServices(this);
    mProviderMap = new ProviderMap(this);

    // TODO: Move creation of battery stats service outside of activity manager service.
    File dataDir = Environment.getDataDirectory();
    File systemDir = new File(dataDir, "system");
    systemDir.mkdirs();
    mBatteryStatsService = new BatteryStatsService(systemDir, mHandler);
    mBatteryStatsService.getActiveStatistics().readLocked();
    mBatteryStatsService.scheduleWriteToDisk();
    mOnBattery = DEBUG_POWER ? true
            : mBatteryStatsService.getActiveStatistics().getIsOnBattery();
    mBatteryStatsService.getActiveStatistics().setCallback(this);

    mProcessStats = new ProcessStatsService(this, new File(systemDir, "procstats"));

    mAppOpsService = new AppOpsService(new File(systemDir, "appops.xml"), mHandler);

    mGrantFile = new AtomicFile(new File(systemDir, "urigrants.xml"));

    // User 0 is the first and only user that runs at boot.
    mStartedUsers.put(UserHandle.USER_OWNER, new UserState(UserHandle.OWNER, true));
    mUserLru.add(UserHandle.USER_OWNER);
    updateStartedUserArrayLocked();

    GL_ES_VERSION = SystemProperties.getInt("ro.opengles.version",
        ConfigurationInfo.GL_ES_VERSION_UNDEFINED);

    mTrackingAssociations = "1".equals(SystemProperties.get("debug.track-associations"));

    mConfiguration.setToDefaults();
    mConfiguration.setLocale(Locale.getDefault());

    mConfigurationSeq = mConfiguration.seq = 1;
    mProcessCpuTracker.init();

    mCompatModePackages = new CompatModePackages(this, systemDir, mHandler);
    mIntentFirewall = new IntentFirewall(new IntentFirewallInterface(), mHandler);
    mRecentTasks = new RecentTasks(this);
    mStackSupervisor = new ActivityStackSupervisor(this, mRecentTasks);
    mTaskPersister = new TaskPersister(systemDir, mStackSupervisor, mRecentTasks);

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

    Watchdog.getInstance().addMonitor(this);
    Watchdog.getInstance().addThread(mHandler);
}
```

# todo 😭

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

```java
public void setInstaller(Installer installer) {
    mInstaller = installer;
}
```

`setInstaller` 也是保存了 `installer` 的对象，那么先继续往下看。

