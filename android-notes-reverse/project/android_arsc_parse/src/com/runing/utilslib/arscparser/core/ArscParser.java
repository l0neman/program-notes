package com.runing.utilslib.arscparser.core;

import com.runing.utilslib.arscparser.type.*;
import com.runing.utilslib.arscparser.util.objectio.ObjectIO;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@SuppressWarnings("ALL")
public class ArscParser {

  private long mIndex;

  private String[] typeStringPool;

  private void parseResTableType(ObjectIO objectIO) throws Exception {
    final ResTableHeader tableType = objectIO.read(ResTableHeader.class, mIndex);
    System.out.println("resource table header:");
    System.out.println(tableType);

    // 向下移动资源表头部的大小。
    mIndex += tableType.header.headerSize;
  }

private void parseStringPool(ObjectIO objectIO) throws Exception {
  final long stringPoolIndex = mIndex;
  ResStringPoolHeader stringPoolHeader = objectIO.read(ResStringPoolHeader.class, stringPoolIndex);
  System.out.println("string pool header:");
  System.out.println(stringPoolHeader);

  StringPoolChunkParser stringPoolChunkParser = new StringPoolChunkParser();
  stringPoolChunkParser.parseStringPoolChunk(objectIO, stringPoolHeader, stringPoolIndex);

  System.out.println();
  System.out.println("string index array:");
  System.out.println(Arrays.toString(stringPoolChunkParser.getStringIndexArray()));

  System.out.println();
  System.out.println("style index array:");
  System.out.println(Arrays.toString(stringPoolChunkParser.getStyleIndexArray()));

  System.out.println();
  System.out.println("string pool:");
  final String[] stringPool = stringPoolChunkParser.getStringPool();

  System.out.println(Arrays.toString(stringPool));
  typeStringPool = stringPool;

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

private void parseTablePackageType(ObjectIO objectIO) throws IOException {
  final long tablePackageIndex = mIndex;
  final ResTablePackage tablePackage = objectIO.read(ResTablePackage.class, tablePackageIndex);

  System.out.println("table package type:");
  System.out.println(tablePackage);

  // 向下移动资源表元信息头部的大小。
  mIndex += tablePackage.header.headerSize;
}

private void parseTableTypeSpecType(ObjectIO objectIO) throws IOException {
  final long typeSpecIndex = mIndex;
  ResTableTypeSpec tableTypeSpec = objectIO.read(ResTableTypeSpec.class, typeSpecIndex);

  System.out.println("table type spec type:");
  System.out.println(tableTypeSpec);

  int[] entryArray = TableTypeChunkParser.parseSpecEntryArray(objectIO, tableTypeSpec, typeSpecIndex);

  System.out.println();
  System.out.println("table type spec type entry array:");
  System.out.println(Arrays.toString(entryArray));

  // 向下移动资源表类型规范内容的大小。
  mIndex += tableTypeSpec.header.size;
}

private void parseTableTypeType(ObjectIO objectIO) throws IOException {
  final long tableTypeIndex = mIndex;
  final ResTableType tableType = objectIO.read(ResTableType.class, tableTypeIndex);

  System.out.println("table type type:");
  System.out.println(tableType);

  int[] offsetArray = TableTypeChunkParser.parseTypeOffsetArray(objectIO, tableType, tableTypeIndex);

  System.out.println();
  System.out.println("offset array:");
  System.out.println(Arrays.toString(offsetArray));

  final long tableEntryIndex = tableTypeIndex + tableType.entriesStart;

  for (int i = 0; i < offsetArray.length; i++) {
    final long entryIndex = offsetArray[i] + tableEntryIndex;
    final ResTableEntry tableEntry = objectIO.read(ResTableEntry.class, entryIndex);

    System.out.println();
    System.out.println("table type type entry " + i + ":");
    System.out.println("header: " + tableEntry);
    System.out.println("entry name: " + typeStringPool[tableEntry.key.index]);

    if (tableEntry.flags == ResTableEntry.FLAG_COMPLEX) {
      // parse ResTable_map
      final ResTableMapEntry tableMapEntry = objectIO.read(ResTableMapEntry.class, entryIndex);

      System.out.println(tableMapEntry);

      int index = 0;

      for (int j = 0; j < tableMapEntry.count; j++) {
        final long tableMapIndex = index + entryIndex + tableMapEntry.size;

        ResTableMap tableMap = objectIO.read(ResTableMap.class, tableMapIndex);
        System.out.println("table map " + j + ":");
        System.out.println(tableMap);

        index += ObjectIO.sizeOf(ResTableMap.class);
      }
    } else {
      // parse Res_value
      final int entrySize = ObjectIO.sizeOf(ResTableEntry.class);
      final ResValue value = objectIO.read(ResValue.class, entryIndex + entrySize);

      System.out.println(value);
    }
  }

  mIndex = objectIO.size();
}

  private void parse(ObjectIO objectIO) throws Exception {
    if (objectIO.isEof(mIndex)) { return; }

    ResChunkHeader header = objectIO.read(ResChunkHeader.class, mIndex);

    System.out.println();
    System.out.println("================================ " + ResourceTypes.nameOf(header.type) +
        " ================================");
    switch (header.type) {
      case ResourceTypes.RES_TABLE_TYPE:
        parseResTableType(objectIO);
        parse(objectIO);
        break;

      case ResourceTypes.RES_STRING_POOL_TYPE:
        System.out.println(ResourceTypes.nameOf(header.type));

        parseStringPool(objectIO);
        parse(objectIO);
        break;

      case ResourceTypes.RES_TABLE_PACKAGE_TYPE:
        System.out.println(ResourceTypes.nameOf(header.type));

        parseTablePackageType(objectIO);
        parse(objectIO);
        break;

      case ResourceTypes.RES_TABLE_TYPE_SPEC_TYPE:
        System.out.println(ResourceTypes.nameOf(header.type));

        parseTableTypeSpecType(objectIO);
        parse(objectIO);
        break;

      case ResourceTypes.RES_TABLE_TYPE_TYPE:
        System.out.println(ResourceTypes.nameOf(header.type));

        parseTableTypeType(objectIO);
        parse(objectIO);
        break;

      default:
    }
  }

  private static void closeQuietly(Closeable closeable) {
    if (closeable != null) {
      try {
        closeable.close();
      } catch (IOException ignore) {
      } catch (RuntimeException e) {
        e.printStackTrace();
      }
    }
  }

  public void parse(String file) {
    ObjectIO objectIO = null;

    try {
      objectIO = new ObjectIO(file, false);
      parse(objectIO);

    } catch (Exception e) {
      e.printStackTrace();

    } finally {
      closeQuietly(objectIO);
    }
  }
}
