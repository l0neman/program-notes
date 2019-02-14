package com.runing.utilslib.arscparser.type;

import com.runing.utilslib.arscparser.util.Bytes;

/*
struct ResTable_package
{
    struct ResChunk_header header;

    // If this is a base package, its ID.  Package IDs start
    // at 1 (corresponding to the value of the package bits in a
    // resource identifier).  0 means this is not a base package.
    uint32_t id;

    // Actual name of this package, \0-terminated.
    char16_t name[128];

    // Offset to a ResStringPool_header defining the resource
    // type symbol table.  If zero, this package is inheriting from
    // another base package (overriding specific values in it).
    uint32_t typeStrings;

    // Last index into typeStrings that is for public use by others.
    uint32_t lastPublicType;

    // Offset to a ResStringPool_header defining the resource
    // key symbol table.  If zero, this package is inheriting from
    // another base package (overriding specific values in it).
    uint32_t keyStrings;

    // Last index into keyStrings that is for public use by others.
    uint32_t lastPublicKey;
};
 */

/**
 * Package 资源项元信息头部。
 */
public class ResTablePackage {
  public static final int BYTES = ResChunkHeader.BYTES + Integer.BYTES * 5 + 128 * Character.BYTES;

  /**
   * {@link ResChunkHeader#type} = {@link ResourceTypes#RES_TABLE_PACKAGE_TYPE}
   * <p>
   * {@link ResChunkHeader#headerSize} = {@link #BYTES} 表示头部大小。
   * <p>
   * {@link ResChunkHeader#size} = {@link #BYTES} + 类型字符串资源池大小 + 类型规范名称字符串池大小 +
   * 类型规范数据块大小 + 数据项信息数据块大小。
   */
  public ResChunkHeader header;
  /** Package ID */
  public int id;
  /** Package Name */
  public char[] name;
  /**
   * 类型字符串资源池相对头部的偏移位置。
   */
  public int typeStrings;
  /**
   * 最后一个导出的 public 类型字符串在类型字符串资源池中的索引，目前这个值设置为类型字符串资源池的大小。
   */
  public int lastPublicType;
  /**
   * 资源项名称字符串相对头部的偏移位置。
   */
  public int keyStrings;
  /**
   * 最后一个导出的 public 资源项名称字符串在资源项名称字符串资源池中的索引，目前这个值设置为资源项名称字符串资源池的大小。
   */
  public int lastPublicKey;

  public ResTablePackage(ResChunkHeader header, int id, char[] name, int typeStrings, int lastPublicType,
                         int keyStrings, int lastPublicKey) {
    this.header = header;
    this.id = id;
    this.name = name;
    this.typeStrings = typeStrings;
    this.lastPublicType = lastPublicType;
    this.keyStrings = keyStrings;
    this.lastPublicKey = lastPublicKey;
  }

  public static ResTablePackage valueOfBytes(byte[] arsc, ResChunkHeader header, int tablePackageIndex) {
    int index = tablePackageIndex;
    return new ResTablePackage(
        header,
        Bytes.getInt(arsc, index += ResChunkHeader.BYTES),
        Bytes.toChars(Bytes.copy(arsc, index += Integer.BYTES, 128 * Character.BYTES)),
        Bytes.getInt(arsc, index += 128 * Character.BYTES),
        Bytes.getInt(arsc, index += Integer.BYTES),
        Bytes.getInt(arsc, index += Integer.BYTES),
        Bytes.getInt(arsc, index + Integer.BYTES)
    );
  }

  @Override
  public String toString() {
    return Config.BEAUTIFUL ?
        "{" +
            "header=" + header +
            ", id=" + Bytes.toHex(Bytes.fromInt(id)) +
            ", name=" + new String(name) +
            ", typeStrings=" + typeStrings +
            ", lastPublicType=" + lastPublicType +
            ", keyStrings=" + keyStrings +
            ", lastPublicKey=" + lastPublicKey +
            '}'
        :
        "ResTablePackage{" +
            "header=" + header +
            ", id=" + id +
            ", name=" + new String(name) +
            ", typeStrings=" + typeStrings +
            ", lastPublicType=" + lastPublicType +
            ", keyStrings=" + keyStrings +
            ", lastPublicKey=" + lastPublicKey +
            '}';
  }
}
