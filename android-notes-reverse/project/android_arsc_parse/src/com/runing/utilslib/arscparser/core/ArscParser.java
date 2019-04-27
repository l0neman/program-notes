package com.runing.utilslib.arscparser.core;

import com.runing.utilslib.arscparser.type.*;
import com.runing.utilslib.arscparser.util.IOUtils;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * <pre><code>
 * 资源表头
 * RES_TABLE_TYPE headerSize(2) size(4) package(4)
 *
 *   字符串资源池
 *   RES_STRING_POOL_TYPE(2) headerSize(2) size(4) stringCount(4) styleCount(4) flags(4) stringStart(4) styleStart(4)
 *   stringOffsetArray - 字符串偏移数组。
 *   styleOffsetArray  - style 偏移数组。
 *   stringPool        - 字符串池。
 *   stylePool         - style 池。
 *
 *   资源表每个包的元信息
 *   RES_TABLE_PACKAGE_TYPE(2) headerSize(2) size(4) id(2) name(256)
 *   ResourcesTypePoolOffset(4) lastPublicType(4) ResourcesKeyPollOffset(4) lastPublicKey(4)
 *   ResourcesTypeStringPool - 资源类型字符串池。
 *   ResourcesKeyStringPool  - 资源关键字字符串池。
 *
 *   资源类型规范信息
 *   RES_TABLE_TYPE_SPEC_TYPE(2) headerSize(2) size(4) id(1) res0(1) res1(2) entryCount(4)
 *   ResourcesTypeSpecArray - 资源类型规范数组。
 *
 *   资源类型信息
 *   RES_TABLE_TYPE_TYPE headerSize(2) size(4) id(1) res0(1) res1(2) entryCount(4) entriesStart(4)
 *   config(48)    - 资源配置信息。
 *   ResourcesEntryOffsetArray - ResTable_entry 偏移数组。
 *   ResourceEntryArray - 资源实体数组。
 * </code></pre>
 */
@SuppressWarnings("ALL")
public class ArscParser {

  private int mIndex;

  private String [] typeStringPool;

  private void parseResTableType(byte[] arsc, ResChunkHeader header) {
    final ResTableHeader tableType = ResTableHeader.valueOfBytes(arsc, header);
    System.out.println("resource table header:");
    System.out.println(tableType);

    // 向下移动资源表头部的大小。
    mIndex += tableType.header.headerSize;
  }

  private void parseStringPool(byte[] arsc, ResChunkHeader header) {
    final int stringPoolIndex = mIndex;
    ResStringPoolHeader stringPoolHeader = ResStringPoolHeader.valueOfBytes(arsc, header, stringPoolIndex);
    System.out.println("string pool header:");
    System.out.println(stringPoolHeader);

    StringPoolChunkParser stringPoolChunkParser = new StringPoolChunkParser();
    stringPoolChunkParser.parseStringPoolChunk(arsc, stringPoolHeader, stringPoolIndex);

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

  private void parseTablePackageType(byte[] arsc, ResChunkHeader header) {
    final int tablePackageIndex = mIndex;
    final ResTablePackage tablePackage = ResTablePackage.valueOfBytes(arsc, header, tablePackageIndex);
    System.out.println("table package type:");
    System.out.println(tablePackage);

    // 向下移动资源表元信息头部的大小。
    mIndex += tablePackage.header.headerSize;
  }

  private void parseTableTypeSpecType(byte[] arsc, ResChunkHeader header) {
    final int typeSpecIndex = mIndex;
    ResTableTypeSpec tableTypeSpec = ResTableTypeSpec.valueOfBytes(arsc, header, typeSpecIndex);
    System.out.println("table type spec type:");
    System.out.println(tableTypeSpec);

    int[] entryArray = TableTypeChunkParser.parseSpecEntryArray(arsc, tableTypeSpec, typeSpecIndex);
    System.out.println();
    System.out.println("table type spec type entry array:");
    System.out.println(Arrays.toString(entryArray));

    // 向下移动资源表类型规范内容的大小。
    mIndex += tableTypeSpec.header.size;
  }

  private void parseTableTypeType(byte[] arsc, ResChunkHeader header) {
    final int tableTypeIndex = mIndex;
    final ResTableType tableType = ResTableType.valueOfBytes(arsc, header, tableTypeIndex);
    System.out.println("table type type:");
    System.out.println(tableType);

    int[] offsetArray = TableTypeChunkParser.parseTypeOffsetArray(arsc, tableType, tableTypeIndex);
    System.out.println();
    System.out.println("offset array:");
    System.out.println(Arrays.toString(offsetArray));

    final int tableEntryIndex = tableTypeIndex + tableType.entriesStart;
    for (int i = 0; i < offsetArray.length; i++) {
      final int entryIndex = offsetArray[i] + tableEntryIndex;
      final ResTableEntry tableEntry = ResTableEntry.valueOfBytes(arsc, entryIndex);
      System.out.println();
      System.out.println("table type type entry " + i + ":");
      System.out.println("header: " + tableEntry);
      System.out.println("entry name: " + typeStringPool[tableEntry.key.index]);

      if (tableEntry.flags == ResTableEntry.FLAG_COMPLEX) {
        // parse ResTable_map
        final ResTableMapEntry tableMapEntry = ResTableMapEntry.valueOfBytes(arsc, entryIndex);
        System.out.println(tableMapEntry);

        int index = 0;
        for (int j = 0; j < tableMapEntry.count; j++) {
          final int tableMapIndex = index + entryIndex + tableMapEntry.size;
          ResTableMap tableMap = ResTableMap.valueOfBytes(arsc, tableMapIndex);
          System.out.println("table map " + j + ":");
          System.out.println(tableMap);

          index += ResTableMap.BYTES;
        }
      }
      else {
        // parse Res_value
        final ResValue value = ResValue.valueOfBytes(arsc, entryIndex + ResTableEntry.BYTES);
        System.out.println(value);
      }
    }

    mIndex += arsc.length;
  }

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

  public void parse(String file) {
    try {
//      parse(IOUtils.fileToBytes(file));
      parse(IOUtils.fileToBytes(file));
    }
    catch (IOException e) {
      e.printStackTrace();
    }
  }
}
