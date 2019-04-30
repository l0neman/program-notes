package com.runing.utilslib.arscparser.xml.export.type;

import com.runing.utilslib.arscparser.xml.export.type.ResStringPoolRef;
import com.runing.utilslib.arscparser.xml.export.util.objectio.Struct;


public class ResXMLTreeCdataExt implements Struct {
  /** CDATA 原始值在字符串池中的索引 */
  public ResStringPoolRef data;
  /** CDATA */
  public ResValue typeData;
}
