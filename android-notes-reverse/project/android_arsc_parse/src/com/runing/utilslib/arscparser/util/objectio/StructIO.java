package com.runing.utilslib.arscparser.util.objectio;

import com.runing.utilslib.arscparser.type2.ResTableMapEntry;

import java.io.Closeable;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.*;

/**
 * 对象读取，便于解析各种文件格式。
 * <p>
 * 借鉴 C 语言中的 read/write 读取。
 */
public class StructIO implements Closeable {

  private ByteOrder byteOrder;
  private final FileChannel inputChannel;
  //  private final FileChannel outputChannel;
  private final long size;

  public StructIO(String file) throws IOException {
    inputChannel = new FileInputStream(file).getChannel();
    size = inputChannel.size();
  }

  public StructIO(String file, boolean bigEndian) throws IOException {
    this(file);
    this.byteOrder = bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
  }

  private static final class FieldInfo {
    final Field field;
    final boolean isArray;
    final int arrayLength;

    public FieldInfo(Field field, boolean isArray, int arrayLength) {
      this.field = field;
      this.isArray = isArray;
      this.arrayLength = arrayLength;
    }
  }

  private static final int MAX_CACHE_CLASS = 20;
  /* 平铺 class 成员，优先包展开类成员。 */
  private static SimpleLru<Class<?>, List<FieldInfo>> sClassFlatFieldListMap = new SimpleLru<>(MAX_CACHE_CLASS);
  private static SimpleLru<Class<?>, Integer> sClassSizeCache = new SimpleLru<>(20);

  /*
    构建类内部成员列表。
   */
  private static List<FieldInfo> buildClassFieldList(Class<?> target) {
    List<FieldInfo> fieldInfoList = new ArrayList<>();
    try {
      buildClassFieldList(fieldInfoList, target);
    } catch (Exception e) {
      throw new IllegalArgumentException("class not meet specifications.", e);
    }
    return fieldInfoList;
  }

  /*
    构建类内部成员列表。（递归函数）
   */
  private static void buildClassFieldList(List<FieldInfo> fieldInfoList, Class<?> target) throws Exception {
    final Field[] fields = target.getDeclaredFields();
    if (fields.length == 0) { return; }

    Class<?> parent = target.getSuperclass();
    while (parent != Object.class) {
      buildClassFieldList(fieldInfoList, parent);
      parent = parent.getSuperclass();
    }

    for (Field field : fields) {
      final Class<?> fieldType = field.getType();

      if (Modifier.isStatic(field.getModifiers())) {
        continue;
      }

      if (!field.isAccessible()) {
        field.setAccessible(true);
      }

      if (!fieldType.isArray()) {
        fieldInfoList.add(new FieldInfo(field, false, 0));
        continue;
      }

      //noinspection Duplicates
      final Object helper = target.newInstance();
      final int length = Array.getLength(field.get(helper));
      if (length == 0) {
        throw new IllegalArgumentException("array " + fieldType.getComponentType() + "[] length is 0, not allowed.");
      }

      fieldInfoList.add(new FieldInfo(field, true, length));
    }
  }

  /*
    获得类的成员列表，LRU 缓存历史类型成员列表。
   */
  private static List<FieldInfo> getClassFieldList(Class<?> clazz) {
    final List<FieldInfo> fieldInfos = sClassFlatFieldListMap.get(clazz);
    if (fieldInfos == null) {
      final List<FieldInfo> fieldInfoList = buildClassFieldList(clazz);
      sClassFlatFieldListMap.put(clazz, fieldInfoList);
      return fieldInfoList;
    }

    return fieldInfos;
  }

  /**
   * 计算类型大小，LRU 缓存历史记录。
   * <p>
   * 1. 不支持直接传数组类型，请用计算数组组件类型与长度相乘。
   * <p>
   * 2. 不计算静态成员大小。
   * <p>
   * 3. 类内部的数据成员需要实例化，否则无法获取数组长度，从而无法计算类大小。
   *
   * @param target 目标类型
   * @return class field total size.
   */
  public static int sizeOf(Class<?> target) {
    if (target.isArray()) {
      throw new IllegalArgumentException("not support array type: " + target);
    }

    checkSupportType(target);

    if (target == byte.class || target == Byte.class) {
      return Byte.BYTES;
    }

    if (target == char.class || target == Character.class) {
      return Character.BYTES;
    }

    if (target == short.class || target == Short.class) {
      return Short.BYTES;
    }

    if (target == int.class || target == Integer.class) {
      return Integer.BYTES;
    }

    if (target == long.class || target == Long.class) {
      return Long.BYTES;
    }

    if (isUnion(target)) {
      List<FieldInfo> fieldInfoList = getClassFieldList(target);

      // 取出 union 类型。
      int[] typeSize = new int[fieldInfoList.size()];
      for (int i = 0; i < typeSize.length; i++) {
        final FieldInfo fieldInfo = fieldInfoList.get(i);
        final Class<?> fieldType = fieldInfo.field.getType();

        // 处理数组成员类型。
        if (fieldInfo.isArray) {
          typeSize[i] = sizeOf(fieldType.getComponentType()) * fieldInfo.arrayLength;
          continue;
        }

        typeSize[i] = sizeOf(fieldType);
      }

      // 得到字节占用（最大成员的大小）。
      Arrays.sort(typeSize);
      return typeSize[typeSize.length - 1];
    }

    if (isStruct(target)) {
      final Integer cacheSize = sClassSizeCache.get(target);
      if (cacheSize != null) { return cacheSize; }

      List<FieldInfo> fieldInfoList = getClassFieldList(target);
      int size = 0;
      for (FieldInfo fieldInfo : fieldInfoList) {
        final Class<?> fieldType = fieldInfo.field.getType();

        // 处理数组成员类型。
        if (fieldInfo.isArray) {
          final Class<?> componentType = fieldInfo.field.getType().getComponentType();
          size += sizeOf(componentType) * fieldInfo.arrayLength;
          continue;
        }

        size += sizeOf(fieldType);
      }

      sClassSizeCache.put(target, size);
      return size;
    }

    throw new IllegalArgumentException("Not a struct or union type: " + target);
  }

  private <T> void toByteBuffer(T object, ByteBuffer byteBuffer) throws IOException {

  }

  @SuppressWarnings("unchecked")
  private <T> T fromByteBuffer(Class<T> target, ByteBuffer byteBuffer) throws Exception {
    // 处理基本类型。
    if (target == byte.class || target == Byte.class) {
      return (T) Byte.valueOf(byteBuffer.get());
    }

    if (target == char.class || target == Character.class) {
      return (T) Character.valueOf(byteBuffer.getChar());
    }

    if (target == short.class || target == Short.class) {
      return (T) Short.valueOf(byteBuffer.getShort());
    }

    if (target == int.class || target == Integer.class) {
      return (T) Integer.valueOf(byteBuffer.getInt());
    }

    if (target == long.class || target == Long.class) {
      return (T) Long.valueOf(byteBuffer.getLong());
    }

    // 处理 Struct 和 Union 类型。
    final boolean isUnion = isUnion(target);
    if (isStruct(target) || isUnion) {
      T object;
      try {
        object = target.newInstance();
      } catch (Exception e) {
        throw new IllegalArgumentException("Must implement the default constructor.", e);
      }

      List<FieldInfo> fieldInfoList = getClassFieldList(target);
      for (FieldInfo fieldInfo : fieldInfoList) {
        final Class<?> fieldType = fieldInfo.field.getType();

        // 注意，Union 由于成员共用内存，所以需要设置重读。
        if (isUnion) {
          byteBuffer.rewind();
        }

        // 处理宿主成员类型。
        if (fieldInfo.isArray) {
          final Class<?> componentType = fieldInfo.field.getType().getComponentType();

          Object arrayField = Array.newInstance(componentType, fieldInfo.arrayLength);
          for (int i = 0; i < fieldInfo.arrayLength; i++) {
            Object item = fromByteBuffer(componentType, byteBuffer);
            Array.set(arrayField, i, item);
          }

          fieldInfo.field.set(object, arrayField);
          continue;
        }

        checkSupportType(fieldType);
        Object field = fromByteBuffer(fieldType, byteBuffer);
        fieldInfo.field.set(object, field);
      }
      return object;
    }

    throw new IllegalArgumentException("Not a struct or union type: " + target);

  }

  // todo write.
  public <T extends Struct> void write(T target, long offset) throws IOException {
  }

  public <T extends Struct> T read(Class<T> target, long offset) throws IOException {
    checkSupportType(target);

    final int size;
    try {
      size = sizeOf(target);
    } catch (Exception e) {
      throw new IllegalArgumentException("object size is null.", e);
    }

    final ByteBuffer byteBuffer = ByteBuffer.allocate(size);
    byteBuffer.order(byteOrder);
    inputChannel.read(byteBuffer, offset);
    byteBuffer.flip();

    try {
      return fromByteBuffer(target, byteBuffer);
    } catch (Exception e) {
      throw new IOException("read error", e);
    }
  }

  public byte readByte(long offset) throws IOException {
    final ByteBuffer byteBuffer = ByteBuffer.allocate(Byte.BYTES);
    byteBuffer.order(byteOrder);
    inputChannel.read(byteBuffer, offset);
    byteBuffer.flip();

    return byteBuffer.get();
  }

  public byte[] readBytes(long offset, int size) throws IOException {
    final ByteBuffer byteBuffer = ByteBuffer.allocate(size);
    byteBuffer.order(byteOrder);
    inputChannel.read(byteBuffer, offset);
    byteBuffer.flip();

    byte[] bytes = new byte[size];
    byteBuffer.get(bytes);
    return bytes;
  }

  public char readChar(long offset) throws IOException {
    final ByteBuffer byteBuffer = ByteBuffer.allocate(Character.BYTES);
    byteBuffer.order(byteOrder);
    inputChannel.read(byteBuffer, offset);
    byteBuffer.flip();

    return byteBuffer.getChar();
  }

  public short readShort(long offset) throws IOException {
    final ByteBuffer byteBuffer = ByteBuffer.allocate(Short.BYTES);
    byteBuffer.order(byteOrder);
    inputChannel.read(byteBuffer, offset);
    byteBuffer.flip();

    return byteBuffer.getShort();
  }

  public int readInt(long offset) throws IOException {
    final ByteBuffer byteBuffer = ByteBuffer.allocate(Integer.BYTES);
    byteBuffer.order(byteOrder);
    inputChannel.read(byteBuffer, offset);
    byteBuffer.flip();

    return byteBuffer.getInt();
  }

  public long readLong(long offset) throws IOException {
    final ByteBuffer byteBuffer = ByteBuffer.allocate(Long.BYTES);
    byteBuffer.order(byteOrder);
    inputChannel.read(byteBuffer, offset);
    byteBuffer.flip();

    return byteBuffer.getLong();
  }

  @Override
  public void close() throws IOException {
    sClassSizeCache.clear();
    sClassFlatFieldListMap.clear();

    if (inputChannel != null && inputChannel.isOpen()) {
      inputChannel.close();
    }
  }

  public boolean isEof(long offset) {
    return offset >= size;
  }

  public long size() { return size; }

  private static boolean isStruct(Class<?> type) {
    return Struct.class.isAssignableFrom(type);
  }

  private static boolean isUnion(Class<?> type) {
    return Union.class.isAssignableFrom(type);
  }

  private static void checkSupportType(Class<?> type) {
    if (!type.isPrimitive() && !isStruct(type) && !isUnion(type)) {
      throw new IllegalArgumentException("Not a struct or union type: " + type);
    }

    if (type == double.class || type == Double.class ||
        type == float.class || type == Float.class ||
        type == boolean.class || type == Boolean.class) {
      throw new IllegalArgumentException("Not a struct or union type: " + type);
    }
  }

  private static final class SimpleLru<K, V> {
    private final LinkedHashMap<K, V> lruMap;

    SimpleLru(int max) {
      this.lruMap = new LinkedHashMap<K, V>(
          (int) (Math.ceil(max / 0.75F)) + 1, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry eldest) {
          return size() > max;
        }
      };
    }

    V get(K key) {
      return lruMap.get(key);
    }

    void put(K key, V value) {
      lruMap.put(key, value);
    }

    void clear() {
      lruMap.clear();
    }
  }
}
