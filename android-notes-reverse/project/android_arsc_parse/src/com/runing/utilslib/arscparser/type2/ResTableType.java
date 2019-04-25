package com.runing.utilslib.arscparser.type2;

/*
struct ResTable_type
{
    struct ResChunk_header header;

    enum {
        NO_ENTRY = 0xFFFFFFFF
    };

    // The type identifier this chunk is holding.  Type IDs start
    // at 1 (corresponding to the value of the type bits in a
    // resource identifier).  0 is invalid.
    uint8_t id;

    // Must be 0.
    uint8_t res0;
    // Must be 0.
    uint16_t res1;

    // Number of uint32_t entry indices that follow.
    uint32_t entryCount;

    // Offset from header where ResTable_entry data starts.
    uint32_t entriesStart;

    // Configuration this collection of entries is designed for.
    ResTable_config config;
};
 */

import com.runing.utilslib.arscparser.util.Bytes;

/**
 * 类型资源项数据块。
 */
public class ResTableType {
  public static final int BYTES = ResChunkHeader.BYTES + Byte.BYTES * 2 + Short.BYTES + Integer.BYTES * 2 +
      ResTableConfig.BYTES;

  public static final int NO_ENTRY = 0xFFFFFFFF;

  /**
   * {@link ResChunkHeader#type} = {@link ResourceTypes#RES_TABLE_TYPE_TYPE}
   * <p>
   * {@link ResChunkHeader#headerSize} = {@link #BYTES} 表示头部大小。
   * <p>
   * {@link ResChunkHeader#size} = {@link #BYTES} + {@link Integer#BYTES} * {@link #entryCount}
   */
  public ResChunkHeader header;
  /** 资源 Type ID */
  public byte id;
  /** 0，保留 */
  public byte res0;
  /** 0，保留 */
  public short res1;
  /** 本类型的资源项个数，即名称相同的资源项的个数 */
  public int entryCount;
  /** 资源项数据块相对头部的偏移值 */
  public int entriesStart;
  /** 描述配置信息 */
  public ResTableConfig tableConfig;

  public ResTableType(ResChunkHeader header, byte id, byte res0, short res1, int entryCount, int entriesStart,
                      ResTableConfig tableConfig) {
    this.header = header;
    this.id = id;
    this.res0 = res0;
    this.res1 = res1;
    this.entryCount = entryCount;
    this.entriesStart = entriesStart;
    this.tableConfig = tableConfig;
  }

  public static ResTableType valueOfBytes(byte[] arsc, ResChunkHeader header, int tableTypeIndex) {
    int index = tableTypeIndex;
    return new ResTableType(
        header,
        arsc[index += ResChunkHeader.BYTES],
        arsc[index += Byte.BYTES],
        Bytes.getShort(arsc, index += Byte.BYTES),
        Bytes.getInt(arsc, index += Short.BYTES),
        Bytes.getInt(arsc, index + Integer.BYTES),
        ResTableConfig.valueOfBytes(arsc, index + Integer.BYTES));
  }

  @Override
  public String toString() {
    return Config.BEAUTIFUL ?
        "{" +
            "header=" + header +
            ", id=" + Bytes.toHex(new byte[]{id}) +
            ", res0=" + res0 +
            ", res1=" + res1 +
            ", entryCount=" + entryCount +
            ", entriesStart=" + entriesStart +
            ", tableConfig=" + tableConfig +
            '}'
        :
        "ResTableType{" +
            "header=" + header +
            ", id=" + id +
            ", res0=" + res0 +
            ", res1=" + res1 +
            ", entryCount=" + entryCount +
            ", entriesStart=" + entriesStart +
            ", tableConfig=" + tableConfig +
            '}';
  }
}
