package com.runing.utilslib.arscparser.util;

public class Formatter {
  public static String toHex(byte[] b) {
    StringBuilder stringBuilder = new StringBuilder("0x");
    for (byte b1 : b) {
      int v = b1 & 0xFF;
      String h = Integer.toHexString(v);
      if (h.length() < 2) {
        stringBuilder.append(0);
      }
      stringBuilder.append(h);
    }
    return stringBuilder.toString();
  }

  public static byte[] fromInt(int a) {
    return new byte[]{
        (byte) ((a) & 0xFF),
        (byte) ((a >> 8) & 0xFF),
        (byte) ((a >> 16) & 0xFF),
        (byte) ((a >> 24) & 0xFF),
    };
  }
}
