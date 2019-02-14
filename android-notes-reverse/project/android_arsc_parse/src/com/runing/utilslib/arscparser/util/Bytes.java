package com.runing.utilslib.arscparser.util;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;

public class Bytes {

  public static byte[] copy(byte[] b, int start, int length) {
    byte[] copy = new byte[length];
    System.arraycopy(b, start, copy, 0, length);
    return copy;
  }

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

  public static byte[] fromShort(int a) {
    return new byte[]{
        (byte) ((a) & 0xFF),
        (byte) ((a >> 8) & 0xFF)
    };
  }

  /**
   * 从 start 位置后 4 个字节读取 int 值。
   *
   * @param b     字节数组
   * @param start 起始位置
   * @return int value
   */
  public static int getInt(byte[] b, int start) {
    return ((b[start + 3] & 0xFF) << 24) | ((b[start + 2] & 0xFF) << 16) | ((b[start + 1] & 0xFF) << 8) |
        (b[start] & 0xFF);
  }

  /**
   * 从 start 位置后 2 个字节读取 short 值。
   *
   * @param b     字节数组
   * @param start 起始位置
   * @return short value
   */
  public static short getShort(byte[] b, int start) {
    return (short) (((b[start + 1] & 0xFF) << 8) | (b[start] & 0xFF));
  }

  public static int toInt(byte[] b) {
    return ((b[3] & 0xFF) << 24) | ((b[2] & 0xFF) << 16) | ((b[1] & 0xFF) << 8) | (b[0] & 0xFF);
  }

  public static short toShort(byte[] b) {
    return (short) (((b[1] & 0xFF) << 8) | (b[0] & 0xFF));
  }

  public static char[] toChars(byte[] b) {
    Charset cs = Charset.forName("utf8");
    ByteBuffer bb = ByteBuffer.allocate(b.length);
    bb.put(b);
    bb.flip();
    CharBuffer cb = cs.decode(bb);
    return cb.array();
  }
}
