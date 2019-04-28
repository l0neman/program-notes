package com.runing.utilslib.arscparser.type;
/*
struct ResTable_ref
{
    uint32_t ident;
};
 */

import com.runing.utilslib.arscparser.util.Formatter;
import com.runing.utilslib.arscparser.util.objectio.Struct;

/**
 * 资源的引用（ResID）
 */
public class ResTableRef implements Struct {

  public int ident;

  @Override
  public String toString() {
    return Config.BEAUTIFUL ?
        "{" +
            "ident=" + Formatter.toHex(Formatter.fromInt(ident)) +
            '}'
        :
        "ResTableRef{" +
            "ident=" + ident +
            '}';
  }
}
