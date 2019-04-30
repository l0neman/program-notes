package com.runing.utilslib.arscparser.xml.export.type;

import com.runing.utilslib.arscparser.xml.export.type.ResStringPoolRef;
import com.runing.utilslib.arscparser.xml.export.util.objectio.Struct;


public class ResXMLTreeEndElementExt implements Struct {
  /** 元素的命名空间的字符串在字符串池中的索引，未指定则为 -1 */
  public ResStringPoolRef ns;
  /** 元素名称字符串在字符串池中的索引 */
  public ResStringPoolRef name;
}
