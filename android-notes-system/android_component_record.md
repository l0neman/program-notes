# Android 组件记录类型分析

## 前言

在分析 Android 系统四大组件的交互过程中，包含很多组件状态的记录数据结构类型，了解这些类型有助于更深层次的理解 Android 系统组件的工作原理，系统与应用进程的交互过程，以及对四大组件管理服务 ActivityManagerService 的理解。

下面基于 Android 6.0 系统源码对各个记录类型进行枚举。

## ProcessRecord

ProcessRecord 不是组件的记录类型，它是应用程序进程的记录类型，它是四大组件和 AMS 交互过程中及其重要的一个数据结构。

下面是 ProcessRecord 类型的所有成员变量。

```java
// ProcessRecord.java

final class ProcessRecord {
private static final String TAG = TAG_WITH_CLASS_NAME ? "ProcessRecord" : TAG_AM;

    private final BatteryStatsImpl mBatteryStats; // 在何处收集运行时统计信息。
    final ApplicationInfo info; // 进程中第一个应用程序的所有相关信息。
    final boolean isolated;     // 如果这是一个特殊的隔离进程，则为 true。
    final int uid;              // 进程的 uid，如果是隔离进程，则可能和 `info` 中 uid 不同。
    final int userId;           // 进程所属用户。
    final String processName;   // 进程名称。
    // 进程中运行的软件包列表。
    final ArrayMap<String, ProcessStats.ProcessStateHolder> pkgList = new ArrayMap<>();
    UidRecord uidRecord;        // 进程 uid 的所有状态。
    ArraySet<String> pkgDeps;   // 我们依赖的其他软件包
    IApplicationThread thread;  // 仅当 `persistent` 为 true 时，时机 proce……可能为 null
                                // (这种情况下，我们正在启动应用程序)。
    ProcessStats.ProcessState baseProcessTracker;
    BatteryStatsImpl.Uid.Proc curProcBatteryStats;
    int pid;                    // 这个应用程序的进程；如果没有则为 0。
    int[] gids;                 // 进程启动时的 gid。
    String requiredAbi;         // 进程启动时的 ABI。
    String instructionSet;      // 进程启动时的指令集。
    boolean starting;           // 如果进程正在启动则为 true。
    long lastActivityTime;      // 用于管理 LRU 列表。
    long lastPssTime;           // 上次我们取得的 pss 数据。
    long nextPssTime;           // 下次我们需要的 pss 数据。
    long lastStateTime;         // 上次 setProcState 有更改。
    long initialIdlePss;        // 处于空闲维护进程的初始内存 pss。
    long lastPss;               // 上次计算的内存 pss.
    long lastCachedPss;         // 处于缓存状态时最后计算的 pss。
    int maxAdj;                 // 此进程的最大 OOM 调整。
    int curRawAdj;              // 此进程对于 OOM 的无限制调整。
    int setRawAdj;              // 此进程上次设置的 OOM 无限制调整。
    int curAdj;                 // 此进程的当前 OOM 调整。
    int setAdj;                 // 此进程的上次 OOM 调整。
    int curSchedGroup;          // 当前所需的调度类。
    int setSchedGroup;          // 上次设置的后台调度类型。
    int trimMemoryLevel;        // 上次选择的内存调整级别。
    int curProcState = PROCESS_STATE_NONEXISTENT; // 当前计算的进程状态。
    int repProcState = PROCESS_STATE_NONEXISTENT; // 上次上报的进程状态。
    int setProcState = PROCESS_STATE_NONEXISTENT; // 进程跟踪器最后设置的进程状态。
    int pssProcState = PROCESS_STATE_NONEXISTENT; // 当前请求的 pss。
    boolean serviceb;           // 当前进程在服务 B 列表中。
    boolean serviceHighRam;     // 由于使用了 RAM，因此我们强制服务 B 列表
    boolean setIsForeground;    // 最后设置时正在运行前台 UI？
    boolean notCachedSinceIdle; // 自上次空闲以来，此进程是否未处于缓存状态？
    boolean hasClientActivities;  // 有活动的客户端服务吗。
    boolean hasStartedServices; // 在此进程中是否正在运行任何已启动的服务？
    boolean foregroundServices; // 运行了任何前台服务？
    boolean foregroundActivities; // 运行了任何前台 Activity？
    boolean repForegroundActivities; // 上次上报的前台 activity。
    boolean systemNoUi;         // 这是一个系统应用，但当前未显示 UI。
    boolean hasShownUi;         // 自启动以来，是否已在此进程中显示 UI？
    boolean pendingUiClean;     // Want to clean up resources from showing UI?
    boolean hasAboveClient;     // Bound using BIND_ABOVE_CLIENT, so want to be lower
    boolean treatLikeActivity;  // Bound using BIND_TREAT_LIKE_ACTIVITY
    boolean bad;                // True if disabled in the bad process list
    boolean killedByAm;         // True when proc has been killed by activity manager, not for RAM
    boolean killed;             // True once we know the process has been killed
    boolean procStateChanged;   // Keep track of whether we changed 'setAdj'.
    boolean reportedInteraction;// Whether we have told usage stats about it being an interaction
    long fgInteractionTime;     // When we became foreground for interaction purposes
    String waitingToKill;       // Process is waiting to be killed when in the bg, and reason
    IBinder forcingToForeground;// Token that is forcing this process to be foreground
    int adjSeq;                 // Sequence id for identifying oom_adj assignment cycles
    int lruSeq;                 // Sequence id for identifying LRU update cycles
    CompatibilityInfo compat;   // last used compatibility mode
    IBinder.DeathRecipient deathRecipient; // Who is watching for the death.
    ComponentName instrumentationClass;// class installed to instrument app
    ApplicationInfo instrumentationInfo; // the application being instrumented
    String instrumentationProfileFile; // where to save profiling
    IInstrumentationWatcher instrumentationWatcher; // who is waiting
    IUiAutomationConnection instrumentationUiAutomationConnection; // Connection to use the UI introspection APIs.
    Bundle instrumentationArguments;// as given to us
    ComponentName instrumentationResultClass;// copy of instrumentationClass
    boolean usingWrapper;       // Set to true when process was launched with a wrapper attached
    BroadcastRecord curReceiver;// receiver currently running in the app
    long lastWakeTime;          // How long proc held wake lock at last check
    long lastCpuTime;           // How long proc has run CPU at last check
    long curCpuTime;            // How long proc has run CPU most recently
    long lastRequestedGc;       // When we last asked the app to do a gc
    long lastLowMemory;         // When we last told the app that memory is low
    boolean reportLowMemory;    // Set to true when waiting to report low mem
    boolean empty;              // Is this an empty background process?
    boolean cached;             // Is this a cached process?
    String adjType;             // Debugging: primary thing impacting oom_adj.
    int adjTypeCode;            // Debugging: adj code to report to app.
    Object adjSource;           // Debugging: option dependent object.
    int adjSourceProcState;     // Debugging: proc state of adjSource's process.
    Object adjTarget;           // Debugging: target component impacting oom_adj.
    Runnable crashHandler;      // Optional local handler to be invoked in the process crash.

    // all activities running in the process
    final ArrayList<ActivityRecord> activities = new ArrayList<>();
    // all ServiceRecord running in this process
    final ArraySet<ServiceRecord> services = new ArraySet<>();
    // services that are currently executing code (need to remain foreground).
    final ArraySet<ServiceRecord> executingServices = new ArraySet<>();
    // All ConnectionRecord this process holds
    final ArraySet<ConnectionRecord> connections = new ArraySet<>();
    // all IIntentReceivers that are registered from this process.
    final ArraySet<ReceiverList> receivers = new ArraySet<>();
    // class (String) -> ContentProviderRecord
    final ArrayMap<String, ContentProviderRecord> pubProviders = new ArrayMap<>();
    // All ContentProviderRecord process is using
    final ArrayList<ContentProviderConnection> conProviders = new ArrayList<>();

    boolean execServicesFg;     // do we need to be executing services in the foreground?
    boolean persistent;         // always keep this application running?
    boolean crashing;           // are we in the process of crashing?
    Dialog crashDialog;         // dialog being displayed due to crash.
    boolean forceCrashReport;   // suppress normal auto-dismiss of crash dialog & report UI?
    boolean notResponding;      // does the app have a not responding dialog?
    Dialog anrDialog;           // dialog being displayed due to app not resp.
    boolean removed;            // has app package been removed from device?
    boolean debugging;          // was app launched for debugging?
    boolean waitedForDebugger;  // has process show wait for debugger dialog?
    Dialog waitDialog;          // current wait for debugger dialog
    
    String shortStringName;     // caching of toShortString() result.
    String stringName;          // caching of toString() result.
    
    // These reports are generated & stored when an app gets into an error condition.
    // They will be "null" when all is OK.
    ActivityManager.ProcessErrorStateInfo crashingReport;
    ActivityManager.ProcessErrorStateInfo notRespondingReport;

    // Who will be notified of the error. This is usually an activity in the
    // app that installed the package.
    ComponentName errorReportReceiver;
}
```



## ServiceRecord

## ActivityRecord

## ContentProviderRecord

## BroadcastRecord