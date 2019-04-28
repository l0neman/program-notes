package com.runing.utilslib.arscparser.type;

import com.runing.utilslib.arscparser.util.objectio.Struct;

/*
struct ResXMLTree_header
{
    struct ResChunk_header header;
};
 */
public class ResXMLTreeHeader implements Struct {
  /**
   * {@link ResChunkHeader#type} = {@link ResourceTypes#RES_XML_TYPE}
   * <p>
   * {@link ResChunkHeader#headerSize} = sizeOf(ResXMLTreeHeader.class) 表示头部大小。
   * <p>
   * {@link ResChunkHeader#size} = 整个二进制 Xml 文件的大小，包括头部的大小。
   */
  public ResChunkHeader header;

  @Override
  public String toString() {
    return Config.BEAUTIFUL ?
        "{" +
            "header=" + header +
            '}'
        :
        "ResXMLTreeHeader{" +
            "header=" + header +
            '}';
  }
}
