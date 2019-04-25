package com.runing.utilslib.arscparser.util;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;

public class Reflect {

  private Class<?> clazz;
  private Object target;
  private Injector injector = new Injector();
  private Invoker invoker = new Invoker();
  private static ThreadLocal<Reflect> sUtilsInstance;

  private Reflect() {}

  /**
   * clean an object.
   */
  public static void recycle() {
    sUtilsInstance = null;
  }

  /* 缓存工具对象，不使用常量。 */
  private static Reflect apply() {
    if (sUtilsInstance == null) {
      sUtilsInstance = new ThreadLocal<>();
    }
    Reflect Reflect = sUtilsInstance.get();
    if (Reflect == null) {
      Reflect = new Reflect();
      return Reflect;
    }
    Reflect.clear();
    Reflect.invoker.clear();
    Reflect.injector.clear();
    return Reflect;
  }

  private void clear() {
    clazz = null;
    target = null;
  }

  /**
   * Gets the {@link Injector} instance from target object.
   *
   * @param target Target object
   */
  public static Injector inject(Object target) {
    Reflect apply = apply();
    apply.target = target;
    return apply.injector;
  }

  /**
   * Gets the {@link Injector} instance from target Class.
   *
   * @param clazz Target class
   */
  public static Injector inject(Class<?> clazz) {
    Reflect apply = apply();
    apply.clazz = clazz;
    return apply.injector;
  }


  /**
   * Gets the {@link Invoker} instance from target object.
   *
   * @param target Target object
   */
  public static Invoker invoke(Object target) {
    Reflect apply = apply();
    apply.target = target;
    return apply.invoker;
  }

  /**
   * Gets the {@link Invoker} instance from target clazz.
   *
   * @param clazz Target clazz
   */
  public static Invoker invoke(Class<?> clazz) {
    Reflect apply = apply();
    apply.clazz = clazz;
    return apply.invoker;
  }

  private static Class<?> getSuperClassByLevel(Class<?> clazz, final int level) {
    Class<?> targetClass = clazz;
    for (int i = 0; i < level; i++) {
      if (targetClass != null) {
        targetClass = targetClass.getSuperclass();
      }
    }
    return targetClass;
  }

  private static Class<?> getSuperClassByLevel(Object target, final int level) {
    return getSuperClassByLevel(target.getClass(), level);
  }

  /**
   * utils base class
   */
  private class BaseReflect {
    int superLevel;

    BaseReflect superLevel(int level) {
      this.superLevel = level;
      return this;
    }

    Class<?> getTargetClass() {
      return target != null ?
          getSuperClassByLevel(target, superLevel) :
          getSuperClassByLevel(clazz, superLevel);
    }
  }

  /**
   * Operate on the target field
   */
  public class Injector extends BaseReflect {
    private String fieldName;

    public Injector field(String name) {
      this.fieldName = name;
      return this;
    }

    void clear() {
      fieldName = null;
      superLevel = 0;
    }

    /**
     * Sets the superclass level.
     *
     * @param level superclass level
     * @return self
     */
    @Override
    public Injector superLevel(int level) {
      return (Injector) super.superLevel(level);
    }

    /**
     * see {@linkplain #setX(Object)}
     *
     * @throws Exception {@link NoSuchFieldException} or {@link IllegalAccessException}
     */
    public void set(Object value) throws Exception {
      setX(value);
    }

    /**
     * Sets the value for the target field.
     *
     * @param value value
     * @throws ReflectiveOperationException Does not support Android API 19 below.
     *                                      {@link NoSuchFieldException} or
     *                                      {@link IllegalAccessException}
     */
    public void setX(Object value) throws ReflectiveOperationException {
      final Class<?> targetClass = getTargetClass();

      Field field = targetClass.getDeclaredField(fieldName);
      field.setAccessible(true);

      if (target != null) {
        field.set(target, value);
      } else {
        field.set(null, value);
      }
    }

    /**
     * see {@linkplain #getX()}
     *
     * @throws Exception {@link NoSuchFieldException} or {@link IllegalAccessException}
     */
    public Object get() throws Exception {
      return getX();
    }

    /**
     * Gets the value of the target.
     *
     * @return field value
     * @throws ReflectiveOperationException {@link NoSuchFieldException} or
     *                                      {@link IllegalAccessException}
     */
    public Object getX() throws ReflectiveOperationException {
      Class<?> targetClass = getTargetClass();

      Field field = targetClass.getDeclaredField(fieldName);
      field.setAccessible(true);

      return target != null ?
          field.get(target) :
          field.get(null);
    }
  }

  /**
   * Operate on the target method
   */
  public class Invoker extends BaseReflect {
    private String methodName;
    private Class<?>[] parameterTypes;

    @Override
    Invoker superLevel(int level) {
      return (Invoker) super.superLevel(level);
    }

    void clear() {
      methodName = null;
      parameterTypes = null;
      superLevel = 0;
    }

    /**
     * Set the method name.
     *
     * @param methodName method"s name
     * @return self
     */
    public Invoker method(String methodName) {
      this.methodName = methodName;
      return this;
    }

    /**
     * Sets the type of the method's arguments.
     *
     * @param parameterTypes methods params types.
     * @return self
     */
    public Invoker parameterTypes(Class<?>... parameterTypes) {
      this.parameterTypes = parameterTypes;
      return this;
    }

    /**
     * see {@linkplain #invokeX(Object...)}
     *
     * @param params method's params
     * @return method result
     * @throws Exception {@link NoSuchFieldException} or {@link IllegalAccessException}
     */
    public Object invoke(Object... params) throws Exception {
      final Class<?> targetClass = getTargetClass();

      Method targetMethod = targetClass.getDeclaredMethod(methodName, parameterTypes);
      targetMethod.setAccessible(true);

      return target != null ?
          targetMethod.invoke(target, params) :
          targetMethod.invoke(null, params);
    }

    /**
     * Call the target method.
     *
     * @param params method's params
     * @return method result
     * @throws NoSuchMethodException NoSuchMethodException
     * @throws NoSuchMethodException InvocationTargetException
     * @throws NoSuchMethodException IllegalAccessException
     */
    public Object invokeX(Object... params) throws NoSuchMethodException, InvocationTargetException,
        IllegalAccessException {
      final Class<?> targetClass = getTargetClass();

      Method targetMethod = targetClass.getDeclaredMethod(methodName, parameterTypes);
      targetMethod.setAccessible(true);

      return target != null ?
          targetMethod.invoke(target, params) :
          targetMethod.invoke(null, params);
    }
  }

  public static final class FieldInfo {
    private String name;
    private Type type;
    private Object value;
    private Field src;

    public void set(Object value) {
      // todo set
    }
  }

  public static final class MethodInfo{
    private String name;
    private Type resultType;
    private Type[] parameterTypes;

    private Method src;

    public Object invoke(Object parameters) {
      // todo invoke
      return null;
    }
  }

  public class Snapshot extends BaseReflect {

    public String capture() {
      return "";
    }


  }
}
