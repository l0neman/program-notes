package com.runing.utilslib.arscparser.core2;

import com.runing.utilslib.arscparser.type2.*;
import com.runing.utilslib.arscparser.util.objectio.StructIO;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@SuppressWarnings("ALL")
public class ArscParser2 {

  private long mIndex;

  private String[] typeStringPool;

  private void parseResTableType(StructIO structIO) throws Exception {
    final ResTableHeader tableType = structIO.read(ResTableHeader.class, mIndex);
    System.out.println("resource table header:");
    System.out.println(tableType);

    // 向下移动资源表头部的大小。
    mIndex += StructIO.sizeOf(ResTableHeader.class);
  }

  private void parseStringPool(StructIO structIO) throws Exception {
    final long stringPoolIndex = mIndex;
    ResStringPoolHeader stringPoolHeader = structIO.read(ResStringPoolHeader.class, stringPoolIndex);
    System.out.println("string pool header:");
    System.out.println(stringPoolHeader);

    StringPoolChunkParser stringPoolChunkParser = new StringPoolChunkParser();
    stringPoolChunkParser.parseStringPoolChunk(structIO, stringPoolHeader, stringPoolIndex);

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

  private void parseTablePackageType(StructIO structIO) throws IOException {
    final long tablePackageIndex = mIndex;
    final ResTablePackage tablePackage = structIO.read(ResTablePackage.class, tablePackageIndex);
    System.out.println("table package type:");
    System.out.println(tablePackage);

    // 向下移动资源表元信息头部的大小。
    mIndex += tablePackage.header.headerSize;
  }

  private void parseTableTypeSpecType(StructIO structIO) throws IOException {
    final long typeSpecIndex = mIndex;
    ResTableTypeSpec tableTypeSpec =  structIO.read(ResTableTypeSpec.class, typeSpecIndex);
    System.out.println("table type spec type:");
    System.out.println(tableTypeSpec);

    int[] entryArray = TableTypeChunkParser.parseSpecEntryArray(structIO, tableTypeSpec, typeSpecIndex);
    System.out.println();
    System.out.println("table type spec type entry array:");
    System.out.println(Arrays.toString(entryArray));

    // 向下移动资源表类型规范内容的大小。
    mIndex += tableTypeSpec.header.size;
  }

  private void parseTableTypeType(StructIO structIO) throws IOException {
    final long tableTypeIndex = mIndex;
    final ResTableType tableType = structIO.read(ResTableType.class, tableTypeIndex);
    System.out.println("table type type:");
    System.out.println(tableType);

    int[] offsetArray = TableTypeChunkParser.parseTypeOffsetArray(structIO, tableType, tableTypeIndex);
    System.out.println();
    System.out.println("offset array:");
    System.out.println(Arrays.toString(offsetArray));

    final long tableEntryIndex = tableTypeIndex + tableType.entriesStart;
    for (int i = 0; i < offsetArray.length; i++) {
      final long entryIndex = offsetArray[i] + tableEntryIndex;
      final ResTableEntry tableEntry = structIO.read(ResTableEntry.class, entryIndex);
      System.out.println();
      System.out.println("table type type entry " + i + ":");
      System.out.println("header: " + tableEntry);
      System.out.println("entry name: " + typeStringPool[tableEntry.key.index]);

      if (tableEntry.flags == ResTableEntry.FLAG_COMPLEX) {
        // parse ResTable_map
        final ResTableMapEntry tableMapEntry = structIO.read(ResTableMapEntry.class, entryIndex);
        System.out.println(tableMapEntry);

        int index = 0;
        for (int j = 0; j < tableMapEntry.count; j++) {
          final long tableMapIndex = index + entryIndex + tableMapEntry.size;
          ResTableMap tableMap = structIO.read(ResTableMap.class, tableMapIndex);
          System.out.println("table map " + j + ":");
          System.out.println(tableMap);

          index += StructIO.sizeOf(ResTableMap.class);
        }
      } else {
        // parse Res_value
        final int entrySize = StructIO.sizeOf(ResTableEntry.class);
        final ResValue value = structIO.read(ResValue.class, entryIndex + entrySize);
        System.out.println(value);
      }
    }

    mIndex = structIO.size();
  }

  /*
  private void parse(byte[] arsc) {
    if (mIndex >= arsc.length - 1) { return; }

    ResChunkHeader header = ResChunkHeader.valueOfBytes(arsc, mIndex);
    System.out.println();
    System.out.println("================================ " + ResourceTypes.nameOf(header.type) +
        " ================================");
    switch (header.type) {
      case ResourceTypes.RES_TABLE_TYPE:
        parseResTableType(arsc, header);
        parse(arsc);
        break;
      case ResourceTypes.RES_STRING_POOL_TYPE:
        parseStringPool(arsc, header);
        parse(arsc);
        break;
      case ResourceTypes.RES_TABLE_PACKAGE_TYPE:
        parseTablePackageType(arsc, header);
        parse(arsc);
        break;
      case ResourceTypes.RES_TABLE_TYPE_SPEC_TYPE:
        parseTableTypeSpecType(arsc, header);
        parse(arsc);
        break;
      case ResourceTypes.RES_TABLE_TYPE_TYPE:
        parseTableTypeType(arsc, header);
        parse(arsc);
        break;
      default:
    }
  }
  // */

  private void parse(StructIO structIO) throws Exception {
    if (structIO.isEof(mIndex)) { return; }

    ResChunkHeader header = structIO.read(ResChunkHeader.class, mIndex);

    System.out.println();
    System.out.println("================================ " + ResourceTypes.nameOf(header.type) +
        " ================================");
    switch (header.type) {
      case ResourceTypes.RES_TABLE_TYPE:
        parseResTableType(structIO);
        parse(structIO);
        break;
      case ResourceTypes.RES_STRING_POOL_TYPE:
        System.out.println(ResourceTypes.nameOf(header.type));

        parseStringPool(structIO);
        parse(structIO);
        break;
      case ResourceTypes.RES_TABLE_PACKAGE_TYPE:
        System.out.println(ResourceTypes.nameOf(header.type));

        parseTablePackageType(structIO);
        parse(structIO);
        break;
      case ResourceTypes.RES_TABLE_TYPE_SPEC_TYPE:
        System.out.println(ResourceTypes.nameOf(header.type));

        parseTableTypeSpecType(structIO);
        parse(structIO);
        break;
      case ResourceTypes.RES_TABLE_TYPE_TYPE:
        System.out.println(ResourceTypes.nameOf(header.type));

        parseTableTypeType(structIO);
        parse(structIO);
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
    StructIO structIO = null;
    try {
      structIO = new StructIO(file, false);
      parse(structIO);

    } catch (Exception e) {
      e.printStackTrace();

    } finally {
      closeQuietly(structIO);
    }
  }
}
