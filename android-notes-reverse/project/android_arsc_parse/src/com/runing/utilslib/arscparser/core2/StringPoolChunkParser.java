package com.runing.utilslib.arscparser.core2;

import com.runing.utilslib.arscparser.type2.ResStringPoolHeader;
import com.runing.utilslib.arscparser.type2.ResStringPoolRef;
import com.runing.utilslib.arscparser.type2.ResStringPoolSpan;
import com.runing.utilslib.arscparser.util.objectio.StructIO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class StringPoolChunkParser {

  private ResStringPoolRef[] stringIndexArray;
  private ResStringPoolRef[] styleIndexArray;
  private String[] stringPool;
  private List<ResStringPoolSpan>[] stylePool;

  private ResStringPoolRef[] parseStringIndexArray(StructIO structIO, ResStringPoolHeader header, long index)
      throws IOException {
    stringIndexArray = new ResStringPoolRef[header.stringCount];
    long start = index;
    final int resStringPoolRefSize = structIO.sizeOf(ResStringPoolRef.class);
    for (int i = 0; i < header.stringCount; i++) {
      stringIndexArray[i] = structIO.read(ResStringPoolRef.class, start);
      start += resStringPoolRefSize;
    }

    return stringIndexArray;
  }

  private ResStringPoolRef[] parseStyleIndexArray(StructIO structIO, ResStringPoolHeader header, long index)
      throws IOException {
    styleIndexArray = new ResStringPoolRef[header.styleCount];
    long start = index;
    final int resStringPoolRefSize = structIO.sizeOf(ResStringPoolRef.class);
    for (int i = 0; i < header.styleCount; i++) {
      styleIndexArray[i] = structIO.read(ResStringPoolRef.class, start);
      start += resStringPoolRefSize;
    }

    return styleIndexArray;
  }

  private static int parseStringLength(byte[] b) {
    return b[1] & 0x7F;
  }

  private String[] parseStringPool(StructIO structIO, ResStringPoolHeader header, long stringPoolIndex)
      throws IOException {
    String[] stringPool = new String[header.stringCount];
    for (int i = 0; i < header.stringCount; i++) {
      final long index = stringPoolIndex + stringIndexArray[i].index;
      final int stringLength = parseStringLength(structIO.readBytes(index, Short.BYTES));
      stringPool[i] = new String(structIO.readBytes(index + Short.BYTES, stringLength), 0, stringLength,
          StandardCharsets.UTF_8);
    }

    return stringPool;
  }

  private List<ResStringPoolSpan>[] parseStylePool(StructIO structIO, ResStringPoolHeader header, long stylePoolIndex)
      throws IOException {
    List<ResStringPoolSpan>[] stylePool = new List[header.styleCount];
    for (int i = 0; i < header.styleCount; i++) {
      final long index = stylePoolIndex + styleIndexArray[i].index;
      int end = 0;
      long littleIndex = index;
      List<ResStringPoolSpan> stringPoolSpans = new ArrayList<>();
      while (end != ResStringPoolSpan.END) {
        ResStringPoolSpan stringPoolSpan = structIO.read(ResStringPoolSpan.class, littleIndex);
        stringPoolSpans.add(stringPoolSpan);

        littleIndex += StructIO.sizeOf(ResStringPoolSpan.class);

        end = structIO.readInt(littleIndex);
      }

      stylePool[i] = stringPoolSpans;
    }
    return stylePool;
  }

  public void parseStringPoolChunk(StructIO structIO, ResStringPoolHeader header, long stringPoolHeaderIndex)
      throws IOException {
    // parse string index array.
    final long stringIndexArrayIndex = stringPoolHeaderIndex + StructIO.sizeOf(ResStringPoolHeader.class);
    stringIndexArray = header.stringCount == 0 ? new ResStringPoolRef[0] :
        parseStringIndexArray(structIO, header, stringIndexArrayIndex);

    final long styleIndexArrayIndex = stringIndexArrayIndex + header.stringCount *
        StructIO.sizeOf(ResStringPoolRef.class);
    styleIndexArray = header.styleCount == 0 ? new ResStringPoolRef[0] :
        parseStyleIndexArray(structIO, header, styleIndexArrayIndex);

    // parse string pool.
    if (header.stringCount != 0) {
      final long stringPoolIndex = stringPoolHeaderIndex + header.stringStart;
      stringPool = parseStringPool(structIO, header, stringPoolIndex);
    } else {
      stringPool = new String[0];
    }

    // parse style pool.
    if (header.styleCount != 0) {
      final long stylePoolIndex = stringPoolHeaderIndex + header.styleStart;
      stylePool = parseStylePool(structIO, header, stylePoolIndex);
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
