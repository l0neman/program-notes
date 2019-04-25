package com.runing.utilslib.arscparser.util.objectio;

import java.io.Closeable;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
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

  private String file;
  private ByteOrder byteOrder;
  private final FileChannel inputChannel;
//  private final FileChannel outputChannel;

  public StructIO(String file) throws IOException {
    this.file = file;
    inputChannel = new FileInputStream(file).getChannel();
  }

  public StructIO(String file, boolean bigEndian) throws IOException {
    this.file = file;
    this.byteOrder = bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
    inputChannel = new FileInputStream(file).getChannel();
  }

  private static final class FieldInfo {
    final Field field;
    final boolean isArray;
    final int arraySize;

    public FieldInfo(Field field, boolean isArray, int arraySize) {
      this.field = field;
      this.isArray = isArray;
      this.arraySize = arraySize;
    }
  }

  private static final int MAX_CACHE_CLASS = 20;
  /* 平铺 class 成员，优先包展开类成员。 */
  private Map<Class<?>, List<FieldInfo>> classFlatFieldListMap = new LinkedHashMap<Class<?>, List<FieldInfo>>(
      (int) (Math.ceil(MAX_CACHE_CLASS / 0.75F)) + 1, 0.75F, true
  ) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<Class<?>, List<FieldInfo>> eldest) {
      return size() > MAX_CACHE_CLASS;
    }
  };
  private Map<Class<?>, Integer> classSizeCache = new HashMap<>();

  /*
    构建类内部成员列表。
   */
  private List<FieldInfo> buildClassFieldList(Class<?> target) {
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
  private void buildClassFieldList(List<FieldInfo> fieldInfoList, Class<?> target) throws Exception {
    final Field[] fields = target.getDeclaredFields();
    if (fields.length == 0) { return; }

    Class<?> parent = target.getSuperclass();
    while (parent != Object.class) {
      buildClassFieldList(fieldInfoList, target);
      parent = target.getSuperclass();
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
  private List<FieldInfo> getClassFieldList(Class<?> clazz) {
    final List<FieldInfo> fieldInfos = classFlatFieldListMap.get(clazz);
    if (fieldInfos == null) {
      final List<FieldInfo> fieldInfoList = buildClassFieldList(clazz);
      classFlatFieldListMap.put(clazz, fieldInfoList);
      return fieldInfoList;
    }

    return fieldInfos;
  }

  /**
   * 计算类型大小。
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
  public int sizeOf(Class<?> target) {
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

    final Integer cacheSize = classSizeCache.get(target);
    if (cacheSize != null) { return cacheSize; }

    List<FieldInfo> fieldInfoList = getClassFieldList(target);
    int size = 0;
    for (FieldInfo fieldInfo : fieldInfoList) {
      final Class<?> fieldType = fieldInfo.field.getType();
      checkSupportType(fieldType);

      if (fieldInfo.isArray) {
        final Class<?> componentType = fieldInfo.field.getType().getComponentType();
        size += sizeOf(componentType) * fieldInfo.arraySize;
        continue;
      }

      size += sizeOf(fieldType);
    }

    classSizeCache.put(target, size);
    return size;
  }

  private <T> void toByteBuffer(T object, ByteBuffer byteBuffer) throws IOException {

  }

  private <T> void fromByteBuffer(T object, Class<?> target, ByteBuffer byteBuffer) throws Exception {
    List<FieldInfo> fieldInfoList = getClassFieldList(target);
    for (FieldInfo fieldInfo : fieldInfoList) {
      final Class<?> fieldType = fieldInfo.field.getType();

      checkSupportType(fieldType);

      if (fieldType == byte.class || fieldType == Byte.class) {
        fieldInfo.field.set(object, byteBuffer.get());
        continue;
      }

      if (fieldType == char.class || fieldType == Character.class) {
        fieldInfo.field.set(object, byteBuffer.getChar());
        continue;
      }

      if (fieldType == short.class || fieldType == Short.class) {
        fieldInfo.field.set(object, byteBuffer.getShort());
        continue;
      }

      if (fieldType == int.class || fieldType == Integer.class) {
        fieldInfo.field.set(object, byteBuffer.getInt());
        continue;
      }

      if (fieldType == long.class || fieldType == Long.class) {
        fieldInfo.field.set(object, byteBuffer.getLong());
        continue;
      }

      if (fieldInfo.isArray) {
        final Class<?> componentType = fieldInfo.field.getType().getComponentType();

        Object arrayField = Array.newInstance(componentType, fieldInfo.arraySize);
        for (int i = 0; i < fieldInfo.arraySize; i++) {
          final Object item = componentType.newInstance();
          fromByteBuffer(item, componentType, byteBuffer);
          Array.set(arrayField, i, item);
        }

        fieldInfo.field.set(object, arrayField);
        continue;
      }

      Object field = fieldInfo.field.getType().newInstance();
      fromByteBuffer(field, fieldType, byteBuffer);
      fieldInfo.field.set(object, field);
    }
  }

  public <T extends Struct> void write(T target, int offset) throws IOException {
  }

  public <T extends Struct> T read(Class<T> target, int offset) throws IOException {
    final int size;
    try {
      size = sizeOf(target);
    } catch (Exception e) {
      throw new IllegalArgumentException("object size is null.");
    }

    final ByteBuffer byteBuffer = ByteBuffer.allocate(size);
    byteBuffer.order(byteOrder);
    inputChannel.read(byteBuffer, offset);
    byteBuffer.flip();

    T result;
    try {
      result = target.newInstance();
    } catch (Exception e) {
      throw new IllegalArgumentException("Must implement the default constructor.", e);
    }

    try {
      fromByteBuffer(result, target, byteBuffer);
      return result;
    } catch (Exception e) {
      throw new IllegalArgumentException("All members must implement the default constructor.", e);
    }
  }

  public byte readByte(int offset) throws IOException {
    final ByteBuffer byteBuffer = ByteBuffer.allocate(Byte.BYTES);
    byteBuffer.order(byteOrder);
    inputChannel.read(byteBuffer, offset);
    byteBuffer.flip();

    return byteBuffer.get();
  }

  public byte[] readBytes(int offset, int size) throws IOException {
    final ByteBuffer byteBuffer = ByteBuffer.allocate(size);
    byteBuffer.order(byteOrder);
    inputChannel.read(byteBuffer, offset);
    byteBuffer.flip();

    byte[] bytes = new byte[size];
    byteBuffer.get(bytes);
    return bytes;
  }

  public char readChar(int offset) throws IOException {
    final ByteBuffer byteBuffer = ByteBuffer.allocate(Character.BYTES);
    byteBuffer.order(byteOrder);
    inputChannel.read(byteBuffer, offset);
    byteBuffer.flip();

    return byteBuffer.getChar();
  }

  public short readShort(int offset) throws IOException {
    final ByteBuffer byteBuffer = ByteBuffer.allocate(Short.BYTES);
    byteBuffer.order(byteOrder);
    inputChannel.read(byteBuffer, offset);
    byteBuffer.flip();

    return byteBuffer.getShort();
  }

  public int readInt(int offset) throws IOException {
    final ByteBuffer byteBuffer = ByteBuffer.allocate(Integer.BYTES);
    byteBuffer.order(byteOrder);
    inputChannel.read(byteBuffer, offset);
    byteBuffer.flip();

    return byteBuffer.getInt();
  }

  public long readLong(int offset) throws IOException {
    final ByteBuffer byteBuffer = ByteBuffer.allocate(Long.BYTES);
    byteBuffer.order(byteOrder);
    inputChannel.read(byteBuffer, offset);
    byteBuffer.flip();

    return byteBuffer.getLong();
  }


  @Override
  public void close() throws IOException {
    if (inputChannel != null) {
      inputChannel.close();
    }
  }

  public boolean isEof(int offset) {
    return offset >= file.length();
  }

  private static void checkSupportType(Type type) {
    if (type == String.class || type == float.class || type == double.class ||
        type == boolean.class || type == Float.class || type == Double.class ||
        type == Boolean.class) {
      throw new IllegalArgumentException("not support filed type: " + type + " .");
    }
  }

  /*
  private static void closeQuietly(Closeable closeable) {
    if (closeable != null) {
      try {
        closeable.close();
      } catch (IOException ignore) {
      } catch (RuntimeException e) {
        throw new RuntimeException(e);
      }
    }
  }
  // */
}
