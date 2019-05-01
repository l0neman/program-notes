package com.runing.utilslib.arscparser.xml.export.type;

import com.runing.utilslib.arscparser.xml.export.util.objectio.FieldOrder;
import com.runing.utilslib.arscparser.xml.export.util.objectio.Struct;


public class ResXMLTreeNamespaceExt implements Struct {
  /** 命名空间字符串在字符串资源池中的索引 */
  @FieldOrder(n = 0) public ResStringPoolRef prefix;
  /** uri 字符串在字符串资源池中的索引 */
  @FieldOrder(n = 0) public ResStringPoolRef uri;
}
