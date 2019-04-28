package com.runing.utilslib.arscparser.old.type;
/*
struct ResTable_ref
{
    uint32_t ident;
};
 */

import com.runing.utilslib.arscparser.old.util.Bytes;

/**
 * 资源的引用（ResID）
 */
public class ResTableRef {

  public static final int BYTES = Integer.BYTES;
  public int ident;

  public ResTableRef(int ident) {
    this.ident = ident;
  }

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
