# Binder 和 AIDL 的使用

- [Binder简介](#binder简介)

- [Binder使用](#binder使用)

- [AIDL工具](#aidl工具)



## binder简介

Binder在android中一般负责与远程service进行进程间通信，在Linux层面作为一个驱动而存在，依附于一段内存，运行在内核态。

- Binder框架

Binder用于完成进程间通信(IPC)，使用了由三种结构组合而成的架构。

1. Binder服务端

   Binder服务端本身是一个Binder对象，其创建时，内部会启动一个隐藏线程，用于接收Binder驱动端发送的消息，并回调到 `onTransact()` 方法，Binder服务端将会根据参数进行处理。`onTransact()` 方法的参数顺序将遵循客户端调用 `transact()` 方法参数的顺序。

2. Binder驱动端

   Binder服务端创建时，Binder驱动端中同时会创建一个 Binder类型的 `mRemote` 对象，客户端通过引用这个对象通过调用 `transact()` 方法来向Binder服务端发送请求。

3. Binder客户端

   Binder客户端与服务端交互时，需要先持有Binder驱动的 `mRemote` 对象，通过调用它的 `transact()` 方法，使用IPC（进程间通讯的方式）向服务端发出请求，服务端对请求进行处理。

4. 服务端的 `onTransact()` 的方法参数将会和客户端调用 `transact()` 的方法参数相对应。


> ```java
> /**
>   * binder 请求方法
>   * @param code 请求码，服务端可以根据请求码判断客户端需要执行何种任务。
>   * @param data 客户端数据包裹，其中可以放入几种基本类型和特定的类型。
>   * @param replay 服务端将会把返回的数据放入此数据包。
>   * @param flag 标记IPC模式，0为双向，服务端会返回数据，1为单向，不返回任何数据。
>   */
> public final boolean transact(int code, Parcel data, Parcel replay, int flag) throw RemoteException
> ```

## binder使用

上面介绍了binder，下面将会使用binder来进行Activity组件与远程Service之间的进程间通讯。

总体流程将会如下：

1. 首先实现一个远程Service(在配置文件中配置为新进程)，它将作为远程服务端，提供一些功能供客户端调用，并需要实现它的 `IBinder onBind(Intent intent)` 方法，实现这个方法之后，在Activity中才可以使用 `bindeService()` 方法绑定服务，这个方法的返回值将是一个Binder对象，也是Binder框架中的服务端Binder的角色。
2. 实现服务端的Binder对象，负责处理客户端的请求，并调用远程Service中的方法。
3. 实现客户端的访问逻辑，客户端将会持有驱动端的Binder对象，并向服务端Binder发起请求。

开始实现：

- 首先需要定义一个接口，定义服务端和客户端的交互逻辑，它需要继承 `IInterface` 接口，因为每个新建的Binder都需要实现它，而服务端的Binder类需要实现这个接口。

在这里，客户端和服务端的交互仅仅是接受和返回一个User对象。

```java
public interface UserContract extends IInterface {
  /** 和服务端请求的约定字符串 */
  String DESCRIPTOR = "UserContract";

  /* Binder请求码应该在 FIRST_CALL_TRANSACTION 和 LAST_CALL_TRANSACTION之间 */
  /** 发起 setUser 的请求码 */
  int TRANSACTION_SET_USER = IBinder.FIRST_CALL_TRANSACTION;
  /** 发起 getUser 的请求码 */
  int TRANSACTION_GET_USER = IBinder.FIRST_CALL_TRANSACTION + 1;
  
  /** 向服务端发送user对象 */
  void setUser(User user) throws RemoteException;
  /** 向服务端请求一个user对象 */
  User getUser() throws RemoteException;
}
```

其中User是一个java bean对象。由于使用Binder和远程服务的交互，使用Parcel对象携带数据，只能支持部分数据类型，其中包括 `Parcelable` 类型，这个类型是android支持的以放入内存的方式进行序列化的接口类型，在使用内存传递序列化数据时，比java中 `Serializable` 效率更高，这里传递自定义的User类型时需要实现这个接口。

```java
public class User implements Parcelable {

  private String name;
  private String gender;
  private int age;
  
  public User(String name, String gender, int age) {
    this.name = name;
    this.gender = gender;
    this.age = age;
  }
  /** 实现解析对象方法 */
  protected User(Parcel in) {
    this.name = in.readString();
    this.gender = in.readString();
    this.age = in.readInt();
  }

  public static final Creator<User> CREATOR = new Creator<User>() {
    @Override public User createFromParcel(Parcel in) { return new User(in); }

    @Override public User[] newArray(int size) { return new User[size]; }
  };

  @Override public int describeContents() { return 0; }
  /** 实现序列化方法 */
  @Override public void writeToParcel(Parcel dest, int flags) {
    dest.writeString(this.name);
    dest.writeString(this.gender);
    dest.writeInt(this.age);
  }
}
```

- 然后实现一个远程Service，在配置文件中配置，并实现它的 `onBinder` 方法，这里先返回一个默认的空Binder对象，在实现客户端的访问逻辑之后，定下数据包裹的传递顺序后，再来实现服务端Binder对客户端请求的处理逻辑。

```java
public class UserService extends Service {

  @Override public IBinder onBind(Intent intent) { return new Binder(); } 
}
```

- 接下来就先实现客户端的访问逻辑，先指定数据传递的顺序，才能在服务端的处理方法中按正确的顺序解析。

这里定义一个 `ServiceAccessor` 类型，实现前面的 `UserContract` 接口，方便在Activity中调用。

其中包含一个IBinder类型的 `mRemote` 对象，和实现的两个请求方法，还需要实现一个 `asInterface` 的方法，它将返回一个 `IInterface` 类型的对象，其中包含查询本地Binder对象的逻辑，如果Service不是一个远程Service，它将会直接返回Service包含的Binder对象，而无需调用进程间通讯。

```java
public class ServiceAccessor implements UserContract {

  private IBinder mRemote;
	
  private ServiceAccessor(IBinder mRemote) { this.mRemote = mRemote; }

  public static UserContract asInterface(IBinder iBinder) {
    if (iBinder == null) {
      return null;
    }
    /* 查询服务端是否为本地Binder */
    IInterface iInterface = iBinder.queryLocalInterface(DESCRIPTOR);
    if (iInterface != null && iInterface instanceof UserContract) {
      return (UserContract) iInterface;  // 直接返回服务端的Binder对象
    }
    return new ServiceAccessor(iBinder); // 使用mRemote对象访问服务端
  }

  @Override public void setUser(User user) throws RemoteException {
    Parcel data = Parcel.obtain();       // 请求一个数据包裹
    Parcel replay = Parcel.obtain();
    try {
      data.writeInterfaceToken(DESCRIPTOR);  //写入字符串标识
      if (user == null) {
        data.writeInt(0);                // 写入空标记
      } else {
        data.writeInt(1);
        user.writeToParcel(data, 0);     //写入User数据
      }
      mRemote.transact(TRANSACTION_SET_USER, data, replay, 0); // 向服务端发出setUser请求
      replay.readException();            // 读取可能返回的异常
    } finally {
      data.recycle();                    // 回收
      replay.recycle();
    }
  }

  @Override public User getUser() throws RemoteException {
    Parcel data = Parcel.obtain();
    Parcel replay = Parcel.obtain();
    final User user;
    try {
      data.writeInterfaceToken(DESCRIPTOR);  //写入字符串标识
      mRemote.transact(TRANSACTION_GET_USER, data, replay, 0); // 向服务端发出getUser请求
      if (replay.readInt() == 0) {        // 读取判空
        user = null;
      } else {
        user = User.CREATOR.createFromParcel(replay); // 解析User对象
      }
      replay.readException();             // 读取可能返回的异常
    } finally {
      data.recycle();
      replay.recycle();
    }
    return user;
  }

  @Override public IBinder asBinder() { return mRemote; }
}
```

以上可以看出，客户端的访问逻辑主要是通过持有 `mRemote` 对象并调用它的 `transact()` 方法来向服务端发出请求的，同时如果查询到本地服务端时，直接返回相同接口的服务端Binder对象，相当于直接调用Service的方法。

- 下面需要在Activity中利用 `ServiceAccessor` 来访问远程Service。

在Activity的 `onStart` 和 `onStop` 分别做绑定远程Service和解除绑定的操作，在绑定成功的回调里面获取实例。

```java
...
private ServiceConnection mConn = new ServiceConnection() {
  @Override public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
    UserContract serviceAccessor = ServiceAccessor.asInterface(iBinder);
    try {  // 捕捉远程异常
      serviceAccessor.setUser(new User("runing", "boy", 22));
    } catch (RemoteException ignore) { }
    try {
      User user = serviceAccessor.getUser();
      Log.d(TAG, "get user from remote -> \n" + user);
    } catch (RemoteException ignore) { }
  }

  @Override public void onServiceDisconnected(ComponentName componentName) { }
};
...
@Override protected void onStart() {
  super.onStart();
  bindService(new Intent(this, UserService.class), mConn, Context.BIND_AUTO_CREATE);
}

@Override protected void onStop() {
  super.onStop();
  unbindService(mConn);
}
```

以上就做完了客户端的请求工作。

- 最后是服务端Binder的处理部分

实现一个 `UserClientHandler` 抽象类，它负责接受服务端请求的操作，子类只需要关心数据处理的逻辑即可。

它是一个Binder类，并实现 `UserContract`  接口。

```java
public abstract class UserClientHandler extends Binder implements UserContract {

  public UserClientHandler() { attachInterface(this, DESCRIPTOR); }

  @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
    switch (code) {
    case INTERFACE_TRANSACTION:
      reply.writeString(DESCRIPTOR);
      break;
    case TRANSACTION_SET_USER: {
      data.enforceInterface(DESCRIPTOR);  // 确认字符串口令
      final User user;
      if (data.readInt() == 0) {          // 标记判空
        user = null;
      } else {
        user = User.CREATOR.createFromParcel(data);
      }
      setUser(user);
      reply.readException();
      return true;
    }
    case TRANSACTION_GET_USER: {
      data.enforceInterface(DESCRIPTOR);
      User user = getUser();
      if (user == null) {
        reply.writeInt(0);
      } else {
        reply.writeInt(1);
        user.writeToParcel(reply, 0);
      }
      reply.readException();
      return true;
    }
    }
    return super.onTransact(code, data, reply, flags);
  }

  @Override public IBinder asBinder() { return this; }
}
```

数据包参数的读取顺序和客户端发送顺序相对应，这样才能正确的取出数据。

- 实现服务端的逻辑处理部分。

替换之前返回的默认Binder对象为 `UserClientHandler` 的子类。

```java
public class UserService extends Service {

  @Override
  public IBinder onBind(Intent intent) {
    return new UserClientHandler() {
      @Override public void setUser(User user) throws RemoteException {
        Log.d(TAG, "user from client -> \n" + user);
      }

      @Override public User getUser() throws RemoteException {
        return new User("service", "girl", 0);
      }
    };
  }
}
```

这样就完成了Activity和远程Service利用Binder通讯的所有逻辑。

这个例子只是简单的传递和返回一个对象，如果每次都编写这样的代码难免繁琐，所以这时应该能想到使用脚本生成代码这种方法，避免重复过程，根据接口来生成Binder通讯代码，Google 已经想到了这点，使用了更高级的数据定义语言，同时也是一个工具，就是aidl数据定义语言，它可以根据已定义的交互接口快速生成Binder通信代码，方便快捷。

## aidl工具

android中为了方便组件与远程Service通讯提供了aidl工具，可以根据定义的接口直接生成冗杂的binder通讯代码，大大简化了开发，现在来实现上面的 `setUser` 和 `getUser` 功能。

- 首先定义aidl接口，aidl接口默认支持下列类型：

1. java 基本类型 int、long、char、boolean、double、float
2. String、CharSequence
3. List 另一端实际接收的具体类始终是 ArrayList
4. Map 另一端实际接收的具体类始终是 HashMap

如果想在aidl中传递自定义的类型，这里是 `User` 类型，需要首先实现 `Parcelable` 接口，然后为这个类型单独声明一个aidl文件，为了让定义的aidl接口能够正确导入。

需要让aidl文件建立在与 `User` 类相同的包下

```java
// User.aidl
package com.runing.testmodule;
parcelable User;
```

现在可声明接口，这里声明为 `UserManager`。

```java
// UserManager.aidl
package com.runing.testmodule;
import com.runing.testmodule.User;

interface UserManager {

    void setUser(in User user);
    User getUser();
}
```

- 编译之后，自动生成了如下代码：

```java
package com.runing.testmodule;

public interface UserManager extends android.os.IInterface {
  
  public void setUser(com.runing.testmodule.User user) throws android.os.RemoteException;
  public com.runing.testmodule.User getUser() throws android.os.RemoteException;
  
  public static abstract class Stub extends android.os.Binder implements com.runing.testmodule.UserManager {
    private static final java.lang.String DESCRIPTOR = "com.runing.testmodule.UserManager";

    public Stub() { this.attachInterface(this, DESCRIPTOR); }

    public static com.runing.testmodule.UserManager asInterface(android.os.IBinder obj) {
      if ((obj == null)) { return null; }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin != null) && (iin instanceof com.runing.testmodule.UserManager))) {
        return ((com.runing.testmodule.UserManager) iin);
      }
      return new com.runing.testmodule.UserManager.Stub.Proxy(obj);
    }

    @Override public android.os.IBinder asBinder() {
      return this;
    }

    @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException {
      switch (code) {
      case INTERFACE_TRANSACTION: {
        reply.writeString(DESCRIPTOR);
        return true;
      }
      case TRANSACTION_setUser: {
        data.enforceInterface(DESCRIPTOR);
        com.runing.testmodule.User _arg0;
        if ((0 != data.readInt())) {
          _arg0 = com.runing.testmodule.User.CREATOR.createFromParcel(data);
        } else {
          _arg0 = null;
        }
        this.setUser(_arg0);
        reply.writeNoException();
        return true;
      }
      case TRANSACTION_getUser: {
        data.enforceInterface(DESCRIPTOR);
        com.runing.testmodule.User _result = this.getUser();
        reply.writeNoException();
        if ((_result != null)) {
          reply.writeInt(1);
          _result.writeToParcel(reply, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
        } else {
          reply.writeInt(0);
        }
        return true;
      }
      }
      return super.onTransact(code, data, reply, flags);
    }

    private static class Proxy implements com.runing.testmodule.UserManager {
      private android.os.IBinder mRemote;

      Proxy(android.os.IBinder remote) { mRemote = remote; }

      @Override public android.os.IBinder asBinder() { return mRemote; }

      public java.lang.String getInterfaceDescriptor() { return DESCRIPTOR; }

      @Override public void setUser(com.runing.testmodule.User user) throws android.os.RemoteException {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          if ((user != null)) {
            _data.writeInt(1);
            user.writeToParcel(_data, 0);
          } else {
            _data.writeInt(0);
          }
          mRemote.transact(Stub.TRANSACTION_setUser, _data, _reply, 0);
          _reply.readException();
        } finally {
          _reply.recycle();
          _data.recycle();
        }
      }

      @Override public com.runing.testmodule.User getUser() throws android.os.RemoteException {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        com.runing.testmodule.User _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          mRemote.transact(Stub.TRANSACTION_getUser, _data, _reply, 0);
          _reply.readException();
          if ((0 != _reply.readInt())) {
            _result = com.runing.testmodule.User.CREATOR.createFromParcel(_reply);
          } else {
            _result = null;
          }
        } finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
    }

    static final int TRANSACTION_setUser = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
    static final int TRANSACTION_getUser = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
  }
}
```

其中的 `UserManager.Proxy`  即为客户端访问服务端的逻辑，`UserManager.Stub` 类为服务端接受数据的处理逻辑。

使用时，替换之前的实现类即可，Activity中的serviceConnection的回调中。

```java
// MainActivity.java
...
@Override public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
   UserManager serviceAccessor = UserManager.Stub.asInterface(iBinder);
   try {
     serviceAccessor.setUser(new User("runing", "boy", 22));
   } catch (RemoteException e) {
     e.printStackTrace();
   }
   try {
     User user = serviceAccessor.getUser();
     Log.d(TAG, "get user from remote -> \n" + user);
   } catch (RemoteException e) {
     e.printStackTrace();
   }
}
```

远程Service中实现 `Stub` 类的子类。

```java
// UserService.java
@Override public IBinder onBind(Intent intent) {
  return new UserManager.Stub() {
    @Override public void setUser(User user) throws RemoteException {
	  // do something.
    }

    @Override public User getUser() throws RemoteException { return new User(); }
  };
}
```

以上就是aidl的用法。