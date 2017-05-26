# Android View事件分发机制

* [触摸事件处理框架](#触摸事件处理框架)
  * [核心方法](#核心方法)
  * [调用关系](#调用关系)
  * [触摸事件流向](#触摸事件流向)
* [部分方法的默认实现](#部分方法的默认实现)
  * [Activity-dispatchTouchEvent](#activity-dispatchtouchevent)
  * [ViewGroup-onInterceptTouchEvent](#viewgroup-onintercepttouchevent)
  * [View-dispatchTouchEvent](#view-dispatchtouchevent)
  * [ViewGroup和View-onTouchEvent](#viewgroup和view-ontouchevent)
* [源码分析](#源码分析)
* [结论](#结论)
* [理论验证](#理论验证)
* [触摸事件冲突处理](#触摸事件冲突处理)
  * [外部拦截法](#外部拦截法)
  * [内部拦截法](#内部拦截法)
* [事件冲突处理实例](#事件冲突处理实例)

之前就从[hongyang的View事件分发机制](http://blog.csdn.net/lmj623565791/article/details/38960443)和《Android开发艺术探索》书上看过事件分发机制，但是看的不够清楚，导致一到用的时候就容易晕，其实书上说的也挺详细的，但有些地方我就是不能理解，所以我觉得不能光看，要实际的写一下试试才行，想起了一句诗，叫“纸上得来终觉浅，绝知此事要躬行”，这次好好看了一下，记录下来，分享给大家，不足之处还请包涵

## 触摸事件处理框架

Android的View和ViewGroup采用了组合模式，所以触摸事件的框架和View测量绘制流程类似，都是层层嵌套的关系，数据都是从根View一层一层向子view分发的，自定义View时有onMeasure、onLayout、onDraw几个重要的方法，处理触摸事件时也有几个重要的方法，只要搞清楚这几个方法的作用及关系，Android事件分发机制也就清晰了。

### 核心方法

- 首先ViewGroup中的三个方法

> `boolean dispatchEvent(MotionEvent e)` 当触摸事件传递给当前View或ViewGroup时，此方法将会被调用，它会负责事件的分发工作，可能会将事件交给自己的 `onTouchEvent` 方法或子View来处理，返回值为是否消耗事件
>
> `boolean onIntercepteEvent(MotionEvent e)` 此方法将被 `dispatchEvent` 方法调用，返回的是是否拦截此次事件，如果返回true，事件将被拦截，子view将不能接收到事件，否则交给子view处理。不过有一个特例，子view可以通过一个方法阻止事件的拦截，下面会详细说
>
> `boolean onTouchEvent(MotionEvent e)` 负责消耗触摸事件，一般在此处处理触摸事件，返回值为是否消耗事件，若不消耗事件，事件将不会再次被传递，而是交给上层View进行处理

- View中存在两个方法，作用和ViewGroup相似

> `boolean dispatchEvent(MotionEvent e)` 作用和viewgroup类似，由于view一定会处理事件，所以view不存在 `onIntercepteEvent` 这个方法，因此 `dispatchEvent` 最终会将事件交给 'onTouchEvent' 方法处理
>
> `boolean onTouchEvent(MotionEvent e)` 与ViewGroup相同

### 调用关系

上面介绍了每个方法的作用，下面用伪代码来描述他们的关系

```java
// 事件交给viewGroup的 dispatchTouchEvent 进行处理
public boolean dispatchTouchEvent(MotionEvent event) {
  	boolean consume = false;
  	if (onIntercepteEvent(event)) {
      	// 决定拦截，事件将交给自己处理
        consume = onTouchEvent(event);
  	} else {
      	// 否则交给子view或viewGroup继续分发事件
        consume = touchTarget.dispatchTouchEvent();
  	}
  	// 消费结果
  	return consume;
}
```

上面的伪代码将事件流向大体描述了出来，事件总体是按照这个方向传递的，但涉及到down，move等具体事件时还需要详细探讨

### 触摸事件流向

上面介绍了触摸事件处理结构，下面介绍当一个触摸事件产生时，触摸事件的传递方向，当触摸屏幕产生触摸事件时，事件将会首先到达顶层Activity并交给它的 `dispatchEvent` 方法来处理，虽然Activity不是View，但它同样具有 `dispatchEvent`和 `onTouchEvent` 方法，`dispatchEvent` 会辗转调用顶层View的 `dispatchEvent` 方法，并传递触摸事件，即开始按照上面伪代码描述的流程来传递事件，如果顶层View的 `dispatchEvent` 返回 `false` 即事件没有被消耗，则会交给Activity自身的 `onTouchEvent` 方法处理

## 部分方法的默认实现

我们遇到事件冲突问题时，需要自定义ViewGroup或View并重写部分事件处理方法改变部分原有规则，就是上面介绍的几个重要的方法，这里首先看一下Android默认的事件处理方式

### activity-dispatchtouchevent

可以清楚的看到Activity的 `dispatchTouchEvent` 的处理，就是之前所描述的

```java
public boolean dispatchTouchEvent(MotionEvent ev) {
    if (ev.getAction() == MotionEvent.ACTION_DOWN) {
      	onUserInteraction();
    }
  	//PhoneWindow将会把事件传递给顶层view
    if (getWindow().superDispatchTouchEvent(ev)) {
      	return true;
    }
    return onTouchEvent(ev);
 }
```

### viewgroup-onintercepttouchevent

这里首先介绍ViewGroup的 `onInterceptTouchEvent` 方法的默认实现，因为 `dispatchEvent` 方法相对复杂一些，将在源码分析中解释

```java
public boolean onInterceptTouchEvent(MotionEvent ev) {
    if (ev.isFromSource(InputDevice.SOURCE_MOUSE)
        && ev.getAction() == MotionEvent.ACTION_DOWN
        && ev.isButtonPressed(MotionEvent.BUTTON_PRIMARY)
        && isOnScrollbarThumb(ev.getX(), ev.getY())) {
      	return true;
    }
    return false;
}
```

基本上我们常用的几个布局,包括LiearnLayout，RelativeLayout，FrameLayout，都没有重写 `onInterceptTouchEvent` 方法，使用的都是ViewGroup的默认实现，可以看到，在通常情况下返回值都为 `false` ，即不拦截事件，将事件交给子view处理，当我们需要拦截事件交给ViewGroup处理的时候，可以重新这个方法，改变它默认的实现

### view-dispatchtouchevent

省略了几行无关代码，需要注意的地方标有注释

```java
public boolean dispatchTouchEvent(MotionEvent event) {
    // ...
    boolean result = false;
    if (mInputEventConsistencyVerifier != null) {
        mInputEventConsistencyVerifier.onTouchEvent(event, 0);
    }
    final int actionMasked = event.getActionMasked();
    if (actionMasked == MotionEvent.ACTION_DOWN) {
        // Defensive cleanup for new gesture
        stopNestedScroll();
    }
    if (onFilterTouchEventForSecurity(event)) {
        if ((mViewFlags & ENABLED_MASK) == ENABLED && handleScrollBarDragging(event)) {
            result = true;
        }
      	// 如果view设置了onTouchListener并且onTouchListener消耗了事件，
      	// 那么view的onTouchEvent方法将不会再回调
        ListenerInfo li = mListenerInfo;
        if (li != null && li.mOnTouchListener != null
                && (mViewFlags & ENABLED_MASK) == ENABLED
                && li.mOnTouchListener.onTouch(this, event)) {
            result = true;
        }
        if (!result && onTouchEvent(event)) {
            result = true;
        }
    }
    // Clean up after nested scrolls if this is the end of a gesture;
    // also cancel it if we tried an ACTION_DOWN but we didn't want the rest
    // of the gesture.
    if (actionMasked == MotionEvent.ACTION_UP ||
            actionMasked == MotionEvent.ACTION_CANCEL ||
            (actionMasked == MotionEvent.ACTION_DOWN && !result)) {
        stopNestedScroll();
    }
    return result;
}
```

可以看到，前面说了view的 `dispatchTouchEvent` 会调用自己的 `onTouchEvent`,但是有一个前提，就是view没有使用OnTouchListener消耗事件的时候，才可正常回调

### viewgroup和view-ontouchevent

ViewGroup直接继承了View，并且它的 `onTouchEvent` 方法使用了View的默认实现，下面是默认实现的源码，有点长

```java
public boolean onTouchEvent(MotionEvent event) {
    final float x = event.getX();
    final float y = event.getY();
    final int viewFlags = mViewFlags;
    final int action = event.getAction();
  	//当控件为 DISABLED 状态时，只要拥有几个特殊标记，依然会消耗事件
    if ((viewFlags & ENABLED_MASK) == DISABLED) {
        if (action == MotionEvent.ACTION_UP && (mPrivateFlags & PFLAG_PRESSED) != 0) {
            setPressed(false);
        }
        // A disabled view that is clickable still consumes the touch
        // events, it just doesn't respond to them.
        return (((viewFlags & CLICKABLE) == CLICKABLE
                || (viewFlags & LONG_CLICKABLE) == LONG_CLICKABLE)
                || (viewFlags & CONTEXT_CLICKABLE) == CONTEXT_CLICKABLE);
    }
  	//类似 onTouchListener功能 的代理
    if (mTouchDelegate != null) {
        if (mTouchDelegate.onTouchEvent(event)) {
            return true;
        }
    }
  	//当view拥有点击属性时，Android会处理并消耗事件，并回调程序中设置的 clickListener，否则不消耗
    if (((viewFlags & CLICKABLE) == CLICKABLE ||
            (viewFlags & LONG_CLICKABLE) == LONG_CLICKABLE) ||
            (viewFlags & CONTEXT_CLICKABLE) == CONTEXT_CLICKABLE) {
        switch (action) {
            case MotionEvent.ACTION_UP:
                boolean prepressed = (mPrivateFlags & PFLAG_PREPRESSED) != 0;
                if ((mPrivateFlags & PFLAG_PRESSED) != 0 || prepressed) {
                    // take focus if we don't have it already and we should in
                    // touch mode.
                    boolean focusTaken = false;
                    if (isFocusable() && isFocusableInTouchMode() && !isFocused()) {
                        focusTaken = requestFocus();
                    }
                    if (prepressed) {
                        // The button is being released before we actually
                        // showed it as pressed.  Make it show the pressed
                        // state now (before scheduling the click) to ensure
                        // the user sees it.
                        setPressed(true, x, y);
                   }
                    if (!mHasPerformedLongPress && !mIgnoreNextUpEvent) {
                        // This is a tap, so remove the longpress check
                        removeLongPressCallback();
                        // Only perform take click actions if we were in the pressed state
                        if (!focusTaken) {
                            // Use a Runnable and post this rather than calling
                            // performClick directly. This lets other visual state
                            // of the view update before click actions start.
                            if (mPerformClick == null) {
                                mPerformClick = new PerformClick();
                            }
                            if (!post(mPerformClick)) {
                                performClick();
                            }
                        }
                    }
                    if (mUnsetPressedState == null) {
                        mUnsetPressedState = new UnsetPressedState();
                    }
                    if (prepressed) {
                        postDelayed(mUnsetPressedState,
                                ViewConfiguration.getPressedStateDuration());
                    } else if (!post(mUnsetPressedState)) {
                        // If the post failed, unpress right now
                        mUnsetPressedState.run();
                    }
                    removeTapCallback();
                }
                mIgnoreNextUpEvent = false;
                break;
            case MotionEvent.ACTION_DOWN:
                mHasPerformedLongPress = false;
                if (performButtonActionOnTouchDown(event)) {
                    break;
                }
                // Walk up the hierarchy to determine if we're inside a scrolling container.
                boolean isInScrollingContainer = isInScrollingContainer();
                // For views inside a scrolling container, delay the pressed feedback for
                // a short period in case this is a scroll.
                if (isInScrollingContainer) {
                    mPrivateFlags |= PFLAG_PREPRESSED;
                    if (mPendingCheckForTap == null) {
                        mPendingCheckForTap = new CheckForTap();
                    }
                    mPendingCheckForTap.x = event.getX();
                    mPendingCheckForTap.y = event.getY();
                    postDelayed(mPendingCheckForTap, ViewConfiguration.getTapTimeout());
                } else {
                    // Not inside a scrolling container, so show the feedback right away
                    setPressed(true, x, y);
                    checkForLongClick(0, x, y);
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                setPressed(false);
                removeTapCallback();
                removeLongPressCallback();
                mInContextButtonPress = false;
                mHasPerformedLongPress = false;
                mIgnoreNextUpEvent = false;
                break;
            case MotionEvent.ACTION_MOVE:
                drawableHotspotChanged(x, y);
                // Be lenient about moving outside of buttons
                if (!pointInView(x, y, mTouchSlop)) {
                    // Outside button
                    removeTapCallback();
                    if ((mPrivateFlags & PFLAG_PRESSED) != 0) {
                        // Remove any future long press/tap checks
                        removeLongPressCallback();
                        setPressed(false);
                    }
                }
                break;
        }
        return true;
    }
    return false;
}
```

上面的代码主要做了两件事

1. 对DISABLE属性的view，如果设置了可点击的属性，例如 `CLICKABLE` 或 `LONGCLICKABLE` 属性，选择消耗事件
2. 如果View设置了可点击的属性，Android会处理并消耗事件，并在 `UP` 事件回调设置的点击事件监听器，否则不消耗

如果我们重写方法时，忘了调用 `super.onTouchEvent` 的话，就需要自己处理点击事件了，一般 `CLICKABLE` 属性一些View会默认拥有，比如 `Button`，对于没有 `CLICKABLE` 属性的View，我们可以通过直接设置或设置点击事件监听器来拥有此属性，`setOnClickListener` 和 `setOnLongClickListner` 内部会设置 `CLICKABLE` 属性

## 源码分析

前面已经说了时间分发的流程和部分默认实现了，接下来就是最重要的ViewGroup的 `dispatchTouchEvent` 方法的分析了，只要搞懂了这个方法，那么事件分发机制也就差不多了，上代码，真长，重点的地方会标有注释

```java
@Override
public boolean dispatchTouchEvent(MotionEvent ev) {
    if (mInputEventConsistencyVerifier != null) {
        mInputEventConsistencyVerifier.onTouchEvent(ev, 1);
    }
    // If the event targets the accessibility focused view and this is it, start
    // normal event dispatch. Maybe a descendant is what will handle the click.
    if (ev.isTargetAccessibilityFocus() && isAccessibilityFocusedViewOrHost()) {
        ev.setTargetAccessibilityFocus(false);
    }
  	//事件处理结果
    boolean handled = false;
  	//Filter the touch event to apply security policies. 通常情况都是true
    if (onFilterTouchEventForSecurity(ev)) {
        final int action = ev.getAction();
        final int actionMasked = action & MotionEvent.ACTION_MASK;
        // down事件发生时将会清空状态，包括 mFirstTouchTarget将会置空
        if (actionMasked == MotionEvent.ACTION_DOWN) {
            // Throw away all previous state when starting a new touch gesture.
            // The framework may have dropped the up or cancel event for the previous gesture
            // due to an app switch, ANR, or some other state change.
            cancelAndClearTouchTargets(ev);
            resetTouchState();
        }
        // 事件拦截结果
        final boolean intercepted;
        if (actionMasked == MotionEvent.ACTION_DOWN || mFirstTouchTarget != null) {
            // 当子view调用此ViewGroup的 requestDisallowInterceptTouchEvent 方法将会设置此标记
        	// 即子view想要阻止ViewGroup拦截事件时
            final boolean disallowIntercept = (mGroupFlags & FLAG_DISALLOW_INTERCEPT) != 0;
            if (!disallowIntercept) {
                // 询问是否拦截事件
                intercepted = onInterceptTouchEvent(ev);
                ev.setAction(action); // restore action in case it was changed
            } else {
                intercepted = false;
            }
        } else {
            // There are no touch targets and this action is not an initial down
            // so this view group continues to intercept touches.
            intercepted = true;
        }
        // If intercepted, start normal event dispatch. Also if there is already
        // a view that is handling the gesture, do normal event dispatch.
        if (intercepted || mFirstTouchTarget != null) {
            ev.setTargetAccessibilityFocus(false);
        }
        // Check for cancelation.
        final boolean canceled = resetCancelNextUpFlag(this)
                || actionMasked == MotionEvent.ACTION_CANCEL;
        // Update list of touch targets for pointer down, if needed.
        final boolean split = (mGroupFlags & FLAG_SPLIT_MOTION_EVENTS) != 0;
        TouchTarget newTouchTarget = null;
        boolean alreadyDispatchedToNewTouchTarget = false;
        // 若viewGroup没有拦截事件且不是cancel状态时，将事件交给子view处理
        if (!canceled && !intercepted) {
            // If the event is targeting accessiiblity focus we give it to the
            // view that has accessibility focus and if it does not handle it
            // we clear the flag and dispatch the event to all children as usual.
            // We are looking up the accessibility focused host to avoid keeping
            // state since these events are very rare.
            View childWithAccessibilityFocus = ev.isTargetAccessibilityFocus()
                    ? findChildWithAccessibilityFocus() : null;
            if (actionMasked == MotionEvent.ACTION_DOWN
                    || (split && actionMasked == MotionEvent.ACTION_POINTER_DOWN)
                    || actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
                final int actionIndex = ev.getActionIndex(); // always 0 for down
                final int idBitsToAssign = split ? 1 << ev.getPointerId(actionIndex)
                        : TouchTarget.ALL_POINTER_IDS;
                // Clean up earlier touch targets for this pointer id in case they
                // have become out of sync.
                removePointersFromTouchTargets(idBitsToAssign);
                final int childrenCount = mChildrenCount;
                if (newTouchTarget == null && childrenCount != 0) {
                    final float x = ev.getX(actionIndex);
                    final float y = ev.getY(actionIndex);
                    // Find a child that can receive the event.
                    // Scan children from front to back.
                    final ArrayList<View> preorderedList = buildTouchDispatchChildList();
                    final boolean customOrder = preorderedList == null
                            && isChildrenDrawingOrderEnabled();
                    final View[] children = mChildren;
                    // 寻找处理触摸事件的子view
                    for (int i = childrenCount - 1; i >= 0; i--) {
                        final int childIndex = getAndVerifyPreorderedIndex(
                                childrenCount, i, customOrder);
                        final View child = getAndVerifyPreorderedView(
                                preorderedList, children, childIndex);
                        // If there is a view that has accessibility focus we want it
                        // to get the event first and if not handled we will perform a
                        // normal dispatch. We may do a double iteration but this is
                        // safer given the timeframe.
                        if (childWithAccessibilityFocus != null) {
                            if (childWithAccessibilityFocus != child) {
                                continue;
                            }
                            childWithAccessibilityFocus = null;
                            i = childrenCount - 1;
                        }
                        if (!canViewReceivePointerEvents(child)
                                || !isTransformedTouchPointInView(x, y, child, null)) {
                            ev.setTargetAccessibilityFocus(false);
                            continue;
                        }
                        newTouchTarget = getTouchTarget(child);
                        if (newTouchTarget != null) {
                            // Child is already receiving touch within its bounds.
                            // Give it the new pointer in addition to the ones it is handling.
                            newTouchTarget.pointerIdBits |= idBitsToAssign;
                            break;
                        }
                        resetCancelNextUpFlag(child);
                      	// 消耗事件
                        if (dispatchTransformedTouchEvent(ev, false, child, idBitsToAssign)) {
                            // Child wants to receive touch within its bounds.
                            mLastTouchDownTime = ev.getDownTime();
                            if (preorderedList != null) {
                                // childIndex points into presorted list, find original index
                                for (int j = 0; j < childrenCount; j++) {
                                    if (children[childIndex] == mChildren[j]) {
                                        mLastTouchDownIndex = j;
                                        break;
                                    }
                                }
                            } else {
                                mLastTouchDownIndex = childIndex;
                            }
                            mLastTouchDownX = ev.getX();
                            mLastTouchDownY = ev.getY();
                          	// mFirstTouchTarget 将被赋值
                            newTouchTarget = addTouchTarget(child, idBitsToAssign);
                            alreadyDispatchedToNewTouchTarget = true;
                            break;
                        }
                        // The accessibility focus didn't handle the event, so clear
                        // the flag and do a normal dispatch to all children.
                        ev.setTargetAccessibilityFocus(false);
                    }
                    if (preorderedList != null) preorderedList.clear();
                }
                if (newTouchTarget == null && mFirstTouchTarget != null) {
                    // Did not find a child to receive the event.
                    // Assign the pointer to the least recently added target.
                    newTouchTarget = mFirstTouchTarget;
                    while (newTouchTarget.next != null) {
                        newTouchTarget = newTouchTarget.next;
                    }
                    newTouchTarget.pointerIdBits |= idBitsToAssign;
                }
            }
        }
        // 子view没有消耗事件则mFirstTouchTarget为null
        if (mFirstTouchTarget == null) {
          	// 交给自己的 dispatch处理
            // No touch targets so treat this as an ordinary view.
            handled = dispatchTransformedTouchEvent(ev, canceled, null,
                    TouchTarget.ALL_POINTER_IDS);
        } else {
            // Dispatch to touch targets, excluding the new touch target if we already
            // dispatched to it.  Cancel touch targets if necessary.
            TouchTarget predecessor = null;
            TouchTarget target = mFirstTouchTarget;
            while (target != null) {
                final TouchTarget next = target.next;
                if (alreadyDispatchedToNewTouchTarget && target == newTouchTarget) {
                    handled = true;
                } else {
                    final boolean cancelChild = resetCancelNextUpFlag(target.child)
                            || intercepted;
                  	// 如果决定拦截而且mFirstTouchTarget不为null时（即上次事件是子view处理，这次被拦截）
                    // 将会把 ACTION_CENCEL事件分发给子view
                    if (dispatchTransformedTouchEvent(ev, cancelChild,
                            target.child, target.pointerIdBits)) {
                        handled = true;
                    }
                    //cancelChild为true时，将会把mFisrtTouchTarget清空
                    if (cancelChild) {
                        if (predecessor == null) {
                            mFirstTouchTarget = next;
                        } else {
                            predecessor.next = next;
                        }
                        target.recycle();
                        target = next;
                        continue;
                    }
                }
                predecessor = target;
                target = next;
            }
        }
        // Update list of touch targets for pointer up or cancel, if needed.
        if (canceled
                || actionMasked == MotionEvent.ACTION_UP
                || actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
            resetTouchState();
        } else if (split && actionMasked == MotionEvent.ACTION_POINTER_UP) {
            final int actionIndex = ev.getActionIndex();
            final int idBitsToRemove = 1 << ev.getPointerId(actionIndex);
            removePointersFromTouchTargets(idBitsToRemove);
        }
    }
    if (!handled && mInputEventConsistencyVerifier != null) {
        mInputEventConsistencyVerifier.onUnhandledEvent(ev, 1);
    }
    return handled;
}
```

首先看第12-42行，做了两件重要的事

1. 当down事件发生时将会清空状态和标记，这里主要关注`mFirstTouchTarget` 和 `FLAG_DISALLOW_INTERCEPT` 这两个东西会被清空和重置，
2. down事件发生时或者 mFirstTouchTarget != null 时将会调用 `onInterceptTouchEvent` 方法询问是否拦截事件，其中拦截事件有一个前提就是子view 没有调用 `requestDisallowInterceptTouchEvent(true)` 来阻拦ViewGroup的拦截。

其中 mFirstTouchTarget 的值代表什么呢？从下面的114行和131行代码可以看出，一旦有子View处理了事件，这个 mFirstTouctTarget 就会被赋值，否则为null，因此 mFirstTouctTarget 就代表有没有子View处理事件

114行和131行代码

```java
if (dispatchTransformedTouchEvent(ev, false, child, idBitsToAssign)) {
    //...
    newTouchTarget = addTouchTarget(child, idBitsToAssign);
    //...
}
```

下面是 `addTouchTarget` 方法的实现，mFirstTouchTarget 会被赋值

```java
private TouchTarget addTouchTarget(@NonNull View child, int pointerIdBits) {
    final TouchTarget target = TouchTarget.obtain(child, pointerIdBits);
    target.next = mFirstTouchTarget;
    //被赋值
    mFirstTouchTarget = target;
    return target;
}
```

就是说每次down事件或者上次有子view处理了事件的时候，ViewGroup将会调用`onInterceptTouchEvent` 方法询问是否拦截事件，从这里可以看出由于每次down事件时都会清空状态，那么down事件时 FLAG_DISALLOW_INTERCEPT 标记必然无法设置，ViewGroup必然会调用 `onInterceptTouchEvent` 方法询问是否拦截事件，那么一旦ViewGroup决定拦截down事件，子view将无法阻拦ViewGroup的拦截，因此不可能处理事件，那么 mFirstTouchTarget 将会为空，最终结果就是，`onInterceptTouchEvent`不会被再次调用，一旦ViewGroup决定拦截down事件, 那么接下来的所有事件都将会交给这个ViewGroup处理

下面看 131-191行代码

1. 如果子View没有处理事件，那么将会调用 `handled = dispatchTransformedTouchEvent(ev, canceled, null, TouchTarget.ALL_POINTER_IDS);`方法，其内部交给自己的事件处理方法处理

   下面是 `dispatchTransformedTouchEvent` 省去部分代码的源码，可以看出child参数决定是调用 自己的 `dispatchTouchEvent` 方法还是child的

   ```java
   private boolean dispatchTransformedTouchEvent(MotionEvent event, boolean cancel,
           View child, int desiredPointerIdBits) {
       final boolean handled;
       // Canceling motions is a special case.  We don't need to perform any transformations
       // or filtering.  The important part is the action, not the contents.
       final int oldAction = event.getAction();
       if (cancel || oldAction == MotionEvent.ACTION_CANCEL) {
           event.setAction(MotionEvent.ACTION_CANCEL);
           if (child == null) {
               handled = super.dispatchTouchEvent(event);
           } else {
               handled = child.dispatchTouchEvent(event);
           }
           event.setAction(oldAction);
           return handled;
       }
       // ...
       return handled;
   }
   ```

2. 如果上次子view处理了事件，而这次被viewGroup拦截,因此这次事件发生时 mFirstTouchTarget 还不是null, 那么将会执行else块中的代码，即会把 ACTION_CANCEL 事件会分发给子View，并把mFirstTouchTarget 置空

以上就是对ViewGroup的 `dispatchTouchEvent` 分析，那么下面就可以根据上面的下结论了

## 结论 

好了，前面分析了这么多，可以得出一些结论

1. 当一个ViewGroup决定拦截down事件时，那么整个事件都会被这个ViewGroup接收，并且 `onInterceptTouchEvent`不会被再次调用， 如果ViewGroup没有处理事件，事件将会被上一级ViewGroup处理，如果过上一级ViewGroup以到顶层ViewGroup都没有处理，那么事件最终将会交给Activity的 `onTouchEvent` 来处理
2. 子View可以通过调用父View的 `requestDisallowInterceptTouchEvent` 的方法来阻止父view对触摸事件的拦截，但down事件无法干预
3. 当子View正在处理事件时，父view拦截了事件，那么子View将会收到一个cancel事件，接下来的事件将会被父view处理

## 理论验证

有了以上的一些分析和结论，现在需要验证一下事件的处理流程，下面我将会用假设来模拟一段触摸事件经过事件处理框架的情况，一般的触摸事件，都是 down->move(有多个)->up 这种形式，那么现在

假设：

事件 down->move->move->up 产生，事件流向是 MyActivity->顶层View->...(多个普通的ViewGroup不拦截不处理事件)->MyViewGroup->MyView

模拟下面典型几种情况：

1. down      - MyViewGroup拦截了事件，并处理了事件

   move...up - 事件传递给MyViewGroup，MyViewGroup处理了事件

2. down      - MyViewGroup拦截了事件, 没有处理事件，MyActivity处理了事件

   move...up - MyActivity会处理剩下的事件

3. down      - MyViewGroup不拦截事件，MyView处理了事件

   move...up - MyViewGroup不拦截事件, MyView处理了事件

4. down     - MyViewGroup不拦截事件，MyView处理了事件

   move     - MyViewGroup拦截了事件，MyView接收到了cancel事件

   move,up  - MyViewGroup处理剩下的事件

5. down     - MyViewGroup不拦截事件，处理了事件

   move     - MyViewGroup拦截了事件，MyView接收到了cancel事件

   move,up  - MyViewGroup处理了事件

6. down    - MyViewGroup不拦截事件，MyView处理了事件

   move    - MyView拦截了事件，MyViewGroup拦截无效，MyView处理了事件

   move,up - MyView处理了事件

上面是基于代码的逻辑而建设模拟的部分典型事件处理流程，当然真实的情况会很复杂，会有更多的情况出现，下面将用真实代码来测试，验证结果的同时，使事件处理流程的印象更加深刻，对实际运用也有帮助

## 编码验证

首先来一个Activity，在实现它的 `onTouchEvent` 方法，并打印日志，日志使用了logger开源库，日志更美观

```java
@Override
public boolean onTouchEvent(MotionEvent event) {
    switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
            Logger.d("activity consume down");
            return true;
        case MotionEvent.ACTION_MOVE:
            Logger.d("activity consume move");
            return true;
        case MotionEvent.ACTION_UP:
            Logger.d("activity consume up");
            return true;
    }
    return super.onTouchEvent(event);
}
```

然后在Activity布局中加入自定义的ViewGroup TouchParent， 内部只重写了 `onIntercepTouchEvent` 和 `onTouchEvent` 方法，布局就不用说了

```java
@Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        final int eventMasked = ev.getActionMasked();
        switch (eventMasked) {
            case MotionEvent.ACTION_DOWN:
                return false;
            case MotionEvent.ACTION_MOVE:
                return false;
            case MotionEvent.ACTION_UP:
                return false;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int eventMasked = event.getActionMasked();
        switch (eventMasked) {
            case MotionEvent.ACTION_DOWN:
                Logger.d("parent consume down");
                return false;
            case MotionEvent.ACTION_MOVE:
                Logger.d("parent consume move");
                return true;
            case MotionEvent.ACTION_UP:
                Logger.d("parent consume up");
                return true;
        }
        return super.onTouchEvent(event);
    }
```

上面的TouchParent里面包含了一个自定义的View TouchChild，只重写了 `onTouchEvent` 方法

```java
@Override
public boolean onTouchEvent(MotionEvent event) {
    final int eventMasked = event.getActionMasked();
    switch (eventMasked) {
        case MotionEvent.ACTION_DOWN:
            Logger.d("child consume down");
            return true;
        case MotionEvent.ACTION_MOVE:
            Logger.d("child consume move");
            return true;
        case MotionEvent.ACTION_UP:
            Logger.d("child consume up");
            return true;
    }
    return super.onTouchEvent(event);
}
```

好，现在上面都是默认情况，TouchParent和TouchChild都默认处理所有事件，开始测试，现在用手指在TouchChild的绿色区域的位置向TouchParent的白色区域滑动并抬起手指，如下图

![](/image/android_view_touch_event/20170211180255373.jpg)

1. 正常情况，TouchParent不拦截事件，那么TouchChild会处理所有事件，结果如下：

![](/image/android_view_touch_event/20170211180324754.jpg)

2. 现在把TouchParent的 `onIntercepTouchEvent` 方法改一下，让它“只拦截”down事件，试试

   ```java
   switch (eventMasked) {
     	case MotionEvent.ACTION_DOWN:
       	return true;
     	case MotionEvent.ACTION_MOVE:
       	return false;
     	case MotionEvent.ACTION_UP:
       	return false;
   }

   ```

结果是：

![](/image/android_view_touch_event/20170211180348020.jpg)

   所以为什么是引号呢，这就验证了前面的，当ViewGroup决定拦截down时，那么所有的事件都会交给它来处理，`onIntercepTouchEvent` 也将不会再被调用。

3. 在2的基础上，把TouchParent的 onTouchEvent的move事件改成返回false，不处理move事件看看会怎样

   ```java
   @Override
       public boolean onTouchEvent(MotionEvent event) {
           	//...
               case MotionEvent.ACTION_MOVE:
   //                Logger.d("parent consume move");
                   return false;
               //...
   ```

![](/image/android_view_touch_event/20170211180405567.jpg)

其中move事件由于TouchParent没有处理，最终交给了Activity处理

4. 现在把TouchParent里的 `onInterceptTouchEvent` 方法改为down事件不拦截，move和up事件拦截

   ```java
   @Override
       public boolean onInterceptTouchEvent(MotionEvent ev) {
           final int eventMasked = ev.getActionMasked();
           switch (eventMasked) {
               case MotionEvent.ACTION_DOWN:
                   return false;
               case MotionEvent.ACTION_MOVE:
                   return true;
               case MotionEvent.ACTION_UP:
                   return true;
           }
           return super.onInterceptTouchEvent(ev);
       }
   ```

   然后把TouchChild里的 `onTouchEvent` 方法，在down中调用 `getParent().requestDisallowInterceptTouchEvent(true);` 即，阻止TouchParent的事件拦截，测试一下：

![](/image/android_view_touch_event/20170211180420036.jpg)

   可以看到，事件完全被TouchChild处理了，因为TouchChild使用 `requestDisallowInterceptTouchEvent` 阻止了TouchParent的拦截，不过如果TouchParent在down事件里选择拦截的话，那么TouchChild将无法进行拦截，这里要注意的是 `requestDisallowInterceptTouchEvent` 是在 `onTouchEvent` 里调用的，推荐在 `dispatchTouchEvent` 方法里进行过拦截，`dispatchTouchEvent` 是必然会接受到事件的，而 `onTouchEvent` 可能受到 OnTouchListener 的影响而不被调用

5. 现在再在4的基础上在 TouchChild里面的 `onTouchEvent` 里move事件里调用 `getParent().requestDisallowInterceptTouchEvent(false);` 把事件还给TouchParent会怎样

![](/image/android_view_touch_event/20170211180831881.jpg)

   这里我多做了一次move事件，为了看的更清楚，当TouchChild在down事件里阻拦了TouchParent的时候，TouchChild将会处理下一个move，在这里TouchChild有把事件交还给TouchParent，这时TouchParent拦截了move事件，事件将会交给TouchParent来处理，但是很奇怪为什么会下一个move会被Activity处理呢，然后才是TouchParent处理move，因为从前面的结论可以知道，上次是TouchChild处理的事件，这次被拦截的话，此次的事件将会变成一个 cancel事件并分发给子view，这里TouchChild没有处理cancel事件，所以最终交给了activity处理，现在让TouchChild处理cancel试试

   ```java
   @Override
   public boolean onTouchEvent(MotionEvent event) {
          //...
           case MotionEvent.ACTION_CANCEL:
               Logger.d("child consume cancel");
               return true;
       }
       return super.onTouchEvent(event);
   }
   ```

![](/image/android_view_touch_event/20170211180903319.jpg)

   好了，事件确实是cancel事件，这次被TouchChild消耗了，activity就不会处理了

   以上的测试是我测试的几个典型例子，可以对结论进行论证，看了以上这些应该事件分发机制也差不多了把，下面是Android事件分发机制在处理事件冲突时的应用


## 触摸事件冲突处理

一般我们在项目中可能会遇到界面比较复杂的情况，而且很可能是可划动的布局相互嵌套的情况，比如，ScrollView里面有一个ListView，两个view都是纵向划动的，还有ViewPager里面有ScrollView，或ScrollView里面有ViewPager，这两个是横向和纵向划动的冲突，当遇到这些情况，会根据滑动的动作来决定事件交给哪个View来处理，为了解决这个问题，就需要对事件分发机制有所熟悉。

​    例如或ScrollView里面有ViewPager这种情况吧，当用户偏向横划的时候，相应的ViewPager就要做出内容的偏移，当用户偏向竖划的时后，ScrollView就要滚动里面的内容，针对这种情况，就产生了一个判断条件，就是用户横向或纵向划动，转化为逻辑就是，move事件时x和y轴划动距离相比较，伪代码如下

```java
if (x > y /*横向滑动*/) {
    // ViewPager滚动
} else /*纵向滑动*/{
    // ScrollView滚动
}
```

那么就是说，x > y时，ViewPager会拦截ScrollView的事件，自己来处理，否则ScrollView拦截ViewPager的事件，自己来处理

针对此情况，一般有两种拦截的方法，也就是触摸事件冲突处理的方法

### 外部拦截法

外部拦截法以嵌套布局外层View为主，主要重写 `onInterceptTouchEvent` 方法

```java
public void onInterceptTouch(MotionEvent ev){
    /*是否拦截*/
    boolean intercepted = false;
    switch(event.getAction()){
    case MotionEvent.ACTION_DOWN:
        /*不拦截Down事件*/
        intercepted = false;
        break;
    case MotionEvent.ACTION_MOVE:
        if(父容器自身需要此事件){
            intercepted = true;
        }else{
            intercepted = false;
        }
        break;
    case MotionEvent.ACTION_UP:
        /*不拦截Up事件,没有意义*/
        intercepted = false;
        break;
    }
    return intercepted;
}
```

这种方法的事件决定权完全在外部View上，其中为什么不拦截down事件呢，因为一旦拦截，那么事件就一定会给自己处理了，子view就没有选择的余地了，up也不能拦截，因为拦截了move事件，up事件也将会交给自己处理，只拦截up的话，没有意义，还会导致子view无法接收到click事件，所以不拦截

### 内部拦截法

内部拦截法以嵌套布局内层View为主，主要重写，子view的 `dispatchTouchEvent` 方法，这种方法有个前提是外层view一定没有拦截down事件，所以首先确认这个条件，必要时重新外层view的 `onInterceptTouchEvent` 方法，不拦截down事件

```java
/*子View*/
public void dispatchTouchEvent(MotionEvent ev){
    switch(event.getAction()){
    case MotionEvent.ACTION_DOWN:
        /*使父View不再调用事件拦截*/
        parent.requestDisallowInterceptTouchEvent(true);
    break;
    case MotionEvent.ACTION_MOVE:
        if(/*还给父view事件*/){
            parent.requestDisallowInterceptTouchEvent(false);
        }
        break;
    case MotionEvent.ACTION_UP:
        break;
    }
    return super.dispatchTouchEvent(event);
}
```

其实刚才所说的ScrollView里面有ViewPager这种情况，官方都做了处理，所以我们用的时候没有任何问题，下面截取了ViewPager的 `onInterceptTouchEvent`的部分源码

```java
case MotionEvent.ACTION_DOWN: {
  	/*
  	* Remember location of down touch.
    * ACTION_DOWN always refers to pointer index 0.
    */
    mLastMotionX = mInitialMotionX = ev.getX();
    mLastMotionY = mInitialMotionY = ev.getY();
    mActivePointerId = ev.getPointerId(0);
    mIsUnableToDrag = false;

    mIsScrollStarted = true;
    mScroller.computeScrollOffset();
    if (mScrollState == SCROLL_STATE_SETTLING
      	&& Math.abs(mScroller.getFinalX() - mScroller.getCurrX()) > mCloseEnough) {
        // Let the user 'catch' the pager as it animates.
        mScroller.abortAnimation();
        mPopulatePending = false;
        populate();
        mIsBeingDragged = true;
        requestParentDisallowInterceptTouchEvent(true);
        setScrollState(SCROLL_STATE_DRAGGING);
  	} else {
    	completeScroll(false);
    	mIsBeingDragged = false;
  	}
  	// ...
 	break;
}
```

会发现在down事件中有一个 `requestParentDisallowInterceptTouchEvent(true)`，这里就是拦截外层view使用的内部拦截法

```java
private void requestParentDisallowInterceptTouchEvent(boolean disallowIntercept) {
    final ViewParent parent = getParent();
    if (parent != null) {
        parent.requestDisallowInterceptTouchEvent(disallowIntercept);
    }
}
```

上面也介绍了时间冲突的处理方法，下面就开始做一个实例，来模拟一个项目中的情况

## 事件冲突处理实例

这里假设一种情况，ScrollView里面有一个ListView，这两个view嵌套肯定会出现问题，我首先正常的在Activity里面放上一个ScrollView然后里面放一个ListView，其中ScrollView是可以滚动的，ListView高度限制为200dp，内部子元素有20个，代码很简单，核心部分如下：

```java
ListView listView = (ListView) findViewById(R.id.lv_content);
        listView.setAdapter(new BaseAdapter() {
                                @Override
                                public int getCount() {
                                    return 20;
                                }

                                @Override
                                public Object getItem(int position) {
                                    return null;
                                }

                                @Override
                                public long getItemId(int position) {
                                    return position;
                                }

                                @Override
                                public View getView(int position, View convertView, ViewGroup parent) {
                                    if (convertView == null) {
                                        TextView textView = new TextView(parent.getContext());

                                        textView.setLayoutParams(new AbsListView.LayoutParams(
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                ViewGroup.LayoutParams.MATCH_PARENT
                                        ));
                                        textView.setTextSize(45);
                                        textView.setTextColor(Color.WHITE);
                                        textView.setGravity(Gravity.CENTER);
                                        textView.setText("item" + position);
                                        convertView = textView;
                                    } else {
                                        ((TextView) convertView).setText("item" + position);
                                    }
                                    return convertView;
                                }
                            }
        );
```

运行一下试试

![](/image/android_view_touch_event/20170211181000852.gif)

会发现ListView根本无法滑动，我判断肯定是move事件完全被ScrollView拦截了，导致ListView接收不到事件，也就无法响应滑动，down事件一般是不会被ScrollView拦截的，我现在就想办法让ListView滑动，解决它们的冲突

​    首先需要一个条件，就是什么时候让ListView滑动，什么时候再把事件交还给ScrollView，让它继续滑动，那么现在我的条件是这样，当手指落在ListView上并移动时，ListView完全处理move事件，当ListView达到底部的极限时且手指继续向上滑动 或 当ListView达到顶部的极限时且手指继续向下滑动把事件交给ScrollView处理，针对这个条件，发现决定事件的主要是ListView，那么这里采用内部拦截法，重写ListView的事件处理方法，下面是完整代码和实现效果

```java
public final class TouchListView extends ListView {
    public TouchListView(Context context) {
        super(context);
    }

    public TouchListView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public TouchListView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    private int mLastY;

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        final int eventMasked = ev.getActionMasked();
        final int y = (int) ev.getY();
        switch (eventMasked) {
            case MotionEvent.ACTION_DOWN:
                mLastY = y;
                // 拦截事件
                getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_MOVE:
                if (y < mLastY && checkScrollBottomLimit(this)) {
                    // 当ListView达到底部的极限时且手指继续向上滑动，释放事件
                    getParent().requestDisallowInterceptTouchEvent(false);
                } else if (y > mLastY && checkScrollTopLimit(this)) {
                    // 当ListView达到顶部的极限时且手指继续向下滑动，释放事件
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                mLastY = y;
                break;
            case MotionEvent.ACTION_UP:
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    /**
     * 判断ListView是否滚动到了底部
     *
     * @param listView target
     * @return 滚动到了底部返回true，否则false
     */
    private boolean checkScrollBottomLimit(ListView listView) {
        if (listView.getLastVisiblePosition() == listView.getCount() - 1) {
            final View lastChild = listView.getChildAt(
                    listView.getChildCount() - 1);
            int lastChildBottom = listView.getTop() + lastChild.getBottom();
            if (lastChildBottom == listView.getBottom()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断ListView是否滚动到了顶部
     *
     * @param listView target
     * @return 滚动到了顶部返回true，否则false
     */
    private boolean checkScrollTopLimit(ListView listView) {
        if (listView.getFirstVisiblePosition() == 0) {
            final View lastChild = listView.getChildAt(0);
            if (lastChild.getTop() == 0) {
                return true;
            }
        }
        return false;
    }
}
```

![](/image/android_view_touch_event/20170211181046103.gif)

效果还行，但我还是感觉有点别扭，例如当ListView达到底部的极限时且手指继续向上滑动，释放事件，ScrollView继续滚动，但是我要再向下滑动时，手指还在ListView上，按说ListView内容应该像下滚动，但是这是事件还是ScrollView在处理，所以它会跟着手一起滚动，怎么办，前面都分析过了，事件一但被父View拦截，剩下的事件就不可能交给子View处理了，其实还是可以的，投机取巧的，我们可以手动，将事件传给ListView，强制让它接受事件，这里就不实现了，有兴趣的小伙伴可以实现一下