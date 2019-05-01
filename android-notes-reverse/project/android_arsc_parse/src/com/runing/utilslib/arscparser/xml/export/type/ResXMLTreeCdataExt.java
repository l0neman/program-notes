package com.runing.utilslib.arscparser.xml.export.type;

import com.runing.utilslib.arscparser.xml.export.util.objectio.FieldOrder;
import com.runing.utilslib.arscparser.xml.export.util.objectio.Struct;


public class ResXMLTreeCdataExt implements Struct {
  /** CDATA 原始值在字符串池中的索引 */
  @FieldOrder(n = 0) public ResStringPoolRef data;
  /** CDATA */
  @FieldOrder(n = 1) public ResValue typeData;
}
