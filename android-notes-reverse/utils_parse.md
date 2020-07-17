#  结构解析工具类

## ObjectIO

工欲善其事，必先利其器。对于解析任何一种数据格式来说，它的内部必然会包含各种子数据结构，如果对于每种子类型都逐个字节解析，不仅麻烦、难以理解和阅读、且没有任何复用性，所以需要一种通用的工具。

这里参考 C 语言中的 `read` 函数，如果将结构体指针传入，则系统会自动将对应字节字节映射到结构体的成员中，既方便又通用，那么使用 Java 中模仿它的 API 写一个工具 `ObjectInput`。

首先抽象了两个接口，`Struct` 和 `Union`，作为标记型接口，为了符合解析规范，实现 `Struct` 接口的类型可被解析，实现 `Union` 接口的类型成员将使用共用字节的解析方式。

然后定义了工具类：`ObjectInput`，源码 [ObjectInput.java](./src/com/runing/utilslib/arscparser/util/objectio/ObjectInput.java)

使用方法为：

1. 首先定义需要被解析的目标类型，例如 `ResChunkHeader.java`：

```java
/**
 * 资源表 Chunk 基础结构。
 */
public class ResChunkHeader implements Struct {

  /** Chunk 类型 */
  @FieldOrder(n = 0) public short type;
  /** Chunk 头部大小 */
  @FieldOrder(n = 1) public short headerSize;
  /** Chunk 大小 */
  @FieldOrder(n = 2) public int size;
}
```

由于需要按照成员顺序解析，而通过反射方法 `getDeclaredFields` 方法获取的成员列表顺序可能发生变化，所以这里采用 `FieldOrder` 注解的方式，保持成员定义的顺序。



2. 调用解析方法。

```java
...
private static void closeQuietly(Closeable closeable) {
  if (closeable != null) {
    try {
      closeable.close();
    } catch (IOException ignore) {
    } catch (RuntimeException e) {
      e.printStackTrace();
    }
  }
}

public void parse(String file) {
  ObjectInput objectInput = null;
  try {
    // 指定文件格式大小端。
    boolean bigEndian = false;

    objectInput = new ObjectInput(file, bigEndian);
    // 内部会通过反射将字节映射到 ResChunkHeader 对象对应成员中。
    ResChunkHeader cheunkHeader = objectInput.read(ResChunkHeader.class, 0);
    ...
  } catch (Exception e) {
    e.printStackTrace();

  } finally {
    closeQuietly(objectIO);
  }
}
```