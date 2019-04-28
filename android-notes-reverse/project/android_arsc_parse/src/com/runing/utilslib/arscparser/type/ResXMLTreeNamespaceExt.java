package com.runing.utilslib.arscparser.type;

import com.runing.utilslib.arscparser.util.objectio.Struct;

public class ResXMLTreeNamespaceExt implements Struct {
  /** 命名空间字符串在字符串资源池中的索引 */
  public ResStringPoolRef prefix;
  /** uri 字符串在字符串资源池中的索引 */
  public ResStringPoolRef uri;

  @Override
  public String toString() {
    return Config.BEAUTIFUL ?
        "{" +
            "prefix=" + prefix +
            ", uri=" + uri +
            '}'
        :
        "ResXMLTreeNamespaceExt{" +
            "prefix=" + prefix +
            ", uri=" + uri +
            '}';
  }
}
