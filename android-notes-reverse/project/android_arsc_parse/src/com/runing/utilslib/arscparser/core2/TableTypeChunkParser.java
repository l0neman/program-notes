package com.runing.utilslib.arscparser.core2;

import com.runing.utilslib.arscparser.type2.ResTableType;
import com.runing.utilslib.arscparser.type2.ResTableTypeSpec;
import com.runing.utilslib.arscparser.util.objectio.StructIO;

import java.io.IOException;

public class TableTypeChunkParser {

  @SuppressWarnings("Duplicates")
  public static int[] parseSpecEntryArray(StructIO structIO, ResTableTypeSpec tableTypeSpec, long typeSpecIndex)
      throws IOException {
    int[] entryArray = new int[tableTypeSpec.entryCount];
    long index = typeSpecIndex + tableTypeSpec.header.headerSize;
    for (int i = 0; i < entryArray.length; i++) {
      entryArray[i] = structIO.readInt(index);
      index += Integer.BYTES;
    }
    return entryArray;
  }

  @SuppressWarnings("Duplicates")
  public static int[] parseTypeOffsetArray(StructIO structIO, ResTableType tableType, long typeIndex)
      throws IOException {
    int[] entryArray = new int[tableType.entryCount];
    long index = typeIndex + tableType.header.headerSize;
    for (int i = 0; i < entryArray.length; i++) {
      entryArray[i] = structIO.readInt(index);
      index += Integer.BYTES;
    }
    return entryArray;
  }
}
