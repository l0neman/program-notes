# Android Provider 实现分析

## 前言

ContentProvider 是 Android 系统的四大组件之一，它提供了一种共享数据的机制，同时也是一种进程间通信的方式，下面基于 Android 6.0.1 系统源码分析 Provider 组件的工作及实现原理，分为两部分，Provider 的查询和 Provider 的安装。

## 使用方式

一般的开发工作很少用到 ContentProvider，下面说一下它的用法。

### 实现数据提供者

首先需要实现自定义的数据提供者，需要实现增删查改的数据操作方法，还可实现自定义操作的 `call` 方法：

```java
public class ExampleContentProvider extends ContentProvider {

  @Override public boolean onCreate() {
    return false;
  }

  @Override
  public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }

  @Override public String getType(Uri uri) { return null; }

  @Override public Uri insert(Uri uri, ContentValues values) { return null; }

  @Override
  public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

  @Override
  public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
```

### 注册数据提供者

实现数据提供者后就可以在清单文件中注册了，注册时需要指定 Uri，当其他进程需要访问时，使用 Uri 即可获得 ContentProvider 的远程代理，然后使用代理即可向 ContentProvider 发送数据操作请求。

```xml
<application>
	<provider
        android:name="ExampleContentProvider"
        android:authorities="l0neman.provider.example" />
</application>
```

### 访问数据提供者

上面的工作完成后就可以向数据提供者发送请求了，使用 `content://` 前缀加 Uri 即可，还可在后面加数据访问路径：

```java
Bundle result = contenxt.getContentResolver().call(
  Uri.parse("content://l0neman.provider.example/path"), "method", null, null);
```

下面开始分析 Provider 的源码实现。

## Provider 的查询

从获得 ContentProvider 开始分析，即 `content.getCOntentResolver()`。

### Client

#### ContextImpl

```java
private final ApplicationContentResolver mContentResolver;
...

public ContextImpl() {
    ...
    mContentResolver = new ApplicationContentResolver(this, mainThread, user);
}

@Override
public ContentResolver getContentResolver() {
    return mContentResolver;
}
```

## Provider 的安装



