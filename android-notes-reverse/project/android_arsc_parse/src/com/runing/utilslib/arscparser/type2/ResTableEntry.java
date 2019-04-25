package com.runing.utilslib.arscparser.type2;

/*
struct ResTable_entry
{
    // Number of bytes in this structure.
    uint16_t size;

    enum {
        // If set, this is a complex entry, holding a set of name/value
        // mappings.  It is followed by an array of ResTable_map structures.
        FLAG_COMPLEX = 0x0001,
        // If set, this resource has been declared public, so libraries
        // are allowed to reference it.
        FLAG_PUBLIC = 0x0002,
        // If set, this is a weak resource and may be overriden by strong
        // resources of the same name/type. This is only useful during
        // linking with other resource tables.
        FLAG_WEAK = 0x0004
    };
    uint16_t flags;

    // Reference into ResTable_package::keyStrings identifying this entry.
    struct ResStringPool_ref key;
};
 */

import com.runing.utilslib.arscparser.util.Bytes;

/**
 * 资源项。
 */
public class ResTableEntry {
  public static final int BYTES = Short.BYTES * 2 + ResStringPoolRef.BYTES;

  public static final int FLAG_COMPLEX = 0x0001;
  public static final int FLAG_PUBLIC = 0x0002;

  /** {@link #BYTES} 资源项头部大小 */
  public short size;
  /**
   * 资源项标志位。如果是一个 Bag 资源项，那么 FLAG_COMPLEX 位就等于 1，并且在 ResTable_entry 后面跟有一个 ResTable_map 数组，
   * 否则的话，在 ResTable_entry {@link ResTableEntry} 后面跟的是一个 Res_value。如果是一个可以被引用的资源项，那么 FLAG_PUBLIC 位就等于1
   */
  public short flags;
  /**
   * 资源项名称在资源项名称字符串资源池的索引。
   */
  public ResStringPoolRef key;

  public ResTableEntry(short size, short flags, ResStringPoolRef key) {
    this.size = size;
    this.flags = flags;
    this.key = key;
  }

  public static ResTableEntry valueOfBytes(byte[] arsc, int tableEntryIndex) {
    int index = tableEntryIndex;
    /*
    return new ResTableEntry(
        Bytes.getShort(arsc, index),
        Bytes.getShort(arsc, index += Short.BYTES),
        new ResStringPoolRef(Bytes.getInt(arsc, index + Short.BYTES))
    );
    // */
    return null;
  }

  @Override
  public String toString() {
    return Config.BEAUTIFUL ?
        "{" +
            "size=" + size +
            ", flags=" + flags +
            ", key=" + key +
            '}'
        :
        "ResTableEntry{" +
            "size=" + size +
            ", flags=" + flags +
            ", key=" + key +
            '}';
  }
}
