package com.runing.utilslib.arscparser.old.type;

/*
struct ResTable_header
{
    struct ResChunk_header header;

    // The number of ResTable_package structures.
    uint32_t packageCount;
};
 */

import com.runing.utilslib.arscparser.old.util.Bytes;

/**
 * 资源表头结构。
 */
public class ResTableHeader {

  public static final int BYTES = ResChunkHeader.BYTES + Integer.BYTES;

  /**
   * {@link ResChunkHeader#type} = {@link ResourceTypes#RES_TABLE_TYPE}
   * <p>
   * {@link ResChunkHeader#headerSize} = {@link #BYTES} 表示头部大小。
   * <p>
   * {@link ResChunkHeader#size} = 整个 resources.arsc 文件的大小。
   */
  public ResChunkHeader header;
  /**
   * 被编译的资源包数量
   */
  public int packageCount;

  public ResTableHeader(ResChunkHeader header, int packageCount) {
    this.header = header;
    this.packageCount = packageCount;
  }

  public static ResTableHeader valueOfBytes(byte[] arsc, ResChunkHeader header) {
    return new ResTableHeader(
        header,
        Bytes.getInt(arsc, ResChunkHeader.BYTES)
    );
  }

  @Override
  public String toString() {

    return Config.BEAUTIFUL ?
        "{" +
            "header=" + header +
            ", packageCount=" + packageCount +
            '}'
        :
        "ResTableHeader{" +
            "header=" + header +
            ", packageCount=" + packageCount +
            '}';
  }
}
