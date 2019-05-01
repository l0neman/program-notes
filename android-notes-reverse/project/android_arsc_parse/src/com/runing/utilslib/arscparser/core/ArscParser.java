package com.runing.utilslib.arscparser.core;

import com.runing.utilslib.arscparser.type.*;
import com.runing.utilslib.arscparser.util.objectio.ObjectInput;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@SuppressWarnings("ALL")
public class ArscParser {

  private long mIndex;
  private String[] stringPool;

  private void parseResTableType(ObjectInput objectInput) throws IOException {
    final ResTableHeader tableType = objectInput.read(ResTableHeader.class, mIndex);
    System.out.println("resource table header:");
    System.out.println(tableType);

    // 向下移动资源表头部的大小。
    mIndex += tableType.header.headerSize;
  }

  private void parseStringPool(ObjectInput objectInput) throws IOException {
    final long stringPoolIndex = mIndex;
    ResStringPoolHeader stringPoolHeader = objectInput.read(ResStringPoolHeader.class, stringPoolIndex);
    System.out.println("string pool header:");
    System.out.println(stringPoolHeader);

    StringPoolChunkParser stringPoolChunkParser = new StringPoolChunkParser();
    stringPoolChunkParser.parseStringPoolChunk(objectInput, stringPoolHeader, stringPoolIndex);

    System.out.println();
    System.out.println("string index array:");
    System.out.println(Arrays.toString(stringPoolChunkParser.getStringIndexArray()));

    System.out.println();
    System.out.println("style index array:");
    System.out.println(Arrays.toString(stringPoolChunkParser.getStyleIndexArray()));

    stringPool = stringPoolChunkParser.getStringPool();

    System.out.println();
    System.out.println("string pool:");
    System.out.println(Arrays.toString(stringPool));

    System.out.println();
    System.out.println("style pool:");
    final List<ResStringPoolSpan>[] stylePool = stringPoolChunkParser.getStylePool();

    System.out.println(Arrays.toString(stylePool));

    System.out.println();
    System.out.println("style detail:");
    for (List<ResStringPoolSpan> spans : stylePool) {
      System.out.println("---------");
      for (ResStringPoolSpan span : spans) {
        System.out.println(stringPool[span.name.index]);
      }
    }

    // 向下移动字符串池的大小。
    mIndex += stringPoolHeader.header.size;
  }

  private void parseTablePackageType(ObjectInput objectInput) throws IOException {
    final long tablePackageIndex = mIndex;
    final ResTablePackage tablePackage = objectInput.read(ResTablePackage.class, tablePackageIndex);

    System.out.println("table package type:");
    System.out.println(tablePackage);

    // 向下移动资源表元信息头部的大小。
    mIndex += tablePackage.header.headerSize;
  }

  private void parseTableTypeSpecType(ObjectInput objectInput) throws IOException {
    final long typeSpecIndex = mIndex;
    ResTableTypeSpec tableTypeSpec = objectInput.read(ResTableTypeSpec.class, typeSpecIndex);

    System.out.println("table type spec type:");
    System.out.println(tableTypeSpec);

    int[] entryArray = TableTypeChunkParser.parseSpecEntryArray(objectInput, tableTypeSpec, typeSpecIndex);

    System.out.println();
    System.out.println("table type spec type entry array:");
    System.out.println(Arrays.toString(entryArray));

    // 向下移动资源表类型规范内容的大小。
    mIndex += tableTypeSpec.header.size;
  }

  private void parseTableTypeType(ObjectInput objectInput) throws IOException {
    final long tableTypeIndex = mIndex;
    final ResTableType tableType = objectInput.read(ResTableType.class, tableTypeIndex);

    System.out.println("table type type:");
    System.out.println(tableType);

    int[] offsetArray = TableTypeChunkParser.parseTypeOffsetArray(objectInput, tableType, tableTypeIndex);

    System.out.println();
    System.out.println("offset array:");
    System.out.println(Arrays.toString(offsetArray));

    final long tableEntryIndex = tableTypeIndex + tableType.entriesStart;

    for (int i = 0; i < offsetArray.length; i++) {
      final long entryIndex = offsetArray[i] + tableEntryIndex;
      final ResTableEntry tableEntry = objectInput.read(ResTableEntry.class, entryIndex);

      System.out.println();
      System.out.println("table type type entry " + i + ":");
      System.out.println("header: " + tableEntry);
      System.out.println("entry name: " + stringPool[tableEntry.key.index]);

      if (tableEntry.flags == ResTableEntry.FLAG_COMPLEX) {
        // parse ResTable_map
        final ResTableMapEntry tableMapEntry = objectInput.read(ResTableMapEntry.class, entryIndex);

        System.out.println(tableMapEntry);

        int index = 0;

        for (int j = 0; j < tableMapEntry.count; j++) {
          final long tableMapIndex = index + entryIndex + tableMapEntry.size;

          ResTableMap tableMap = objectInput.read(ResTableMap.class, tableMapIndex);
          System.out.println("table map " + j + ":");
          System.out.println(tableMap);

          index += ObjectInput.sizeOf(ResTableMap.class);
        }
      } else {
        // parse Res_value
        final int entrySize = ObjectInput.sizeOf(ResTableEntry.class);
        final ResValue value = objectInput.read(ResValue.class, entryIndex + entrySize);

        System.out.println(value);
      }
    }

    mIndex = objectInput.size();
  }

  private void parse(ObjectInput objectInput) throws IOException {
    while (!objectInput.isEof(mIndex)) {
      ResChunkHeader header = objectInput.read(ResChunkHeader.class, mIndex);

      System.out.println();
      System.out.println("================================ " + ResourceTypes.nameOf(header.type) +
          " ================================");
      switch (header.type) {
        case ResourceTypes.RES_TABLE_TYPE:
          parseResTableType(objectInput);
          break;

        case ResourceTypes.RES_STRING_POOL_TYPE:
          parseStringPool(objectInput);
          break;

        case ResourceTypes.RES_TABLE_PACKAGE_TYPE:
          parseTablePackageType(objectInput);
          break;

        case ResourceTypes.RES_TABLE_TYPE_SPEC_TYPE:
          parseTableTypeSpecType(objectInput);
          break;

        case ResourceTypes.RES_TABLE_TYPE_TYPE:
          parseTableTypeType(objectInput);
          break;

        default:
      }
    }
  }

  private static void closeQuietly(Closeable closeable) {
    if (closeable != null) {
      try {
        closeable.close();
      } catch (IOException ignore) {
      } catch (RuntimeException e) {
        throw new RuntimeException(e);
      }
    }
  }

  public void parse(String file) throws IOException{
    ObjectInput objectInput = null;

    try {
      objectInput = new ObjectInput(file, false);
      parse(objectInput);
    } finally {
      closeQuietly(objectInput);
    }
  }
}
