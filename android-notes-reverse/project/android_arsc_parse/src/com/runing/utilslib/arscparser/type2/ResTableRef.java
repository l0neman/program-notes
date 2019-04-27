package com.runing.utilslib.arscparser.type2;
/*
struct ResTable_ref
{
    uint32_t ident;
};
 */

import com.runing.utilslib.arscparser.util.Bytes;
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
            "ident=" + Bytes.toHex(Bytes.fromInt(ident)) +
            '}'
        :
        "ResTableRef{" +
            "ident=" + ident +
            '}';
  }
}
