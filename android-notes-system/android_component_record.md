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
    boolean pendingUiClean;     // 是否想清除显示 UI 的资源？
    boolean hasAboveClient;     // 使用 BIND_ABOVE_CLIENT 绑定，因此想降低。
    boolean treatLikeActivity;  // 使用 BIND_TREAT_LIKE_ACTIVITY 绑定。
    boolean bad;                // 如果在 bad 进程列表中被禁用，则为 true。
    boolean killedByAm;         // 当 proc 已被 AMS 终止，而不是用于 RAM 时为 true。
    boolean killed;             // 一旦我们知道该 process 已被终止，便为 true。
    boolean procStateChanged;   // 追踪我们是否更改了 “setAdj”。
    boolean reportedInteraction;// 我们是否已告知 usage stats 是一种交互。
    long fgInteractionTime;     // 当我们为了交互而成为前台的时间。
    String waitingToKill;       // 在 bg 中等待进程被杀死，原因是。
    IBinder forcingToForeground;// 强制此进程变为前台的 token。
    int adjSeq;                 // 用于标识 oom_adj 分配周期的序队列 ID。
    int lruSeq;                 // 用于标识 LRU 更新周期的队列 ID。
    CompatibilityInfo compat;   // 上次使用的兼容模式。
    IBinder.DeathRecipient deathRecipient; // 谁在监听 binder 死亡。
    ComponentName instrumentationClass;    // 安装到 instrument 的类。
    ApplicationInfo instrumentationInfo;   // 正在测试的应用程序。
    String instrumentationProfileFile;     // 保存配置文件的位置。
    IInstrumentationWatcher instrumentationWatcher; // 谁在等待。
    IUiAutomationConnection instrumentationUiAutomationConnection; // 使用 UI 自检 API 的连接。
    Bundle instrumentationArguments;// 给我们的。
    ComponentName instrumentationResultClass;// 从 instrumentationClass 复制。
    boolean usingWrapper;       // 在进程启动并附加包装器时设置为 true。
    BroadcastRecord curReceiver;// 当前在应用程序中运行的广播接收器。
    long lastWakeTime;          // proc 上次检查时保持唤醒锁的时间
    long lastCpuTime;           // proc 上次检查时运行的 cpu 时间。
    long curCpuTime;            // proc 最近运行的 CPU 时间。
    long lastRequestedGc;       // 上一次要求该应用执行 gc 的时间。
    long lastLowMemory;         // 我们上次告诉应用程序内存不足的时间。
    boolean reportLowMemory;    // 等待低内存上报时设置为 true。
    boolean empty;              // 这是一个空的后台进程吗？
    boolean cached;             // 这是一个缓存的进程吗？
    String adjType;             // Debugging：影响oom_adj的主要原因。
    int adjTypeCode;            // Debugging：向 app 上报的 adj 代码。
    Object adjSource;           // Debugging：选项相关的对象。
    int adjSourceProcState;     // Debugging：adjSource 进程的 proc 状态。
    Object adjTarget;           // Debugging：影响 oom_adj 的目标组件。
    Runnable crashHandler;      // 在进程崩溃时要调用的可选的本地处理程序。

    // 此进程中所有运行的 activity。
    final ArrayList<ActivityRecord> activities = new ArrayList<>();
    // 此进程中所有运行的 ServiceRecord。
    final ArraySet<ServiceRecord> services = new ArraySet<>();
    // 当前正在执行代码的 Service（需要保持在前台）。
    final ArraySet<ServiceRecord> executingServices = new ArraySet<>();
    // 此进程持有的所有 ConnectionRecord。
    final ArraySet<ConnectionRecord> connections = new ArraySet<>();
    // 该进程注册的所有 IIntentReceivers。
    final ArraySet<ReceiverList> receivers = new ArraySet<>();
    // class (String) -> ContentProviderRecord
    final ArrayMap<String, ContentProviderRecord> pubProviders = new ArrayMap<>();
    // 进程正在使用的所有 ContentProviderRecord。
    final ArrayList<ContentProviderConnection> conProviders = new ArrayList<>();

    boolean execServicesFg;     // 我们需要在前台执行服务吗？
    boolean persistent;         // 始终保持该应用程序运行？
    boolean crashing;           // 我们正在 crash 吗？
    Dialog crashDialog;         // 由于崩溃而显示的对话框。
    boolean forceCrashReport;   // 不允许正常自动关闭崩溃对话框和报告用户界面？
    boolean notResponding;      // 该应用程序是否有未响应 Dialog？
    Dialog anrDialog;           // 由于没有响应应用程序而显示 Dialog。
    boolean removed;            // 是否已从设备中删除应用包？
    boolean debugging;          // 应用是否已启动进行调试？
    boolean waitedForDebugger;  // 进程是否显示等待调试器 Dialog？
    Dialog waitDialog;          // 当前等待调试器 Dialog。
    
    String shortStringName;     // 缓存 toShortString() 结果。
    String stringName;          // 缓存 toString() 结果。
    
    // 当应用程序进入错误状态时，将生成并存储这些报告。
    // 一切正常后，它们将为 “null”。
    ActivityManager.ProcessErrorStateInfo crashingReport;
    ActivityManager.ProcessErrorStateInfo notRespondingReport;

    // 该向谁通知该错误。
    // 这通常是软件包安装应用程序中的活动。
    ComponentName errorReportReceiver;
}
```

## ServiceRecord

ServiceRecord 是记录 Service 组件信息的数据结构，下面枚举出它的成员变量：

```java
// ServiceRecord.java

final class ServiceRecord extends Binder {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ServiceRecord" : TAG_AM;

    // 放弃之前的最大传送尝试次数。
    static final int MAX_DELIVERY_COUNT = 3;

    // 放弃执行之前执行失败的最大次数。
    static final int MAX_DONE_EXECUTING_COUNT = 6;

    final ActivityManagerService ams;
    final BatteryStatsImpl.Uid.Pkg.Serv stats;
    final ComponentName name; // service 组件。
    final String shortName;   // name.flattenToShortString()。
    final Intent.FilterComparison intent;
                              // 用来寻找 service 的原始 intent。
    final ServiceInfo serviceInfo;
                              // 和 service 相关的所有信息。
    final ApplicationInfo appInfo;
                              // service 所在 app 的相关信息。
    final int userId;         // 运行该服务的用户。
    final String packageName; // 实现 intent 组件的包。
    final String processName; // 组件所在进程。
    final String permission;  // 访问 service 所需权限。
    final boolean exported;   // 来自 ServiceInfo.exported。
    final Runnable restarter; // 用于安排重启服务的时间。
    final long createTime;    // service 被创建的时间。
    final ArrayMap<Intent.FilterComparison, IntentBindRecord> bindings
            = new ArrayMap<Intent.FilterComparison, IntentBindRecord>();
                            // 对于此 service 所有活动的绑定者。
    final ArrayMap<IBinder, ArrayList<ConnectionRecord>> connections
            = new ArrayMap<IBinder, ArrayList<ConnectionRecord>>();
                            // IBinder -> 所有绑定客户端的 ConnectionRecord。

    ProcessRecord app;      // service 正在运行的地方，或为 null。
    ProcessRecord isolatedProc; // 跟踪隔离的进程（如果需要）。
    ProcessStats.ServiceState tracker; // 跟踪 service 执行，可能为 null。
    ProcessStats.ServiceState restartTracker; // 跟踪 service 重启。
    boolean delayed;        // 我们是否正在等待在后台启动此 service？
    boolean isForeground;   // service 当前处于后后台模式吗？
    int foregroundId;       // 上一个前台请求的 Notification id。
    Notification foregroundNoti; // 前台状态下的 Notification 记录。
    long lastActivity;      // 上次这个 service 的一些活动。
    long startingBgTimeout;  // 我们将其安排为延迟启动的时间。
    boolean startRequested; // 有人明确地请求启动？
    boolean delayedStop;    // service 已停止，但延迟启动？
    boolean stopIfKilled;   // 上次 onstart() 说如果 service 被杀死就停止？
    boolean callStart;      // 上次 onStart() 要求在重新启动时总是被调用。
    int executeNesting;     // 持有前台的未完成操作数。
    boolean executeFg;      // 我们应该在前台执行吗？
    long executingStart;    // 上次执行请求的启动时间。
    boolean createdFromFg;  // 该 service 上次是由于前台进程调用而创建的吗？
    int crashCount;         // proc 因 service 运行而崩溃的次数。
    int totalRestartCount;  // 我们不得不重新启动的次数。
    int restartCount;       // 连续执行的重新启动次数。
    long restartDelay;      // 延迟到下一次重新启动尝试。
    long restartTime;       // 上次重新启动的时间。
    long nextRestartTime;   // restartDelay 到期的时间。
    boolean destroying;     // 在我们开始销毁 service 时设置。
    long destroyTime;       // 销毁的开始时间。

    String stringName;      // 缓存 toString。
    
    private int lastStartId; // 最近启动请求的标识符。
}
```

## ActivityRecord

ActivityRecord 是记录 Activity 组件信息的数据结构，下面枚举出它的成员变量：

```java
final class ActivityRecord {
    private static final boolean SHOW_ACTIVITY_START_TIME = true;
    final public static String RECENTS_PACKAGE_NAME = "com.android.systemui.recents";

    private static final String ATTR_ID = "id";
    private static final String TAG_INTENT = "intent";
    private static final String ATTR_USERID = "user_id";
    private static final String TAG_PERSISTABLEBUNDLE = "persistable_bundle";
    private static final String ATTR_LAUNCHEDFROMUID = "launched_from_uid";
    private static final String ATTR_LAUNCHEDFROMPACKAGE = "launched_from_package";
    private static final String ATTR_RESOLVEDTYPE = "resolved_type";
    private static final String ATTR_COMPONENTSPECIFIED = "component_specified";
    static final String ACTIVITY_ICON_SUFFIX = "_activity_icon_";

    final ActivityManagerService service;  // 所有者。
    final IApplicationToken.Stub appToken; // window manager token。
    final ActivityInfo info;               // 和我相关的信息。
    final ApplicationInfo appInfo;         // activity 所在应用的信息。
    final int launchedFromUid;             // 始终是启动 Activity 的 uid。
    final String launchedFromPackage;      // 始终是启动 activity 的包。
    final int userId;                      // 该用户运行哪个用户？
    final Intent intent;                   // 创建我们的原始 intent。
    final ComponentName realActivity;      // intent 的组件或 target 的别名。
    final String shortComponentName;       // intent 的短组件名。
    final String resolvedType;             // 原始调用者;
    final String packageName;              // 实现 intent 组件的包。
    final String processName;              // 该组件要运行的进程。
    final String taskAffinity;             // 依照 ActivityInfo.taskAffinity
    final boolean stateNotNeeded;          // 依照 ActivityInfo.flags
    boolean fullscreen;                    // 覆盖了全屏?
    final boolean noDisplay;               // activity 没有显示?
    final boolean componentSpecified;      // 调用者是否指定了显式组件？
    final boolean rootVoiceInteraction;    // 这是语音交互的根 activity 吗。

    static final int APPLICATION_ACTIVITY_TYPE = 0;
    static final int HOME_ACTIVITY_TYPE = 1;
    static final int RECENTS_ACTIVITY_TYPE = 2;
    int mActivityType;

    CharSequence nonLocalizedLabel;  // Package Manager 中的标签信息。
    int labelRes;           // Package Manager 中的标签信息。
    int icon;               // activity 的 icon 的资源 id.
    int logo;               // activity 的 logo 的资源 id.
    int theme;              // activity 的 theme 的资源 id.
    int realTheme;          // 我们使用的实际 theme 资源，永远不会是 0。
    int windowFlags;        // 预览窗口的自定义窗口 flags.
    TaskRecord task;        // 所在栈。
    long createTime = System.currentTimeMillis();
    long displayStartTime;  // 我们开始启动此 activity 的时间。
    long fullyDrawnStartTime; // 我们开始启动此 activity 的时间。
    long startTime;         // 上次启动此 activity 的时间。
    long lastVisibleTime;   // 上次此活动可见的时间。
    long cpuTimeAtResume;   // 恢复 activity 时本机进程的 cpu 时间。
    long pauseTime;         // 上次我们开始暂停 activity 的时间。
    long launchTickTime;    // 启动 tick 消息的基准时间。
    Configuration configuration; // 配置 activity 最后一次由活动堆栈在“覆盖的配置”中运行
    // WARNING: Reference points to {@link ActivityStack#mOverrideConfig}, 
    // 因此切勿直接更改其内部状态。
    Configuration stackConfigOverride;
    CompatibilityInfo compat;// 上次使用的兼容模式。
    ActivityRecord resultTo; // 是由谁启动的, 所以我们会得到回复。
    final String resultWho;  // 用于 resultTo 的附加标识符。
    final int requestCode;   // 请求者给的请求码 (resultTo)
    ArrayList<ResultInfo> results; // 我们收到的待处理的 ActivityResult objs。
    HashSet<WeakReference<PendingIntentRecord>> pendingResults; // 该 activity 的所有 pending intents。
    ArrayList<ReferrerIntent> newIntents; // single-top 模式的任何新的 pending intents。
    ActivityOptions pendingOptions;   // 最近给出的选项。
    ActivityOptions returningOptions; // 通过 convertToTranslucent 返回的选项。
    AppTimeTracker appTimeTracker;    // 设置是否在此应 app/task/activity 中追踪时间。
    HashSet<ConnectionRecord> connections; // 我们持有的所有 ConnectionRecord。
    UriPermissionOwner uriPermissions; // 当前的特殊 URI 访问权限。
    ProcessRecord app;      // 如果不是 null，则是本机 app。
    ActivityState state;    // 我们目前的状态
    Bundle  icicle;         // 上次保存的 activity 状态。
    PersistableBundle persistentState; // 上一个持久保存的 activity 状态。
    boolean frontOfTask;    // 这是所在任务的根 activity 吗？
    boolean launchFailed;   // 如果启动失败则设置，第二次则尝试中止。
    boolean haveState;      // 我们是否获得了最后一个 activity 状态？
    boolean stopped;        // activity 暂停完成了吗？
    boolean delayedResume;  // 由于停止了应用程序切换而还未 resume？
    boolean finishing;      // activity 在待 finish 列表中？
    boolean configDestroy;  // 需要因配置更改而销毁？
    int configChangeFlags;  // 哪些配置值已更改。
    boolean keysPaused;     // 是否已暂停 key 分发？
    int launchMode;         // 启动模式活动属性。
    boolean visible;        // 是否需要显示此 activity 的窗口?
    boolean sleeping;       // 我们有通知 activity 睡眠吗？
    boolean nowVisible;     // 此 activity 的窗口是否可见。
    boolean idle;           // activity 闲置了吗？
    boolean hasBeenLaunched;// 此 activity 有没有被启动过?
    boolean frozenBeforeDestroy;// 已被冻结但尚未销毁。
    boolean immersive;      // 沉浸模式（如果有可能，请勿中断）。
    boolean forceNewConfig; // 下次使用新配置强制重新创建 activity。
    int launchCount;        // 自上次状态以来的启动次数。
    long lastLaunchTime;    // 此 activity 上次启动时间。
    ArrayList<ActivityContainer> mChildContainers = new ArrayList<ActivityContainer>();

    String stringName;      // 缓存 toString()。

    private boolean inHistory;  // 我们在历史栈列表吗?
    final ActivityStackSupervisor mStackSupervisor;
    boolean mStartingWindowShown = false;
    ActivityContainer mInitialActivityContainer;

    TaskDescription taskDescription; // 此 activity 的最近信息。
    boolean mLaunchTaskBehind; // 此 activity 正在使用 ActivityOptions.setLaunchTaskBehind 
                               //主动启动，一旦启动完成，该 activity 将被清除。
}
```

## ContentProviderRecord

## BroadcastRecord