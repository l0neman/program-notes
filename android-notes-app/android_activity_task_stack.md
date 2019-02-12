# Android Activity 任务栈

- [activity 选项](#activity-选项)
  * [taskAffinity](#taskaffinity)
  * [allowTaskReparenting](#allowtaskreparenting)
  * [excludeFromRecents](#excludefromrecents)
  * [alwaysRetainTaskState](#alwaysretaintaskstate)
  * [clearTaskOnLaunch](#cleartaskonlaunch)
  * [finishOnTaskLaunch](#finishontasklaunch)
  * [autoRemoveFromRecents](#autoremovefromrecents)
  * [maxRecents](#maxrecents)
  * [noHistory](#nohistory)
- [启动模式-launchMode](#启动模式-launchmode)
  * [standard](#standard)
  * [singleTop](#singletop)
  * [singleTask](#singletask)
  * [singleInstance](#singleinstance)
- [启动模式-IntentFlag](#启动模式-intentflag)
  * [FLAG_ACTIVITY_CLEAR_TASK](#flag_activity_clear_task)
  * [FLAG_ACTIVITY_CLEAR_TOP](#flag_activity_clear_top)
  * [FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS](#flag_activity_exclude_from_recents)
  * [FLAG_ACTIVITY_NEW_TASK](#flag_activity_new_task)
  * [FLAG_ACTIVITY_NO_HISTORY](#flag_activity_no_history)
  * [FLAG_ACTIVITY_SINGLE_TOP](#flag_activity_single_top)
  * [FLAG_ACTIVITY_FORWARD_RESULT](#flag_activity_forward_result)
  * [FLAG_ACTIVITY_MULTIPLE_TASK](#flag_activity_multiple_task)
  * [FLAG_ACTIVITY_NEW_DOCUMENT](#flag_activity_new_document)
  * [FLAG_ACTIVITY_NO_ANIMATION](#flag_activity_no_animation)
  * [FLAG_ACTIVITY_RESET_TASK_IF_NEEDED](#flag_activity_reset_task_if_needed)
  * [FLAG_ACTIVITY_RETAIN_IN_RECENTS](#flag_activity_retain_in_recents)
  * [FLAG_ACTIVITY_TASK_ON_HOME](#flag_activity_task_on_home)

一个 Android 应用中与用户产生交互的一系列 activity 被称为任务，Android 系统使用栈来管理这些 activity，它们的行为如下：

当在桌面点击一个应用图标打开主界面时，如果之前没有打开过这个应用，将会创建一个全新的任务栈，并把主界面的 activity1 作为栈底元素入栈，当用户从主界面依次打开 activity2, activity3 时，它们会被依次压入栈中，此时栈内元素为 activity1, activity2, activity3，用户点击返回键时，activity3将被被执行 finish 操作，同时将会出栈，activity2 恢复，继续点击返回键，直到退回到主界面时，如果再次点击返回键，则应用退出，任务栈被销毁。

不同应用可以指定相同的任务，同一个应用中的 activity 也可存在于多个任务中，还可使用一些选项影响 activity 在任务栈中的行为。更详细的文档可以参考官方指南：

[AndroidAPI指南：任务和返回栈](https://developer.android.com/guide/components/tasks-and-back-stack.html)

下面对和任务栈相关的选项和配置进行详细分析：

## activity 选项

以下选项是在 android 配置文件中的 `<activity/>` 标签下所指定的选项。

### taskAffinity

字符串属性 ，用来指定与 activity 相关联的任务，应用内所有 activity 的默认任务为应用对应的包名，所以指定时应该不同于本应用包名，在这个 activity 指定了 `allowTaskReparenting` 选项时或者启动 activity 的 Intent 带有 `FLAG_ACTIVITY_NEW_TASK` 标记时，activity 可能会被移动到指定的任务栈中。

如果从用户的角度来看，一个 `.apk` 文件包含多个“应用”，可能需要使用 [`taskAffinity`](https://developer.android.com/guide/topics/manifest/activity-element.html#aff) 属性将不同关联分配给与每个“应用”相关的 Activity。

### allowTaskReparenting

指定 activity 所在的任务切换至前台时，activity 能否从该任务切换到相关联的任务，`true` 表示可以转移，默认为  false。

例如，应用1的主 activity a 启动了 应用2的 activity b2，则 b2 和 a 在同一个任务栈中，此时切换到后台，点击桌面图标进入应用2，应用2的主 activity 是 a2，如果 b2 的 allowTaskReparenting 选项开启，则此时显示的是b2，点击返回键后可退到 a2，这是由于应用1在切换到后台时，b2 已被转移到与其相关联的任务栈中，如果 b2 使用 `taskAffinity` 属性指定了与 a2 不同的任务，则不会发生以上的任务切换。

### excludeFromRecents

布尔属性，是否将 activity 启动的任务排除在最近使用的应用列表之外（android 5.0 展示为可滑动清除的卡片列表），此选项仅对任务的根 activity 有效，任务的根 activity 指定此选项后，其所在任务不会在最近应用列表出现。

### alwaysRetainTaskState

布尔属性，是否始终保持 Activity 所在任务的状态，如果为 false，系统可能在一段时间后，清空当前任务（清除根 activity 之上的所有 activity），当用户再次切换到此应用时，只能看到根 activity，如果为 true，则会始终保持切换到后台前的状态。此选项只对任务的根 activity 有效。

### clearTaskOnLaunch

布尔属性，每次用户点击桌面图标打开任务时，都将清除任务的根 activity 之上的所有 activity， 只保留根 activity，如果 activity 中有 `allowTaskReparenting` 选项开启，则该 activity 将会转移到与其相关联的任务中，此选项只对任务的根 activity 有效。

### finishOnTaskLaunch

布尔属性，每次用户点击桌面图标打开任务时，将会关闭开启此选项的 activity，如果 activity 中有 `allowTaskReparenting` 选项开启，优先执行此选项。

### autoRemoveFromRecents

布尔属性，api21可用，指定是否在根 activity 执行 finish 之后，从最近应用列表中移除任务。

### maxRecents

数字属性，概览屏幕中位于此 Activity 根位置的任务数上限。 达到该条目数时，系统会从概览屏幕中移除最近最少使用的实例。 有效值为 1-50（低内存设备使用 25）；0 为无效值。 该值必须是整数，例如 50。默认值为 16。

### noHistory

布尔属性，当用户离开 activity 并且其不再屏幕上展示时，是否将其从任务栈中移除并执行 finish 操作。

测试结论，如果此属性设置在根 activity 上时，

1. 如果任务只有根 activity，则在返回桌面再切换到应用时，根 activity 将不会被关闭
2. 如果在根 activity 之上有未开启 noHistory 选项的 activity，则在返回桌面再切换到应用时，则根 activity 会被 finish，只留下其上的 activity。

当任务中的所有 activity 都开启了 noHistory 选项后，切换到应用时，会清空根 activity 之上的所有 activity。 

## 启动模式-launchMode

以下4种启动模式，是在清单文件配置中 `<activity/>` 标签下 `launchMode` 属性的所有取值。

### standard

标准模式，所有 activity 默认的启动模式，每次使用 Intent 启动 activity，都会创建一个新的 activity 实例，每个实例均可属于不同的任务，并且一个任务可以拥有多个实例。

### singleTop

和 `standard` 模式的唯一区别是，如果目标 activity 在当前任务的栈顶已经存在一个实例并且指定了 `singleTop` 启动模式，则不会创建新实例，只会回调它的 `onNewIntent` 方法传递启动的 Intent，如果目标 activity 不在栈顶，则行为和 `standard` 模式一致，当 activity 出现在其他任务栈时，需要和 `Intent.FLAG_ACTIVITY_NEW_TASK ` 模式组合使用，才能在其他任务栈内寻找栈顶的 activity，并回调其 `onNewIntent` 方法。

### singleTask

首先寻找 activity 所在任务，如果找到与 activity 关联的任务，并且不存在 activity 实例，将会创建 activity 实例，如果在 activity 关联任务中已存在目标 activity 实例，则不会创建新实例，并且回调其 `onNewIntent` 方法传递启动的 Intent，当目标 activity 之上有 activity 存在时（任务栈中），将会清空其上的所有 activity，即将目标 activity 置于栈顶，此启动模式有 `Intent.FLAG_ACTIVITY_NEW_TASK` 的作用，如果找不到 activity  关联任务，则会创建新的任务，并在任务中创建新的 activity 实例作为任务的根 activity。 

### singleInstance

与 `singleTask` 的唯一区别是，它只允许任务栈中存在唯一的目标 activity 的实例，不允许其他 activity 在任务中和其共存，即使不指定 `taskAffinity` 属性，使用 `singleInstance` 启动模式的 activity 也会在新的任务中出现。

## 启动模式-IntentFlag

以下是在启动 Activity 的 Intent 中可使用 `addFlag` 方法指定的部分和任务栈相关的标记。

### FLAG_ACTIVITY_CLEAR_TASK

在启动 activity 时，首先清空 activity 关联任务栈（任务栈内的 activity 全部执行 finish），此标记只能与 `Intent.FLAG_ACTIVITY_NEW_TASK` 联合使用。

###  FLAG_ACTIVITY_CLEAR_TOP

如果当前任务中存在 activity 实例，则会销毁当前任务栈中目标 activity 之上的所有 acitivty，然后回调目标 activity 的 `onNewIntent` 方法传递启动 Activity 的 Intent，若找不到，则创建新的 activity 实例，一般和 `Intent.FLAG_ACTIVITY_NEW_TASK ` 结合使用，结合后，可在其它任务栈中找到目标 activity 的实例，并执行和上面相同描述的操作，否则不会在其他任务栈中寻找 activity。

###  FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS

设置后，activity 所在任务将不会在最近任务列表展示（概览屏幕），只对任务的根 activity 有效，如果在当前任务中的 activity 启动新 activity 时设置此标记，则 activty 依然会在最近任务列表中展示，所以设置此标记，需要配合 `Intent.FLAG_ACTIVITY_NEW_TASK` 在新的任务中启动 activity，这个新任务将不会在最近任务列表中展示。

###  FLAG_ACTIVITY_NEW_TASK

首先寻找 activity 关联的任务，如果找不到，就创建新的任务，并且创建新的 activity 实例并入栈，如果找到 activity 关联任务，里面没有 activity 实例，则创建新的 activity 实例入栈，如果关联任务栈内已存在 activity 实例，则判断任务栈是否处于前台状态，若处于前台，则无动作，否则将关联的任务提到前台。

###  FLAG_ACTIVITY_NO_HISTORY

使用此标记启动的 activity 将和在清单文件中开启 `noHsitory` 选项有相同的作用。

###  FLAG_ACTIVITY_SINGLE_TOP

使用此标记启动 activity 时，将和在清单文件中开启 `singleTop` 选项有相同的作用。

###  FLAG_ACTIVITY_FORWARD_RESULT

适用于在使用了 `startForResult` 方法启动的 activity 中启动新 activity 时设置此标记，如果设置了此标记，那么即是使用了 `startForResult` 方法启动的 activity 将返回结果的权力交给了使用此标记启动的 activity，即使用 `setResult` 方法传递结果的权力，那么期待返回结果的 activity 将收到此标记启动 activity 返回的结果。 

###  FLAG_ACTIVITY_MULTIPLE_TASK

只能和  `FLAG_ACTIVITY_NEW_DOCUMENT` 或 `FLAG_ACTIVITY_NEW_TASK`  组合使用，使用此标记将强制创建新的任务并创建新的 activity 入栈。

###  FLAG_ACTIVITY_NEW_DOCUMENT

api21可用，与 `FLAG_ACTIVITY_NEW_TASK`   的不同点是，如果任务栈中没有 activity 实例， 即使未指定 activity 的 	`taskAffinity` 属性，也将创建新的任务，并当 activity 入栈，当 activity 所在的任务退出后，最近任务列表里也将不再出现这个任务。

###  FLAG_ACTIVITY_NO_ANIMATION

启动 activity 时关闭动画

###  FLAG_ACTIVITY_RESET_TASK_IF_NEEDED

###  FLAG_ACTIVITY_RETAIN_IN_RECENTS

使用 `FLAG_ACTIVITY_NEW_DOCUMENT` 启动的文档界面会在执行 finish 之后从最近任务列表中移除，使用这个标志可以使其保留在最近任务列表中，可以使用户重新启动这个文档。

###  FLAG_ACTIVITY_TASK_ON_HOME

当启动此 activity 后，点击返回键将返回系统桌面，需要和 `FLAG_ACTIVITY_NEW_TASK`  结合使用。