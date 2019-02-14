package com.runing.utilslib.arscparser.core;

import com.runing.utilslib.arscparser.type.ResStringPoolHeader;
import com.runing.utilslib.arscparser.type.ResStringPoolRef;
import com.runing.utilslib.arscparser.type.ResStringPoolSpan;
import com.runing.utilslib.arscparser.util.Bytes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

class StringPoolChunkParser {

  private ResStringPoolRef[] stringIndexArray;
  private ResStringPoolRef[] styleIndexArray;
  private String[] stringPool;
  private List<ResStringPoolSpan>[] stylePool;

  private ResStringPoolRef[] parseStringIndexArray(byte[] b, ResStringPoolHeader header) {
    stringIndexArray = new ResStringPoolRef[header.stringCount];
    return getInts(b, header.stringCount, stringIndexArray);
  }

  private ResStringPoolRef[] parseStyleIndexArray(byte[] b, ResStringPoolHeader header) {
    styleIndexArray = new ResStringPoolRef[header.styleCount];
    return getInts(b, header.styleCount, styleIndexArray);
  }

  private ResStringPoolRef[] getInts(byte[] b, int styleCount, ResStringPoolRef[] indexArray) {
    int start = 0;
    for (int i = 0; i < styleCount; i++) {
      indexArray[i] = new ResStringPoolRef(
          Bytes.getInt(b, start)
      );
      start += Integer.BYTES;
    }
    return indexArray;
  }

  private static int parseStringLength(byte[] b) {
    return b[1] & 0x7F;
  }

  private String[] parseStringPool(byte[] b, ResStringPoolHeader header) {
    String[] stringPool = new String[header.stringCount];
    for (int i = 0; i < header.stringCount; i++) {
      final int index = stringIndexArray[i].index;
      final int stringLength = parseStringLength(Bytes.copy(b, index, Short.BYTES));
      stringPool[i] = new String(
          Bytes.copy(b, index + Short.BYTES, stringLength), StandardCharsets.UTF_8
      );
    }
    return stringPool;
  }

  private List<ResStringPoolSpan>[] parseStylePool(byte[] b, ResStringPoolHeader header) {
    //noinspection unchecked
    List<ResStringPoolSpan>[] stylePool = new List[header.styleCount];
    for (int i = 0; i < header.styleCount; i++) {
      final int index = styleIndexArray[i].index;
      int end = 0;
      int littleIndex = index;
      List<ResStringPoolSpan> stringPoolSpans = new ArrayList<>();
      while (end != ResStringPoolSpan.END) {
        ResStringPoolSpan stringPoolSpan = new ResStringPoolSpan(
            new ResStringPoolRef(Bytes.getInt(b, littleIndex)),
            Bytes.getInt(b, littleIndex += Integer.BYTES),
            Bytes.getInt(b, littleIndex += Integer.BYTES)
        );

        stringPoolSpans.add(stringPoolSpan);
        end = Bytes.getInt(b, littleIndex += Integer.BYTES);
      }
      stylePool[i] = stringPoolSpans;
    }
    return stylePool;
  }

  void parseStringPoolChunk(byte[] arsc, ResStringPoolHeader header, int stringPoolHeaderIndex) {
    // parse string index array.
    final int stringIndexArrayIndex = stringPoolHeaderIndex + ResStringPoolHeader.BYTES;
    stringIndexArray = parseStringIndexArray(
        Bytes.copy(arsc, stringIndexArrayIndex, header.stringCount * Integer.BYTES), header);

    // parse style index array.
    final int styleIndexArrayIndex = stringIndexArrayIndex + header.stringCount * Integer.BYTES;
    styleIndexArray = parseStyleIndexArray(
        Bytes.copy(arsc, styleIndexArrayIndex, header.styleCount * Integer.BYTES), header);

    // parse string pool.
    if (header.stringStart != 0) {
      final int stringPoolIndex = stringPoolHeaderIndex + header.stringStart;
      final int stringPoolLength = header.header.size;
      stringPool = parseStringPool(Bytes.copy(arsc, stringPoolIndex, stringPoolLength), header);
    }
    else {
      stringPool = new String[0];
    }

    // parse style pool.
    if (header.styleStart != 0) {
      final int stylePoolIndex = stringPoolHeaderIndex + header.styleStart;

      stylePool = parseStylePool(
          Bytes.copy(arsc, stylePoolIndex, header.header.size - header.styleStart - 8), header);
    }
    else {
      //noinspection unchecked
      stylePool = new List[0];
    }
  }

  ResStringPoolRef[] getStringIndexArray() {
    return stringIndexArray;
  }

  ResStringPoolRef[] getStyleIndexArray() {
    return styleIndexArray;
  }

  String[] getStringPool() {
    return stringPool;
  }

  List<ResStringPoolSpan>[] getStylePool() {
    return stylePool;
  }
}
