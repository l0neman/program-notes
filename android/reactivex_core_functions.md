# ReactiveX 核心方法
以及对应的 RxJava2 的示例代码

- [Single](#single)
- [Subject](#subject)
  * [AsyncSubject](#asyncsubject)
  * [BehaviorSubject](#behaviorsubject)
  * [PublichSubject](#publichsubject)
  * [ReplaySubject](#replaysubject)
- [Observable 创建方法](#observable-创建方法)
  * [Create](#create)
  * [Defer](#defer)
  * [Empty/Never/Throw](#empty-never-throw)
  * [From](#from)
  * [Interval](#interval)
  * [Just](#just)
  * [Range](#range)
  * [Repeat](#repeat)
  * [Start](#start)
  * [Timer](#timer)
- [Observable 转换操作](#observable-转换操作)
  * [Buffer](#buffer)
  * [FlatMap](#flatmap)
  * [GroupBy](#groupby)
  * [Map](#map)
  * [Scan](#scan)
  * [Window](#window)
- [Observable 过滤操作](#observable-过滤操作)
  * [Debounce](#debounce)
  * [Distinct](#distinct)
  * [ElementAt](#elementat)
  * [Filter](#filter)
  * [First](#first)
  * [IgnoreElements](#ignoreelements)
  * [Last](#last)
  * [Sample](#sample)
  * [Skip](#skip)
  * [SkipLast](#skiplast)
  * [Take](#take)
  * [TakeLast](#takelast)
- [Observable 合并操作](#observable-合并操作)
  * [CombineLatest](#combinelatest)
  * [Join](#join)
  * [Merge](#merge)
  * [StartWith](#startwith)
  * [Switch](#switch)
  * [Zip](#zip)
- [Observable 错误处理](#observable-错误处理)
  * [Catch](#catch)
  * [Retry](#retry)
- [Observable 辅助操作](#observable-辅助操作)
  * [Delay](#delay)
  * [Do](#do)
  * [Materialize/Dematerialize](#materialize-dematerialize)
  * [ObserveOn](#observeon)
  * [Serialize](#serialize)
  * [Subscribe](#subscribe)
  * [SubscribeOn](#subscribeon)
  * [TimeInterval](#timeinterval)
  * [Timeout](#timeout)
  * [Timestamp](#timestamp)
  * [Using](#using)
- [Observable 条件操作](#observable-条件操作)
  * [All](#all)
  * [Amb](#amb)
  * [Contains](#contains)
  * [DefaultIfEmpty](#defaultifempty)
  * [SqeuenceEqual](#sqeuenceequal)
  * [SkipUntil](#skipuntil)
  * [SkipWhile](#skipwhile)
  * [TakeUntil](#takeuntil)
  * [TakeWhile](#takewhile)
- [Observable 背压操作](#observable-背压操作)
  * [onBackpressureBuffer](#onbackpressurebuffer)
  * [onBackpressureDrop](#onbackpressuredrop)
  * [onBackpressureLatest](#onbackpressurelatest)
- [Observable 可连接操作](#observable-可连接操作)
  * [Publish](#publish)
  * [Connect](#connect)
  * [Refcount](#refcount)
  * [Replay](#replay)
- [Observable 转换操作](#observable-转换操作)
  * [To](#to)
- [参考](#参考)


## Single
Single 是一种只能发出单个项目的特殊的 Observable，没有 `onNext` 方法，最终只有 `onSuccess` 或 `onError` 之中的一个被调用，结果将在 `onSuccess` 中直接回调出去。

```java
Single.just("a string")
    .subscribe(new SingleObserver<String>() {
      @Override public void onSubscribe(Disposable d) {
        L.print("onSubscribe");
      }

      @Override public void onSuccess(String s) {
        L.print("result %s", s); // 回调最终结果
      }

      @Override public void onError(Throwable e) {
        L.print(e);
      }
    });
```

## Subject

`Subject` 本身既可以当作被观察者，也可当作观察者，在某些实现中被当作桥梁或代理。

### AsyncSubject

只发出最后一个项目，在所有项目完成都之后，将结果发送给订阅者。
如果出现错误，则观察者只会接收到错误的通知。

```java
final AsyncSubject<Integer> subject = AsyncSubject.create();
subject.onNext(0);
subject.onNext(1);
subject.onNext(2);
subject.onComplete();
subject.subscribe(new Consumer<Integer>() {
    @Override public void accept(Integer integer) throws Exception {
      L.print(String.valueOf(integer)); // 2
    }
});
```

### BehaviorSubject

当观察者订阅时，它会发出源 Observable 发出的上一个最近的项目，若还没有项目发出，可以指定一个默认的项目。

```java
BehaviorSubject<Integer> subject = BehaviorSubject.createDefault(-1);
subject.subscribe(new Consumer<Integer>() {
  @Override public void accept(Integer integer) throws Exception {
    L.print(String.valueOf(integer)); // -1 0 1 2
  }
});
subject.onNext(0);
subject.onNext(1);
subject.subscribe(new Consumer<Integer>() {
  @Override public void accept(Integer integer) throws Exception {
    L.print(String.valueOf(integer)); // 1 2
  }
});
subject.onNext(2);
subject.onComplete();
subject.subscribe(new Consumer<Integer>() {
  @Override public void accept(Integer integer) throws Exception {
    L.print(String.valueOf(integer)); // no result
  }
});
```

### PublichSubject

它会向观察者发出发出在观察者订阅之后发出的所有项目。

```java
PublishSubject<Integer> subject = PublishSubject.create();
subject.onNext(0);
subject.subscribe(new Consumer<Integer>() {
  @Override public void accept(Integer integer) throws Exception {
    L.print(String.valueOf(integer)); // 1 2
  }
});
subject.onNext(1);
subject.subscribe(new Consumer<Integer>() {
  @Override public void accept(Integer integer) throws Exception {
    L.print(String.valueOf(integer)); // 2
  }
});
subject.onNext(2);
subject.onComplete();
```

### ReplaySubject

每次在观察者订阅的时候都发送源 Observable 发送过的所有项目，在一些版本中，指定缓存限制，缓存超过限制时，旧的项目将被丢弃。

```java
ReplaySubject<Integer> subject = ReplaySubject.createWithSize(2); // 数量限制
subject.onNext(0);
subject.subscribe(new Consumer<Integer>() {
  @Override public void accept(Integer integer) throws Exception {
    L.print(String.valueOf(integer)); // 0 1 2
  }
});
subject.onNext(1);
subject.onNext(2);
subject.subscribe(new Consumer<Integer>() {
  @Override public void accept(Integer integer) throws Exception {
    L.print(String.valueOf(integer)); // 1 2
  }
});
subject.onComplete();
```

## Observable 创建方法

创建可观察的 Observable

### Create 
  以自己的方式从头创建与一个 Observable，遵循 Observable 的回调方式，一般 `onNext` 调用一次或者多次，`onCompleted` 或 `onError` 只调用一次。

```java
/* 提供 Disposable 或 Cancellable 的创建*/
Observable.create(new ObservableOnSubscribe<Integer>() {
  @Override public void subscribe(ObservableEmitter<Integer> e) throws Exception {
    e.setDisposable(new Disposable() {
      @Override public void dispose() {
        L.print("dispose");
      }

      @Override public boolean isDisposed() { return false; }
    });
    // e.setCancellable(new Cancellable() {
    //   @Override public void cancel() throws Exception { /* 取消操作 */ }
    // });
    e.onNext(0);
    e.onNext(1);
    e.onNext(2);
    e.onComplete();
  }
});
```

使用 `ubsafeCreate` 方法创建，需要自己处理 dispose 操作。

```java
Observable.unsafeCreate(new ObservableSource<Integer>() {
  @Override public void subscribe(Observer<? super Integer> observer) {
    Disposable d = new Disposable() {
      private boolean isDispose;

      @Override public void dispose() { isDispose = true; }

      @Override public boolean isDisposed() { return isDispose; }
    };
    observer.onSubscribe(d);
    
    if (!d.isDisposed()) { observer.onNext(0); }
    if (!d.isDisposed()) { observer.onNext(1); }
    if (!d.isDisposed()) { observer.onNext(2); }
    if (!d.isDisposed()) { observer.onComplete(); }
  }
});
```

### Defer

创建延迟发射项目的 Observable，每次在观察者订阅的时候，产生一个新的 Observable，开始发射它的所有项目。

```java
Observable<Integer> defer = Observable.defer(new Callable<ObservableSource<Integer>>() {
  @Override public ObservableSource<Integer> call() throws Exception {
    return Observable.just(0, 1, 2);
  }
});

defer.subscribe(new Consumer<Integer>() {
  @Override public void accept(Integer integer) throws Exception {
    L.print(String.valueOf(integer)); // 0 1 2
  }
});

defer.subscribe(new Consumer<Integer>() {
  @Override public void accept(Integer integer) throws Exception {
    L.print(String.valueOf(integer)); // 0 1 2
  }
});
```

### Empty/Never/Throw

创建具有明确行为的 Observable

```java
Observable.empty()  // 只发射完成
    .subscribe(new Observer<Object>() {
      @Override public void onSubscribe(Disposable d) {}

      @Override public void onNext(Object o) {}

      @Override public void onError(Throwable e) {}

      @Override public void onComplete() { L.print("onComplete"); }
    });

Observable.never(); // 什么都不发射

Observable.error(new Exception("test")) // 只发射错误
    .subscribe(new Observer<Object>() {
      @Override public void onSubscribe(Disposable d) {}

      @Override public void onNext(Object o) {}

      @Override public void onError(Throwable e) { L.print(e); }

      @Override public void onComplete() {}
    });
```

### From

从一个数据结构或对象创建 Observable

```java
/* 来自一个数组类型 */
Observable.fromArray("1", "2", "3");
/* 来自一个集合类型 */
Observable.fromIterable(Arrays.asList("1", "2", "3"));
/* 来自一个函数返回值 */
Observable.fromCallable(new Callable<String>() {
  @Override public String call() throws Exception {
    return "a string";
  }
});
/* ... */
```

### Interval

创建以特定时间间隔返回递增整数的 Observable

```java
/* 每隔一秒发送一次 */
Observable.interval(1, TimeUnit.SECONDS)
    .subscribe(new Consumer<Long>() {
      @Override public void accept(Long aLong) throws Exception {
        L.print(String.valueOf(aLong)); // 0 1 2 3 ...
      }
    });
```

### Just

创建一个发射一个或一组对象的 Observable

```java
/* 从一个或多个对象创建 Observable */
Observable.just(0);
Observable.just(0, 1, 2, 3)
    .subscribe(new Consumer<Integer>() {
      @Override public void accept(Integer integer) throws Exception {
        L.print(String.valueOf(integer)); // 0 1 2 3
      }
    });
```

### Range

创建一个发射一个整数范围的 Observable

```java
Observable.range(1, 4)
    .subscribe(new Consumer<Integer>() {
      @Override public void accept(Integer integer) throws Exception {
        L.print(String.valueOf(integer)); // 1 2 3 4
      }
    });
```

### Repeat

创建一个可重复发射项目的 Observable，有些实现可指定重复次数。

```java
Observable.just(0, 1, 2)
    .repeat(2)
    .subscribe(new Consumer<Integer>() {
      @Override public void accept(Integer integer) throws Exception {
        L.print(String.valueOf(integer)); // 0 1 2 0 1 2
      }
    });
```

### Start

创建一个发射一个函数返回值的 Observable

```java
Observable.fromCallable(new Callable<String>() {
  @Override public String call() throws Exception {
    /* 一系列操作 */
    return "result";
  }
}).subscribe(new Consumer<String>() {
  @Override public void accept(String s) throws Exception {
    L.print(s);
  }
});
```

### Timer

在指定的时间后发出一个整型0

```java
/* 指定一秒后发出 */
Observable.timer(1, TimeUnit.SECONDS)
    .subscribe(new Consumer<Long>() {
      @Override public void accept(Long aLong) throws Exception {
        L.print(String.valueOf(aLong)); // 0
      }
    });
TestHelper.block(2);
```

## Observable 转换操作

对发射项目类型的转换

### Buffer

将源 Observable 转换成一个周期性打包发射项目的 Observable，每次发射一组打包的项目

```java
Observable.just(0, 1, 2, 3, 4, 5)
    .buffer(2) // 1 个一组打包发射
    .subscribe(new Consumer<List<Integer>>() {
      @Override public void accept(List<Integer> integers) throws Exception {
        L.printList(integers); // [0, 1] [2, 3] [4, 5]
      }
    });

Observable.just(0, 1, 2, 3, 4, 5)
    .buffer(2, 3)  // 每隔3个作为起点
    .subscribe(new Consumer<List<Integer>>() {
      @Override public void accept(List<Integer> integers) throws Exception {
        L.printList(integers); // [0, 1] [3, 4]
      }
    });
```

### FlatMap

将一个包含多维项目的 Observable 平铺成多个低维项目的 Observable，并依次发送他们的项目

```java
/* 将一维的列表平铺成单个的整型 */
List<Integer> list = Arrays.asList(1, 3, 5, 7);
List<Integer> list1 = Arrays.asList(2, 4, 6, 8);
Observable.just(list, list1)
    .flatMap(new Function<List<Integer>, ObservableSource<Integer>>() {
      @Override
      public ObservableSource<Integer> apply(List<Integer> integers) throws Exception {
        return Observable.fromIterable(integers);
      }
    })
    .subscribe(new Consumer<Integer>() {
      @Override public void accept(Integer integer) throws Exception {
        L.print(String.valueOf(integer)); // 1 3 5 7 2 4 6 8
      }
    });
```

### GroupBy

将源 Observable 按给定条件分成多组 Observable

```java
Observable.just(0, 1, 2, 3, 4,5)
    .groupBy(new Function<Integer, Integer>() {
      @Override public Integer apply(Integer integer) throws Exception {
        return integer % 2 == 0 ? 0 : 1; // 按奇偶数分组
      }
    })
    .subscribe(new Consumer<GroupedObservable<Integer, Integer>>() {
      @Override
      public void accept(GroupedObservable<Integer, Integer> groupedObservable) throws Exception {
        if (groupedObservable.getKey() == 0) {
          groupedObservable.subscribe(new Consumer<Integer>() {
            @Override public void accept(Integer integer) throws Exception {
              L.print(String.valueOf(integer)); // 偶数 0 2 4 
            }
          });
        } else if (groupedObservable.getKey() == 1) {
          groupedObservable.subscribe(new Consumer<Integer>() {
            @Override public void accept(Integer integer) throws Exception {
              L.print(String.valueOf(integer)); // 奇数 1 3 5
            }
          });
        }
      }
    });
```

### Map

将发射的项目通过转换方法转换成另一种类型再发射

```java
Observable
    .just(BitmapFactory.decodeResource(
        context.getResources(), R.mipmap.ic_launcher
    ))
    .map(new Function<Bitmap, Drawable>() { // Bitmap 转换为 Drawable
      @Override public Drawable apply(Bitmap bitmap) throws Exception {
        return new BitmapDrawable(bitmap);
      }
    })
    .subscribe(new Consumer<Drawable>() {
      @Override public void accept(Drawable drawable) throws Exception {
        imageView.setImageDrawable(drawable); // set image 
      }
    });
```

### Scan

将发射的每一个项目依次调用指定的方法，并将结果和下一个项目进行计算，有时被称为累加器

```java
Observable.just(0, 1, 2, 3, 4)
    .scan(new BiFunction<Integer, Integer, Integer>() {
      @Override public Integer apply(Integer integer, Integer integer2) throws Exception {
        return integer + integer2;
      }
    })
    .subscribe(new Consumer<Integer>() {
      @Override public void accept(Integer integer) throws Exception {
        //0 (0+1)=1 (1+2)=3 (3+3)=6 (6+4)=10
        L.print(String.valueOf(integer)); // 0 1 3 6 10
      }
    });
```

### Window

将发射项目按规则分为多个窗口，每个窗口都是一个 Observable，而且具有完整生命周期

```java
/* print s(subscribe)... 0 1 c(complete)... s... 2 3 c... s... 4 5 c...*/
Observable.just(0, 1, 2, 3, 4, 5)
    .window(2) // 每两个分成一个窗口
    .subscribe(new Consumer<Observable<Integer>>() {
      @Override
      public void accept(final Observable<Integer> integerObservable) throws Exception {
        integerObservable.subscribe(new Observer<Integer>() {
          @Override public void onSubscribe(Disposable d) {
            L.print("subscribe on " + String.valueOf(integerObservable));
          }

          @Override public void onNext(Integer integer) {
            L.print(String.valueOf(integer));
          }

          @Override public void onError(Throwable e) {}

          @Override public void onComplete() {
            L.print("complete on " + String.valueOf(integerObservable));
          }
        });
      }
    });
  }
```

## Observable 过滤操作

选择性的发射项目

### Debounce

去抖动，当一段时间内，没有再次发射项目时，发射最近发射过的一次项目，如果时间段内再次发射了项目，之前的项目将被丢弃，并且计时将重新开始

```java
Subject<Integer> subject = PublishSubject.create();
subject.debounce(500, TimeUnit.MILLISECONDS)
    .subscribe(new Consumer<Integer>() {
      @Override public void accept(Integer integer) throws Exception {
        L.print(String.valueOf(integer)); // 2 3
      }
    });

subject.onNext(0);
Thread.sleep(300);
subject.onNext(1);
Thread.sleep(300);
subject.onNext(2);
Thread.sleep(600);
subject.onNext(3);
subject.onComplete();
```

### Distinct

去重复，通过给定key判断是否已发射过了，发射过的将不会再次发射

```java
Observable.just(1, 2, 3, 1, 2, 4)
    .distinct(new Function<Integer, Integer>() {
      @Override public Integer apply(Integer integer) throws Exception {
        return integer; // 默认key为自身
      }
    })
    .subscribe(new Consumer<Integer>() {
      @Override public void accept(Integer integer) throws Exception {
        L.print(String.valueOf(integer)); // 1 2 3 4
      }
    });
```

### ElementAt

通过下标指定项目发射

```java
Observable.just(0, 1, 2, 3, 4)
    .elementAt(2)
    .subscribe(new Consumer<Integer>() {
      @Override public void accept(Integer integer) throws Exception {
        L.print(String.valueOf(integer)); // 2
      }
    });
```

### Filter

过滤，只发射通过指定测试方法的项目

```java
Observable.just(0, 1, 2, 3, 4, 5)
    .filter(new Predicate<Integer>() {
      @Override public boolean test(Integer integer) throws Exception {
        return integer <= 2;
      }
    })
    .subscribe(new Consumer<Integer>() {
      @Override public void accept(Integer integer) throws Exception {
        L.print(String.valueOf(integer)); // 0 1 2
      }
    });
```

### First

仅发射第一个项目，或者满足条件的第一个项目

```java
Observable.just(0, 1, 2)
    .first(0)
    .subscribe(new Consumer<Integer>() {
      @Override public void accept(Integer integer) throws Exception {
        L.print(String.valueOf(integer)); // 0
      }
    });
```

### IgnoreElements

禁止发射所有项目，只发射结束或错误的通知

```java
Observable.just(0, 1, 2, 3)
    .ignoreElements()
    .subscribe(new Action() {
      @Override public void run() throws Exception {
        L.print("end.");
      }
    });
```

### Last

仅发射最后一个项目，或者满足条件的最后一个项目

```java
Observable.just(0,1,2,3,4)
    .last(0)
    .subscribe(new Consumer<Integer>() {
      @Override public void accept(Integer integer) throws Exception {
        L.print(String.valueOf(integer)); // 4
      }
    });
```

### Sample

定期观察源 Observable 发射的项目，发射距观察点最近的上一次发射的项目

```java
Observable.create(
    new ObservableOnSubscribe<Integer>() {
      @Override public void subscribe(ObservableEmitter<Integer> e) throws Exception {
        e.onNext(0);
        Thread.sleep(180);
        e.onNext(1);
        Thread.sleep(180);
        e.onNext(2); // 360
        Thread.sleep(180);
        e.onNext(3); // 540
      }
    })
    .sample(400, TimeUnit.MILLISECONDS)
    .subscribe(new Consumer<Integer>() {
      @Override public void accept(Integer integer) throws Exception {
        L.print(String.valueOf(integer)); // 2 3
      }
    });
```

### Skip

跳过指定数量或时间内的项目，继续发射后面的项目

```java
Observable.just(0, 1, 2, 3, 4)
    .skip(2)
    .subscribe(new Consumer<Integer>() {
      @Override public void accept(Integer integer) throws Exception {
        L.print(String.valueOf(integer)); // 2 3 4
      }
    });
```

### SkipLast

从后面跳过指定数量或时间内的项目，正常发射前面的项目

```java
Observable.just(0, 1, 2, 3, 4)
    .skipLast(2)
    .subscribe(new Consumer<Integer>() {
      @Override public void accept(Integer integer) throws Exception {
        L.print(String.valueOf(integer)); // 0 1 2
      }
    });
```

### Take

仅仅发射指定数量或时间内的项目

```java
Observable.just(0, 1, 2, 3, 4)
    .take(2)
    .subscribe(new Consumer<Integer>() {
      @Override public void accept(Integer integer) throws Exception {
        L.print(String.valueOf(integer)); // 0 1
      }
    });
```

### TakeLast

仅仅发射末尾指定数量或时间内的项目

```java
Observable.just(0, 1, 2, 3, 4)
    .takeLast(2)
    .subscribe(new Consumer<Integer>() {
      @Override public void accept(Integer integer) throws Exception {
        L.print(String.valueOf(integer)); // 3 4
      }
    });
```

## Observable 合并操作

和多个 Observable 共同创建发射序列

### CombineLatest

将两个或多个Observable正在发射的最新项目通过合并方法合并成新项目后再发射

```java
/*
  |  |  |  |  |
  0  1  2
  a        b  c
*/
Observable.combineLatest(
    Observable.create(new ObservableOnSubscribe<Integer>() {
      @Override public void subscribe(ObservableEmitter<Integer> e) throws Exception {
        e.onNext(0);
        Thread.sleep(100);
        e.onNext(1);
        Thread.sleep(100);
        e.onNext(2);
        e.onComplete();
      }
    }).subscribeOn(Schedulers.newThread()),
    Observable.create(new ObservableOnSubscribe<String>() {
      @Override public void subscribe(ObservableEmitter<String> e) throws Exception {
        e.onNext("a");
        Thread.sleep(300);
        e.onNext("b");
        Thread.sleep(100);
        e.onNext("c");
        e.onComplete();
      }
    }).subscribeOn(Schedulers.newThread()),
    new BiFunction<Integer, String, String>() {
      @Override public String apply(Integer integer, String s) throws Exception {
        return integer + s;
      }
    }
```

### Join

当源 Observable 发出一个项目时，如果在另一个 Observable 当前发射项目的时间窗口之内，将两个来自不同 Observable 的项目通过方法组合成新项目后再发射

```java
/*
     *    *    *    *
    /    /    /    /
   a    b    c    d
  |    |    |    |
  0    1         2
   \    \         \
    *    \         *
          \
           \
            \
             *
*/
Observable
    .create(new ObservableOnSubscribe<Integer>() {
      @Override public void subscribe(ObservableEmitter<Integer> e) throws Exception {
        e.onNext(0);
        Thread.sleep(200);
        e.onNext(1);
        Thread.sleep(400);
        e.onNext(2);
        e.onComplete();
      }
    })
    .subscribeOn(Schedulers.newThread())
    .join(
        Observable.create(new ObservableOnSubscribe<String>() {
          @Override public void subscribe(ObservableEmitter<String> e) throws Exception {
            e.onNext("a");
            Thread.sleep(200);
            e.onNext("b");
            Thread.sleep(200);
            e.onNext("c");
            Thread.sleep(200);
            e.onNext("d");
            e.onComplete();
          }
        }).subscribeOn(Schedulers.newThread()),
        /* 为源 Observable 发射的每个项目设置时效（转换成窗口类型） */
        new Function<Integer, ObservableSource<Integer>>() {
          @Override
          public ObservableSource<Integer> apply(final Integer integer) throws Exception {
            int delay = 100;
            if (integer == 1) {
              delay = 250;
            }
            return Observable
                .timer(delay, TimeUnit.MILLISECONDS)
                .map(new Function<Long, Integer>() {
                  @Override public Integer apply(Long aLong) throws Exception {
                    return integer;
                  }
                });
          }
        },
        /* 为join的 Observable 发射的每个项目设置时效（转换成窗口类型） */
        new Function<String, ObservableSource<String>>() {
          @Override public ObservableSource<String> apply(final String s) throws Exception {
            return Observable
                .timer(100, TimeUnit.MILLISECONDS)
                .map(new Function<Long, String>() {
                  @Override public String apply(Long aLong) throws Exception {
                    return s;
                  }
                });
          }
        }, new BiFunction<Integer, String, String>() {
          @Override public String apply(Integer integer, String s) throws Exception {
            return integer + s;
          }
        }
    )
    .subscribe(new Consumer<String>() {
      @Override public void accept(String s) throws Exception {
        L.print(s); // 0a 1b 1c 2d
      }
    });
```

### Merge

将两个或多个 Observable 合并成一个 Observable，让其行为和单个 Observable 一样

```java
Observable
    .merge(
        Observable.just(1, 3, 5, 7),
        Observable.just(2, 4, 6, 8)
    )
    .subscribe(new Consumer<Integer>() {
      @Override public void accept(Integer integer) throws Exception {
        L.print(String.valueOf(integer)); // 1 3 5 7 2 4 6 8
      }
    });
```

### StartWith

在源 Observable 发射项目之前发射指定的项目

```java
Observable.just(0, 1, 2, 3)
    .startWith(Arrays.asList(3, 4))
    .subscribe(new Consumer<Integer>() {
      @Override public void accept(Integer integer) throws Exception {
        L.print(String.valueOf(integer)); // 3 4 0 1 2 3
      }
    });
​````

### Switch

 订阅一个发射项目类型为 Observable 的 Observable，每当新的 Observable 被发射时，将会取消订阅之前的 Observable，开始订阅新的 Observable

​```java
/*
  |  |  |  |  |  |
  -> 0  1  2  3 ...
  ->      2  4  6
*/
Observable.switchOnNext(Observable.create(
    new ObservableOnSubscribe<ObservableSource<Integer>>() {
      @Override
      public void subscribe(ObservableEmitter<ObservableSource<Integer>> e) throws Exception {
        e.onNext(Observable.interval(100, TimeUnit.MILLISECONDS)
            .map(new Function<Long, Integer>() {
              @Override public Integer apply(Long aLong) throws Exception {
                return aLong.intValue();
              }
            }));
        Thread.sleep(240);
        e.onNext(Observable.just(2, 4, 6));
        e.onComplete();
      }
    }
)).observeOn(AndroidSchedulers.mainThread())
    .subscribe(new Consumer<Integer>() {
      @Override public void accept(Integer integer) throws Exception {
        L.print(String.valueOf(integer)); // 0 1 2 4 6
      }
    });
```

### Zip

将两个或多个 Observable 发射的项目通过方法合并出新的类型后再发射

```java
Observable.just(0, 1, 2, 3)
    .zipWith(Observable.just("a", "b", "c", "d"),
        new BiFunction<Integer, String, String>() {
          @Override public String apply(Integer integer, String s) throws Exception {
            return integer + s;
          }
        })
    .subscribe(new Consumer<String>() {
      @Override public void accept(String s) throws Exception {
        L.print(s); // 0a 1b 2c 3d
      }
    });
  }
```

## Observable 错误处理

对错误通知到来时的一些处理

### Catch

拦截 `onError` 通知，将其转换为正常序列或其他项目

- `onErrorReturn` 出现错误时，发射一个特定的项目，然后正常终止

```java
Observable
    .create(new ObservableOnSubscribe<Integer>() {
      @Override public void subscribe(ObservableEmitter<Integer> e) throws Exception {
        e.onNext(0);
        e.onError(new AssertionError("test"));
      }
    })
    .onErrorReturn(new Function<Throwable, Integer>() {
      @Override public Integer apply(Throwable throwable) throws Exception { return -1; }
    })
    .subscribe(new Observer<Integer>() {
      @Override public void onSubscribe(Disposable d) {}

      @Override public void onNext(Integer integer) {
        L.print(String.valueOf(integer)); // 0 -1
      }

      @Override public void onError(Throwable e) { L.print(e); }

      @Override public void onComplete() { L.print("onComplete"); /* call this */ }
    });
```

- `onErrorResumeNext`

当出现错误时，切换到备用 Observable 继续发射项目

```java
/* onErrorResumeNext test */
Observable
    .create(new ObservableOnSubscribe<Integer>() {
      @Override public void subscribe(ObservableEmitter<Integer> e) throws Exception {
        e.onNext(0);
        e.onNext(1);
        throw new AssertionError("test");
      }
    })
    .onErrorResumeNext(Observable.just(2, 3, 4))
    .subscribe(new Consumer<Integer>() {
      @Override public void accept(Integer integer) throws Exception {
        L.print(String.valueOf(integer)); // 0 1 2 3 4
      }
    });
```

- `onExceptionResumeNext`

当出现错误时，如果是Exception类型，切换到备用 Observable 继续发射项目，否则发出错误通知

```java
Observable
    .create(new ObservableOnSubscribe<Integer>() {
      @Override public void subscribe(ObservableEmitter<Integer> e) throws Exception {
        e.onNext(0);
        e.onNext(1);
        /* throw new AssertionError("test"); */
        throw new Exception("test");
      }
    })
    .onExceptionResumeNext(
        Observable.just(2, 3, 4))
    .subscribe(new Observer<Integer>() {
      @Override public void onSubscribe(Disposable d) {}

      @Override public void onNext(Integer integer) {
        L.print(String.valueOf(integer)); // error is Exception ? 0 1 2 3 4 : 0 1
      }

      @Override public void onError(Throwable e) {
        L.print(e); // call this if error not a Exception 
      }

      @Override public void onComplete() { L.print("onComplete"); }
    });
```

### Retry

在源 Observable 发出错误后重新订阅 Observable 期望再次发射时没有错误

```java
/* 0 1 0 1 error */
Observable
    .create(new ObservableOnSubscribe<Integer>() {
      @Override public void subscribe(ObservableEmitter<Integer> e) throws Exception {
        e.onNext(0);
        e.onNext(1);
        throw new AssertionError("test");
      }
    })
    .retry(1) // 重试一次
    .subscribe(new Observer<Integer>() {
      @Override public void onSubscribe(Disposable d) {}

      @Override public void onNext(Integer integer) { L.print(String.valueOf(integer)); }

      @Override public void onError(Throwable e) { L.print(e); }

      @Override public void onComplete() { L.print("onComplete"); }
    });
```

## Observable 辅助操作

对 Observable 的一些工具方法

### Delay

将发射序列整体延迟一段时间后再发射

```java
Observable.just(0, 1, 2, 3)
    .delay(100, TimeUnit.MILLISECONDS)
    .subscribe(new Consumer<Integer>() {
      @Override public void accept(Integer integer) throws Exception {
        L.print(String.valueOf(integer)); // 100毫秒后 0 1 2 3
      }
    });
```

### Do

注册 Observable 各个生命周期的监听，看起来就像订阅了 Observable 一样

```java
Observable.just(0, 1, 2, 3)
    /* 注册 onSubscribe 监听 */
    .doOnSubscribe(new Consumer<Disposable>() {
      @Override public void accept(Disposable disposable) throws Exception {}
    })
    /* 注册 onNext 监听 */
    .doOnNext(new Consumer<Integer>() {
      @Override public void accept(Integer integer) throws Exception {}
    })
    /* 注册 onComplete 监听 */
    .doOnComplete(new Action() {
      @Override public void run() throws Exception {}
    })
    /* 注册 onError 监听 */
    .doOnError(new Consumer<Throwable>() {
      @Override public void accept(Throwable throwable) throws Exception {}
    })
    /* 注册 onNext 和 onComplete 监听 */
    .doOnEach(new Observer<Integer>() {
      @Override public void onSubscribe(Disposable d) { }
      @Override public void onNext(Integer integer) { }
      @Override public void onError(Throwable e) { }
      @Override public void onComplete() {}
    });
```

### Materialize/Dematerialize

前者把原始发射序列转换成代表每个发射项目的 Notification 对象序列，后者还原成原始序列

```java
Observable<Notification<Integer>> materialize = Observable.just(0, 1, 2)
    .materialize();

materialize
    .subscribe(new Consumer<Notification<Integer>>() {
      @Override public void accept(Notification<Integer> integerNotification) throws Exception {
        if (integerNotification.isOnComplete()) {
          L.print("onComplete");
        } else if (integerNotification.isOnNext()) {
          L.print(String.valueOf(integerNotification.getValue()));
        } else if (integerNotification.isOnError()) {
          L.print(integerNotification.getError());
        }
      }
    });

materialize.dematerialize()
    .subscribe(new Consumer<Object>() {
      @Override public void accept(Object o) throws Exception {
        L.print(String.valueOf(o)); // 0 1 2
      }
    });
```

### ObserveOn

指定观察者运行的调度器

```java
Observable.just(0, 1, 2)
    .observeOn(Schedulers.newThread())
    .subscribe(new Consumer<Integer>() {
      @Override public void accept(Integer integer) throws Exception {
        L.print(String.valueOf(integer)); // 0 1 2 on thread RxNewThreadScheduler-1
      }
    });
```

### Serialize

将可能运行在多个线程并出现发射顺序混乱的 Observable 强制序列化发射，符合发射规则

```java
/*
  t1 3    *
     |  |  |
  t2  0  1  2
  
  -> 3 0 1 onComplete
*/
Observable
    .unsafeCreate(new ObservableSource<Integer>() {
      @Override public void subscribe(final Observer<? super Integer> observer) {
        TestHelper.runOnNewThread(new Runnable() {
          @Override public void run() {
            TestHelper.sleep(20);
            observer.onNext(0);
            TestHelper.sleep(120);
            observer.onNext(1);
            TestHelper.sleep(100);
            observer.onNext(2);
          }
        });
        TestHelper.runOnNewThread(new Runnable() {
          @Override public void run() {
            observer.onNext(3);
            TestHelper.sleep(150);
            observer.onComplete();
          }
        });
      }
    })
    .serialize()
    .subscribe(new DisposableObserver<Integer>() {
      @Override public void onNext(Integer integer) {
        L.print(String.valueOf(integer));
      }

      @Override public void onError(Throwable e) {}

      @Override public void onComplete() {
        L.print("onComplete");
      }
    });
```

### Subscribe

订阅观察者

### SubscribeOn

指定 Observable 运行的调度器

```java
Observable
        .create(new ObservableOnSubscribe<Integer>() {
          @Override public void subscribe(ObservableEmitter<Integer> e) throws Exception {
            /* run on thread RxNewThreadScheduler-1 */
            e.onNext(0);
            e.onNext(1);
            e.onNext(2);
            e.onComplete();
          }
        })
        .subscribeOn(Schedulers.newThread())
        .subscribe(new Consumer<Integer>() {
          @Override public void accept(Integer integer) throws Exception {
            L.print(String.valueOf(integer));
          }
        });
```

### TimeInterval

获取发射项目之间的发射的时间间隔的序列

```java
Observable
    .create(new ObservableOnSubscribe<Integer>() {
      @Override public void subscribe(ObservableEmitter<Integer> e) throws Exception {
        Thread.sleep(100);
        e.onNext(0);
        Thread.sleep(120);
        e.onNext(1);
        Thread.sleep(50);
        e.onNext(2);
        Thread.sleep(3);
        e.onComplete();
      }
    })
    .subscribeOn(Schedulers.newThread())
    .timeInterval()
    .subscribe(new Consumer<Timed<Integer>>() {
      @Override public void accept(Timed<Integer> integerTimed) throws Exception {
        L.print(String.valueOf(integerTimed.time())); // 100 120 50 (大约)
      }
    });
```

### Timeout

指定限制一段时间内没有项目发射则发射一个 TimeoutException 到 onError

```java
Observable
    .create(new ObservableOnSubscribe<Integer>() {
      @Override public void subscribe(ObservableEmitter<Integer> e) throws Exception {
        Thread.sleep(200);
        e.onNext(0);
        e.onComplete();
      }
    })
    .timeout(100, TimeUnit.MILLISECONDS)
    .subscribe(new DisposableObserver<Integer>() {
      @Override public void onNext(Integer integer) {}

      @Override public void onError(Throwable e) {
        L.print(e); // call this
      }

      @Override public void onComplete() {}
    });
```

### Timestamp

获取发射项目对应的时间戳序列

```java
Observable
    .create(new ObservableOnSubscribe<Integer>() {
      @Override public void subscribe(ObservableEmitter<Integer> e) throws Exception {
        Thread.sleep(60);
        e.onNext(0);
        Thread.sleep(100);
        e.onNext(1);
        Thread.sleep(80);
        e.onNext(2);
        e.onComplete();
      }
    })
    .timestamp()
    .subscribe(new Consumer<Timed<Integer>>() {
      @Override public void accept(Timed<Integer> integerTimed) throws Exception {
        L.print(String.valueOf(integerTimed.time()));
      }
    });
```

### Using

创建和 Observable 生命周期一致的一次性资源的 Observable 

```java
/*
   -> sub      dispose
       |  |  |  |
   ->          close
 */
Observable
    .using(new Callable<BufferedReader>() {
      @Override public BufferedReader call() throws Exception {
        return new BufferedReader(new StringReader("string line."));
      }
    }, new Function<BufferedReader, ObservableSource<String>>() {
      @Override
      public ObservableSource<String> apply(BufferedReader reader) throws Exception {
        return Observable.just(reader.readLine());
      }
    }, new Consumer<BufferedReader>() {
      @Override public void accept(BufferedReader reader) throws Exception {
        reader.close();
      }
    })
    .subscribe(new Consumer<String>() {
      @Override public void accept(String s) throws Exception {
        L.print(s); // string line.
      }
    });
```

## Observable 条件操作

评估 Observable 的相关操作

### All

判断所有项目是否满足某个条件

```java
Observable
    .just(0, 1, 2, 3, 4)
    .all(new Predicate<Integer>() {
      @Override public boolean test(Integer integer) throws Exception {
        return integer < 4;
      }
    })
    .subscribe(new Consumer<Boolean>() {
      @Override public void accept(Boolean aBoolean) throws Exception {
        L.print(String.valueOf(aBoolean)); // false
      }
    });
```

### Amb

选出最先发射项目的那个 Observable，只发射它的所有项目

```java
Observable
    .amb(Arrays.asList(
        Observable.just(1, 3, 5)
            .delay(40, TimeUnit.MILLISECONDS),
        Observable.just(2, 4, 6)
            .delay(60, TimeUnit.MILLISECONDS))
    )
    .subscribe(new Consumer<Integer>() {
      @Override public void accept(Integer integer) throws Exception {
        L.print(String.valueOf(integer)); // 2 4 6
      }
    });
​````

### Contains

测试发射序列中是否存在特定的项目

​```java
Observable
    .just(0, 1, 2, 3)
    .contains(2)
    .subscribe(new Consumer<Boolean>() {
      @Override public void accept(Boolean aBoolean) throws Exception {
        L.print(String.valueOf(aBoolean)); // true
      }
    });
```

### DefaultIfEmpty

如果源 Observable 没有发射项目正常结束，那么发射默认的项目

```java
Observable.<Integer>empty()
    .defaultIfEmpty(-1)
    .subscribe(new Consumer<Integer>() {
      @Override public void accept(Integer integer) throws Exception {
        L.print(String.valueOf(integer)); // -1
      }
    });
```

### SqeuenceEqual

对比两个 Observable 的发射序列是否一致

```java
Observable.sequenceEqual(
    Observable.create(new ObservableOnSubscribe<Integer>() {
      @Override public void subscribe(ObservableEmitter<Integer> e) throws Exception {
        e.onNext(0);
        e.onNext(1);
        e.onNext(2);
        e.onComplete();
      }
    }),
    Observable.just(0, 1, 2)
).subscribe(new Consumer<Boolean>() {
  @Override public void accept(Boolean aBoolean) throws Exception {
    L.print(String.valueOf(aBoolean));
  }
});
```

### SkipUntil

源 Observable 不断丢弃发射的项目，并观察另一个 Observable，当它开始发射项目时，源 Observable 停止丢弃，开始发射剩余的项目

```java
/*
   |  |  |  |
   0  1  2  3
          a *
 */
Observable.create(new ObservableOnSubscribe<Integer>() {
  @Override public void subscribe(ObservableEmitter<Integer> e) throws Exception {
    e.onNext(0);
    Thread.sleep(100);
    e.onNext(1);
    Thread.sleep(100);
    e.onNext(2);
    Thread.sleep(100);
    e.onNext(3);
    e.onComplete();
  }
}).skipUntil(Observable.just('a').delay(220, TimeUnit.MILLISECONDS))
    .subscribe(new Consumer<Integer>() {
      @Override public void accept(Integer integer) throws Exception {
        L.print(String.valueOf(integer)); // 3
      }
    });
```

### SkipWhile

不断丢弃满足条件的项目，直到不满足条件后，开始发射剩余项目

```java
Observable.just(0, 1, 2, 3, 2, 5)
    .skipWhile(new Predicate<Integer>() {
      @Override public boolean test(Integer integer) throws Exception {
        return integer < 2;
      }
    })
    .subscribe(new Consumer<Integer>() {
      @Override public void accept(Integer integer) throws Exception {
        L.print(String.valueOf(integer)); // 2 3 2 5
      }
    });
```

### TakeUntil

观察另一个 Observable，当它开始发射项目时，源 Observable 丢弃后面所有项目

```java
/*
       |  |  |  |
       0  1  2  3
              a *
    */
    Observable.create(new ObservableOnSubscribe<Integer>() {
      @Override public void subscribe(ObservableEmitter<Integer> e) throws Exception {
        e.onNext(0);
        Thread.sleep(100);
        e.onNext(1);
        Thread.sleep(100);
        e.onNext(2);
        Thread.sleep(100);
        e.onNext(3);
        e.onComplete();
      }
    }).takeUntil(Observable.just('a').delay(220, TimeUnit.MILLISECONDS))
        .subscribe(new Consumer<Integer>() {
          @Override public void accept(Integer integer) throws Exception {
            L.print(String.valueOf(integer)); // 0 1 2
          }
        });
```

### TakeWhile

满足条件不断发射项目，当条件不满足时，丢弃后面的所有项目

```java
Observable
    .just(0, 1, 2, 3, 2, 5)
    .takeWhile(new Predicate<Integer>() {
      @Override public boolean test(Integer integer) throws Exception {
        return integer < 2;
      }
    })
    .subscribe(new Consumer<Integer>() {
      @Override public void accept(Integer integer) throws Exception {
        L.print(String.valueOf(integer)); // 0 1
      }
    });
```

## Observable 背压操作

处理生产者比消费者慢的情况

### onBackpressureBuffer

在订阅者调用 request 方法时，发射下一项数据，可设置缓存大小，缓存满后抛出异常

```java
Flowable.range(0, 10)
        .onBackpressureBuffer()
        .subscribe(new Subscriber<Integer>() {
          Subscription s;

          @Override public void onSubscribe(Subscription s) {
            this.s = s;
            s.request(1);
          }

          @Override public void onNext(Integer integer) {
            L.print(String.valueOf(integer));
            TestHelper.sleep(500); // 0 ~ 10 每隔 500 毫秒输出
            s.request(1);
          }

          @Override public void onError(Throwable t) {}
          @Override public void onComplete() {}
        });
```

### onBackpressureDrop

### onBackpressureLatest

## Observable 可连接操作

可以更精确的动态控制订阅状态

### Publish

将一个普通的 Obsevable 转换为一个可连接的 ConnectableObservable

### Connect

激活 Connectable 的发射器，使其开始发射项目

```java
ConnectableObservable<Integer> publish = Observable.just(0, 1, 2, 3, 4)
    .publish();
publish.subscribe(new Consumer<Integer>() {
  @Override public void accept(Integer integer) throws Exception {
    L.print(String.valueOf(integer)); // 0 1 2 3 4
  }
});
publish.connect();
```

### Refcount

将一个可连接的 Connectable 转换为一个普通的 Observable

```java
ConnectableObservable<Integer> publish = Observable.range(0, 4).publish();
publish.refCount()
    .subscribe(new Consumer<Integer>() {
      @Override public void accept(Integer integer) throws Exception {
        L.print(String.valueOf(integer)); // 0 1 2 3
      }
    });
```

### Replay

保证每个订阅者接收完整的发射序列

```java
ConnectableObservable<Integer> replay = Observable.create(
    new ObservableOnSubscribe<Integer>() {
      @Override public void subscribe(ObservableEmitter<Integer> e) throws Exception {
        for (int i = 0; i < 4; i++) {
          Thread.sleep(100);
          e.onNext(i);
        }
      }
    })
    .replay();
replay.subscribe(new Consumer<Integer>() {
  @Override public void accept(Integer integer) throws Exception {
    L.print("sub 1: %d", integer); // 0 1 2 3
  }
});
replay.connect();
TestHelper.sleep(200);
replay.subscribe(new Consumer<Integer>() {
  @Override public void accept(Integer integer) throws Exception {
    L.print("sub 2: %d", integer); // 0 1 2 3
  }
});
```

## Observable 转换操作

### To

将 Observable 转换成另一个对象或者数据结构

```java
/* to feature */
Future<Integer> future = Single.create(new SingleOnSubscribe<Integer>() {
  @Override public void subscribe(SingleEmitter<Integer> e) throws Exception {
    Thread.sleep(1000);
    e.onSuccess(1);
  }
}).toFuture();

try {
  L.print(String.valueOf(future.get()));
} catch (InterruptedException e) {
  e.printStackTrace();
} catch (ExecutionException e) {
  e.printStackTrace();
}

/* to list */
Observable.just(0, 1, 2)
    .toList()
    .subscribe(new Consumer<List<Integer>>() {
      @Override public void accept(List<Integer> integers) throws Exception {
        L.printList(integers);
      }
    });

/* to blocking iterable */
for (int i : Observable.just(0, 1, 2, 3).blockingIterable()) {
  L.print(String.valueOf(i));
}

/* to map */
Observable.just(0, 1, 2)
    .toMap(new Function<Integer, String>() {
      @Override public String apply(Integer integer) throws Exception {
        return String.valueOf(integer);
      }
    })
    .subscribe(new Consumer<Map<String, Integer>>() {
      @Override public void accept(Map<String, Integer> stringIntegerMap) throws Exception {
        L.printMap(stringIntegerMap); // { 0: 0, 1: 1, 2: 2}
      }
    });
```

## 参考

- http://reactivex.io/documentation/operators.html

- http://3.course.uprogrammer.cn/rxjava_cn/index.html