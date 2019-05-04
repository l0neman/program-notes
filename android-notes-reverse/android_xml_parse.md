# Android 二进制 Xml 文件解析



## 前言

android sdk 在编译 Android 工程时，将会把诸如资源文件和清单文件之类的相关 xml 文件编译为特定的二进制格式，目的是为了压缩其容量以及优化其在运行时的解析效率。

将 Xml 文件编译为二进制的 Xml 文件是 android 编译资源时的一个子步骤，android 在完整的资源编译过程结束后将会生成一个 `resources.arsc` 文件，它是一个资源文件表，应用在运行时会将它映射在内存中，为了资源的查询和引用。编译 Xml 文件为生成 arsc 文件的一个子步骤，如果 Xml 文件中引用了资源，例如字符串资源，那么 Xml 文件中引用字符串的位置将会包含一个全局字串池的索引，通过索引在 arsc 文件中的全局字符串池中即可查询到引用的具体字符串。

有关 arsc 文件的结构和解析方法可参考：[Android arsc 文件解析](./android_arsc_parse.md)。

## Xml 文件结构

编译后的二进制文件结构如下图：

![](./image/android_xml_parse/android_xml_struct.png)

和 arsc 文件的构成方式类似，二进制 Xml 文件的结构也是由若干 Chunk 结构组成，且它们在 android 源码中的 `ResourceTypes.h` 头文件中均有对应结构的定义。下面分别说明二进制 Xml 中 4 部分 Chunk 的内容。

### Xml Chunk Header

Xml Chunk Header 描述了 Xml 文件的基本信息，它在 `ResourceTypes.h` 中的结构为 `struct ResXMLTree_header`，这里使用 java 描述为：

```java
public class ResXMLTreeHeader implements Struct {
  /**
   * {@link ResChunkHeader#type} = {@link ResourceTypes#RES_XML_TYPE}
   * <p>
   * {@link ResChunkHeader#headerSize} = sizeOf(ResXMLTreeHeader.class) 表示头部大小。
   * <p>
   * {@link ResChunkHeader#size} = 整个二进制 Xml 文件的大小，包括头部的大小。
   */
  public ResChunkHeader header;
}
```

其中 `ResChunkHeader` 为资源 Chunk 的基础描述头部结构，对应的定义为 `struct ResChunk_header`，java 表示为：

```java
public class ResChunkHeader implements Struct {
  /** Chunk 类型 */
  public short type;
  /** Chunk 头部大小 */
  public short headerSize;
  /** Chunk 大小 */
  public int size;
}
```

在 Xml 文件中，此时的 `type` 值为 `0x003`  等于 `ResourceTypes.h` 中定义的 Xml 类型的 type 值：

```java
// 这里使用 java 描述。
public class ResourceTypes {
	public static final short RES_XML_TYPE = 0x0003;
    ...
}
```

`headerSize` 为 `ResXMLTreeHeader` 头部结构自身的大小，即 `ResChunkHeader` 的大小，为 8 字节。

`size` 为当前 Chunk 大小，此时为 Xml 文件的大小，包括头结构的大小。

### String Pool Chunk

String Pool Chunk 为字符串池结构，它包含了此 Xml 文件中出现的所有字符串内容。它的结构和 arsc 文件中的全局字符串结构完全一致，下面是引用上篇解析 arsc 文件中的字符串池的描述：

字符串池包括如下几个部分：

1. ResStringPool_header 字符串池头部，包含字符串池的信息，大小，数量，数组偏移等。
2. String Offset Array 字符串在字符串内容中的字节位置数组，32 位 int 类型。
3. Style Offset Array 字符串样式在字符串样式中的字节位置数组，32 位 int 类型。
4. String Content 字符串内容块。
5. Style Content 字符串样式块。

字符串池的头部使用 `struct ResStringPool_header` 数据结构描述，java 表示为：

```java
/**
 * 字符串池头部。
 */
public class ResStringPoolHeader implements Struct {
  public static final int SORTED_FLAG = 1;
  public static final int UTF8_FLAG = 1 << 8;

  /**
   * {@link ResChunkHeader#type} = {@link ResourceTypes#RES_STRING_POOL_TYPE}
   * <p>
   * {@link ResChunkHeader#headerSize} = sizeOf(ResStringPoolHeader.class) 表示头部大小。
   * <p>
   * {@link ResChunkHeader#size} = 整个字符串 Chunk 的大小，包括 headerSize 的大小。
   */
  public ResChunkHeader header;
  /** 字符串的数量 */
  public int stringCount;
  /** 字符串样式的数量 */
  public int styleCount;
  /** 0, SORTED_FLAG, UTF8_FLAG 它们的组合值 */
  public int flags;
  /** 字符串内容块相对于其头部的距离 */
  public int stringStart;
  /** 字符串样式块相对于其头部的距离 */
  public int styleStart;
}
```

其中 `flags` 包含 `UTF8_FLAG` 表示字符串格式为 utf8， `SORTED_FLAG` 表示已排序。

字符串的偏移数组使用 `struct ResStringPool_ref` 数据结构描述，java 表示为：

```java
/**
 * 字符串在字符串内容块中的字节偏移。
 */
public class ResStringPoolRef implements Struct{
  /** 字符串在字符串池中的索引 */
  public int index;
}
```

字符串样式则使用 `struct ResStringPool_span` 数据结构描述，java 表示为：

```java
/**
 * 字符串样式块中的字符串样式信息。
 */
public class ResStringPoolSpan implements Struct{
  public static final int END = 0xFFFFFFFF;

  /** 本样式在字符串内容块中的字节位置 */
  public ResStringPoolRef name;
  /** 包含样式的字符串的第一个字符索引 */
  public int firstChar;
  /** 包含样式的字符串的最后一个字符索引 */
  public int lastChar;
}
```

其中 `name` 表示字符串样式本身字符串的索引，比如 `<b>` 样式本身的字符串为 b，即为 b 在字符串池中的索引。 

`firstChar` 和 `lastChar` 则为具有样式的字符串的中字符串首位的索引，例如 `he<b>ll</b>o`，则为 2 和 3。

字符串样式块和字符串内容块是一一对应的，就是说第一个字符串的样式对应第一个字符串样式块中的样式，如果对应的字符串中有不具有样式的字符串，则对应的 `ResStringPool_span` 的 `name` 为 `0xFFFFFFFF`，起占位的作用。

### Resource Ids Chunk

### Xml Content Chunk

## Xml 文件解析

