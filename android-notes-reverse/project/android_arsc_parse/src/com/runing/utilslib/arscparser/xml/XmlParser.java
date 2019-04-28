package com.runing.utilslib.arscparser.xml;

import com.runing.utilslib.arscparser.core.StringPoolChunkParser;
import com.runing.utilslib.arscparser.type.*;
import com.runing.utilslib.arscparser.util.Formatter;
import com.runing.utilslib.arscparser.util.objectio.ObjectIO;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@SuppressWarnings("ALL")
public class XmlParser {

  private int mIndex;
  private StringPoolChunkParser stringPoolChunkParser;

  private void parseXMLTreeHeader(ObjectIO objectIO) throws IOException {
    ResXMLTreeHeader xmlTreeHeader = objectIO.read(ResXMLTreeHeader.class, mIndex);

    System.out.println("ResXMLTreeHeader:");
    System.out.println(xmlTreeHeader);

    mIndex += xmlTreeHeader.header.headerSize;
  }

  private void parseStringPool(ObjectIO objectIO) throws IOException {
    final long stringPoolIndex = mIndex;
    ResStringPoolHeader stringPoolHeader = objectIO.read(ResStringPoolHeader.class, stringPoolIndex);
    System.out.println("string pool header:");
    System.out.println(stringPoolHeader);

    stringPoolChunkParser = new StringPoolChunkParser();
    stringPoolChunkParser.parseStringPoolChunk(objectIO, stringPoolHeader, stringPoolIndex);

    System.out.println();
    System.out.println("string index array:");
//    System.out.println(Arrays.toString(stringPoolChunkParser.getStringIndexArray()));

    System.out.println();
    System.out.println("style index array:");
    System.out.println(Arrays.toString(stringPoolChunkParser.getStyleIndexArray()));

    final String[] stringPool = stringPoolChunkParser.getStringPool();

    System.out.println();
    System.out.println("string pool:");
    /*
    System.out.print('[');
    for (String str : stringPool) {
      System.out.print(Formatter.trim(str) + ", ");
    }
    System.out.print(']');
    // */

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

  private void parseResourceIds(ObjectIO objectIO) throws IOException {
    ResChunkHeader header = objectIO.read(ResChunkHeader.class, mIndex);
    // 解析 xml 文件中出现的资源 ID。
    final int size = header.size;
    final int count = size / Integer.BYTES;

    int index = mIndex + Integer.BYTES * 2;
    for (int i = 0; i < count; i++) {
      System.out.println("resId: " + Formatter.toHex(Formatter.fromInt(
          objectIO.readInt(index)
      )));
      index += i * Integer.BYTES;
    }

    mIndex += header.size;
  }

  private void parseStartNamespace(ObjectIO objectIO) throws IOException {
    ResXMLTreeNode xmlTreeNode = objectIO.read(ResXMLTreeNode.class, mIndex);

    System.out.println("ResXMLTreeNode:");
    System.out.println(xmlTreeNode);

    int namespaceExtIndex = mIndex + xmlTreeNode.header.headerSize;

    final ResXMLTreeNamespaceExt xmlTreeNamespaceExt =
        objectIO.read(ResXMLTreeNamespaceExt.class, namespaceExtIndex);

    System.out.println();
    System.out.println("ResXMLTreeNamespaceExt:");
    System.out.println(xmlTreeNamespaceExt);
    String namespace = stringPoolChunkParser.getStringPool()[xmlTreeNamespaceExt.prefix.index];
    System.out.println("namepsace: " + Formatter.trim(namespace));
    String namespaceUri = stringPoolChunkParser.getStringPool()[xmlTreeNamespaceExt.uri.index];
    System.out.println("namepsace uri: " + Formatter.trim(namespaceUri));

    mIndex += xmlTreeNode.header.size;
  }

  private void parse(ObjectIO objectIO) throws IOException {
    if (objectIO.isEof(mIndex)) {
      return;
    }

    ResChunkHeader header = objectIO.read(ResChunkHeader.class, mIndex);

    System.out.println();
    System.out.println("================================ " + ResourceTypes.nameOf(header.type) +
        " ================================");
    System.out.println(header);
    switch (header.type) {
      case ResourceTypes.RES_XML_TYPE:
        parseXMLTreeHeader(objectIO);
        parse(objectIO);
        break;

      case ResourceTypes.RES_STRING_POOL_TYPE:
        parseStringPool(objectIO);
        parse(objectIO);
        break;

      case ResourceTypes.RES_XML_RESOURCE_MAP_TYPE:
        parseResourceIds(objectIO);
        parse(objectIO);
        break;

//      case ResourceTypes.RES_XML_START_NAMESPACE_TYPE:
//
//        parseStartNamespace(objectIO);
//        parse(objectIO);
//        break;

      case ResourceTypes.RES_XML_START_ELEMENT_TYPE:
        String[] stringPool = stringPoolChunkParser.getStringPool();
        ResXMLTreeNode xmlTreeNode = null;

        do {
          xmlTreeNode = objectIO.read(ResXMLTreeNode.class, mIndex);
          System.out.println(xmlTreeNode);

          switch (xmlTreeNode.header.type) {
            case ResourceTypes.RES_XML_START_ELEMENT_TYPE:

              int index = mIndex + xmlTreeNode.header.headerSize;

              ResXMLTreeAttrExt xmlTreeAttrExt = objectIO.read(ResXMLTreeAttrExt.class, index);
//              System.out.println("ResXMLTreeAttrExt:");
//              System.out.println(xmlTreeAttrExt);
              if(xmlTreeAttrExt.ns.index != -1) {
                System.out.println("attr ns: " + stringPool[xmlTreeAttrExt.ns.index]);
              }
              System.out.println("attr name: " + stringPool[xmlTreeAttrExt.name.index]);

              index += ObjectIO.sizeOf(ResXMLTreeAttrExt.class);

              for(int i = 0; i < xmlTreeAttrExt.attributeCount; i++) {
                ResXMLTreeAttribute attribute = objectIO.read(ResXMLTreeAttribute.class, index);
                System.out.println("attribute:");
                System.out.println(attribute);
              }
              System.out.println();
              break;

            case ResourceTypes.RES_XML_CDATA_TYPE:
              break;

            case ResourceTypes.RES_XML_END_ELEMENT_TYPE:
              break;

            default:
              break;
          }

          mIndex += xmlTreeNode.header.size;
        } while (!objectIO.isEof(mIndex));

        break;

//      case ResourceTypes.RES_XML_CDATA_TYPE:
//
//        mIndex += header.size;
//        parse(objectIO);
//        break;
//
//      case ResourceTypes.RES_XML_END_ELEMENT_TYPE:
//
//        mIndex += header.size;
//        parse(objectIO);
//        break;

      case ResourceTypes.RES_XML_END_NAMESPACE_TYPE:

        mIndex += header.size;
//        parse(objectIO);
        break;

      default:
        break;
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
      objectIO = new ObjectIO(file);
      parse(objectIO);
    } catch (IOException e) {
      e.printStackTrace();
    } finally {
      closeQuietly(objectIO);
    }
  }
}
