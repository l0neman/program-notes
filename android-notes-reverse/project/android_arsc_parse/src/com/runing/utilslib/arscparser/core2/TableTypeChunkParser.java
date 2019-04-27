package com.runing.utilslib.arscparser.core2;

import com.runing.utilslib.arscparser.type2.ResTableType;
import com.runing.utilslib.arscparser.type2.ResTableTypeSpec;
import com.runing.utilslib.arscparser.util.objectio.ObjectIO;

import java.io.IOException;

public class TableTypeChunkParser {

  @SuppressWarnings("Duplicates")
  public static int[] parseSpecEntryArray(ObjectIO objectIO, ResTableTypeSpec tableTypeSpec, long typeSpecIndex)
      throws IOException {
    int[] entryArray = new int[tableTypeSpec.entryCount];
    long index = typeSpecIndex + tableTypeSpec.header.headerSize;

    for (int i = 0; i < entryArray.length; i++) {
      entryArray[i] = objectIO.readInt(index);
      index += Integer.BYTES;
    }
    return entryArray;
  }

  @SuppressWarnings("Duplicates")
  public static int[] parseTypeOffsetArray(ObjectIO objectIO, ResTableType tableType, long typeIndex)
      throws IOException {
    int[] entryArray = new int[tableType.entryCount];
    long index = typeIndex + tableType.header.headerSize;

    for (int i = 0; i < entryArray.length; i++) {
      entryArray[i] = objectIO.readInt(index);
      index += Integer.BYTES;
    }
    return entryArray;
  }
}
