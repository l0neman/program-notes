package com.runing.utilslib.arscparser.type;

/*
struct ResStringPool_ref
{
    // Index into the string pool table (uint32_t-offset from the indices
    // immediately after ResStringPool_header) at which to find the location
    // of the string data in the pool.
    uint32_t index;
};
 */

/**
 * 字符串在字符串内容块中的字节偏移。
 */
public class ResStringPoolRef {

  public static final int BYTES = Integer.BYTES;

  public int index;

  public ResStringPoolRef(int index) {
    this.index = index;
  }

  @Override
  public String toString() {
    return Config.BEAUTIFUL ? '{' +
        "index=" + index +
        '}'
        :
        "ResStringPoolRef{" +
            "index=" + index +
            '}';
  }
}
