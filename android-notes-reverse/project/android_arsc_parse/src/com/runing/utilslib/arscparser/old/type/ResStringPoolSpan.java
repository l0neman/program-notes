package com.runing.utilslib.arscparser.old.type;

/*
struct ResStringPool_span
{
    enum {
        END = 0xFFFFFFFF
    };

    // This is the name of the span -- that is, the name of the XML
    // tag that defined it.  The special value END (0xFFFFFFFF) indicates
    // the end of an array of spans.
    ResStringPool_ref name;

    // The range of characters in the string that this span applies to.
    uint32_t firstChar, lastChar;
};
 */

/**
 * 字符串样式块中的字符串样式信息。
 */
public class ResStringPoolSpan {

  public static final int BYTES = Integer.BYTES * 2;

  public static final int END = 0xFFFFFFFF;

  /** 本样式在字符串内容块中的字节位置 */
  public ResStringPoolRef name;
  /** 包含样式的字符串的第一个字符索引 */
  public int firstChar;
  /** 包含样式的字符串的最后一个字符索引 */
  public int lastChar;

  public ResStringPoolSpan(ResStringPoolRef name, int firstChar, int lastChar) {
    this.name = name;
    this.firstChar = firstChar;
    this.lastChar = lastChar;
  }

  @Override
  public String toString() {
    return Config.BEAUTIFUL ?
        "{" +
            "name=" + name +
            ", firstChar=" + firstChar +
            ", lastChar=" + lastChar +
            '}'
        :
        "ResStringPoolSpan{" +
            "name=" + name +
            ", firstChar=" + firstChar +
            ", lastChar=" + lastChar +
            '}';
  }
}
