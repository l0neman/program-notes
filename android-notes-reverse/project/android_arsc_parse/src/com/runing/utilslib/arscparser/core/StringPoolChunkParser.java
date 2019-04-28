package com.runing.utilslib.arscparser.core;

import com.runing.utilslib.arscparser.type.ResStringPoolHeader;
import com.runing.utilslib.arscparser.type.ResStringPoolRef;
import com.runing.utilslib.arscparser.type.ResStringPoolSpan;
import com.runing.utilslib.arscparser.util.objectio.ObjectIO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class StringPoolChunkParser {

  private ResStringPoolRef[] stringIndexArray;
  private ResStringPoolRef[] styleIndexArray;
  private String[] stringPool;
  private List<ResStringPoolSpan>[] stylePool;

  private ResStringPoolRef[] parseStringIndexArray(ObjectIO objectIO, ResStringPoolHeader header, long index)
      throws IOException {
    stringIndexArray = new ResStringPoolRef[header.stringCount];

    long start = index;
    final int resStringPoolRefSize = ObjectIO.sizeOf(ResStringPoolRef.class);

    for (int i = 0; i < header.stringCount; i++) {
      stringIndexArray[i] = objectIO.read(ResStringPoolRef.class, start);
      start += resStringPoolRefSize;
    }

    return stringIndexArray;
  }

  private ResStringPoolRef[] parseStyleIndexArray(ObjectIO objectIO, ResStringPoolHeader header, long index)
      throws IOException {
    styleIndexArray = new ResStringPoolRef[header.styleCount];

    long start = index;
    final int resStringPoolRefSize = ObjectIO.sizeOf(ResStringPoolRef.class);

    for (int i = 0; i < header.styleCount; i++) {
      styleIndexArray[i] = objectIO.read(ResStringPoolRef.class, start);
      start += resStringPoolRefSize;
    }

    return styleIndexArray;
  }

  private static int parseStringLength(byte[] b) {
    return b[1] & 0x7F;
  }

  private String[] parseStringPool(ObjectIO objectIO, ResStringPoolHeader header, long stringPoolIndex)
      throws IOException {
    String[] stringPool = new String[header.stringCount];

    for (int i = 0; i < header.stringCount; i++) {
      final long index = stringPoolIndex + stringIndexArray[i].index;
      final int stringLength = parseStringLength(objectIO.readBytes(index, Short.BYTES));

      stringPool[i] = new String(objectIO.readBytes(index + Short.BYTES, stringLength), 0, stringLength,
          StandardCharsets.UTF_8);
    }

    return stringPool;
  }

  private List<ResStringPoolSpan>[] parseStylePool(ObjectIO objectIO, ResStringPoolHeader header, long stylePoolIndex)
      throws IOException {
    @SuppressWarnings("unchecked")
    List<ResStringPoolSpan>[] stylePool = new List[header.styleCount];

    for (int i = 0; i < header.styleCount; i++) {
      final long index = stylePoolIndex + styleIndexArray[i].index;
      int end = 0;
      long littleIndex = index;

      List<ResStringPoolSpan> stringPoolSpans = new ArrayList<>();
      while (end != ResStringPoolSpan.END) {
        ResStringPoolSpan stringPoolSpan = objectIO.read(ResStringPoolSpan.class, littleIndex);
        stringPoolSpans.add(stringPoolSpan);

        littleIndex += ObjectIO.sizeOf(ResStringPoolSpan.class);

        end = objectIO.readInt(littleIndex);
      }

      stylePool[i] = stringPoolSpans;
    }
    return stylePool;
  }

  public void parseStringPoolChunk(ObjectIO objectIO, ResStringPoolHeader header, long stringPoolHeaderIndex)
      throws IOException {
    // parse string index array.
    final long stringIndexArrayIndex = stringPoolHeaderIndex + ObjectIO.sizeOf(ResStringPoolHeader.class);

    stringIndexArray = header.stringCount == 0 ? new ResStringPoolRef[0] :
        parseStringIndexArray(objectIO, header, stringIndexArrayIndex);

    final long styleIndexArrayIndex = stringIndexArrayIndex + header.stringCount *
        ObjectIO.sizeOf(ResStringPoolRef.class);

    styleIndexArray = header.styleCount == 0 ? new ResStringPoolRef[0] :
        parseStyleIndexArray(objectIO, header, styleIndexArrayIndex);

    // parse string pool.
    if (header.stringCount != 0) {
      final long stringPoolIndex = stringPoolHeaderIndex + header.stringStart;
      stringPool = parseStringPool(objectIO, header, stringPoolIndex);
    } else {
      stringPool = new String[0];
    }

    // parse style pool.
    if (header.styleCount != 0) {
      final long stylePoolIndex = stringPoolHeaderIndex + header.styleStart;
      stylePool = parseStylePool(objectIO, header, stylePoolIndex);
    } else {
      //noinspection unchecked
      stylePool = new List[0];
    }
  }

  public ResStringPoolRef[] getStringIndexArray() {
    return stringIndexArray;
  }

  public ResStringPoolRef[] getStyleIndexArray() {
    return styleIndexArray;
  }

  public String[] getStringPool() {
    return stringPool;
  }

  public List<ResStringPoolSpan>[] getStylePool() {
    return stylePool;
  }
}
