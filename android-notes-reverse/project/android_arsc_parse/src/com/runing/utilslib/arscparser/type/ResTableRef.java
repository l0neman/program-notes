package com.runing.utilslib.arscparser.type;
/*
struct ResTable_ref
{
    uint32_t ident;
};
 */

import com.runing.utilslib.arscparser.util.Formatter;
import com.runing.utilslib.arscparser.util.objectio.Struct;
import com.runing.utilslib.arscparser.util.objectio.FieldOrder;

/**
 * 资源的引用（ResID）
 */
public class ResTableRef implements Struct {

  @FieldOrder(n = 0) public int ident;

  @Override
  public String toString() {
    return Config.BEAUTIFUL ?
        "{" +
            "ident=" + Formatter.toHex(Formatter.fromInt(ident, true)) +
            '}'
        :
        "ResTableRef{" +
            "ident=" + ident +
            '}';
  }
}
