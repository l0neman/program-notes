package com.runing.utilslib.arscparser.type;

import com.runing.utilslib.arscparser.util.objectio.Struct;

public class ResXMLTreeCdataExt implements Struct {
  /** CDATA 原始值在字符串池中的索引 */
  public ResStringPoolRef data;
  /** CDATA */
  public ResValue typeData;

  @Override
  public String toString() {
    return Config.BEAUTIFUL ?
        "{" +
            "data=" + data +
            ", typeData=" + typeData +
            '}'
        :
        "ResXMLTreeCdataExt{" +
            "data=" + data +
            ", typeData=" + typeData +
            '}';
  }
}
