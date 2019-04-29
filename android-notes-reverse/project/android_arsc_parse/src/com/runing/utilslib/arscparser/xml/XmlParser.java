package com.runing.utilslib.arscparser.xml;

import com.runing.utilslib.arscparser.core.StringPoolChunkParser;
import com.runing.utilslib.arscparser.type.*;
import com.runing.utilslib.arscparser.util.Formatter;
import com.runing.utilslib.arscparser.util.objectio.ObjectIO;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SuppressWarnings("ALL")
public class XmlParser {

  private int mIndex;
  private StringPoolChunkParser stringPoolChunkParser;
  private XmlEditor xmlEditor = new XmlEditor();
  private Map<String, String> namespaceMap = new HashMap<>();

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
    // System.out.println(Arrays.toString(stringPoolChunkParser.getStringIndexArray()));

    System.out.println();
    System.out.println("style index array:");
    System.out.println(Arrays.toString(stringPoolChunkParser.getStyleIndexArray()));

    final String[] stringPool = stringPoolChunkParser.getStringPool();

    System.out.println();
    System.out.println("string pool:");
    // System.out.println(Arrays.toString(stringPoolChunkParser.getStringPool()));

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
          objectIO.readInt(index), true
      )));
      index += i * Integer.BYTES;
    }

    mIndex += header.size;
  }

  private void parseStartNamespace(ObjectIO objectIO) throws IOException {
    ResXMLTreeNode node = objectIO.read(ResXMLTreeNode.class, mIndex);
    final String[] stringPool = stringPoolChunkParser.getStringPool();
    int namespaceExtIndex = mIndex + node.header.headerSize;

    System.out.println("node:");
    System.out.println(node);

    final ResXMLTreeNamespaceExt namespaceExt =
        objectIO.read(ResXMLTreeNamespaceExt.class, namespaceExtIndex);

    System.out.println();
    System.out.println("namespace:");
    System.out.println(namespaceExt);
    String namespace = stringPool[namespaceExt.prefix.index];
    System.out.println("namepsace name: " + namespace);
    String namespaceUri = stringPool[namespaceExt.uri.index];
    System.out.println("namepsace uri: " + namespaceUri);

    xmlEditor.addNamespace(namespace, namespaceUri);
    namespaceMap.put(namespaceUri, namespace);

    mIndex += node.header.size;
  }

  private void parseStartElement(ObjectIO objectIO) throws IOException {
    String[] stringPool = stringPoolChunkParser.getStringPool();
    ResXMLTreeNode xmlTreeNode = null;

    xmlTreeNode = objectIO.read(ResXMLTreeNode.class, mIndex);
    int index = mIndex + xmlTreeNode.header.headerSize;

    // System.out.println(xmlTreeNode);
    ResXMLTreeAttrExt attrExt = objectIO.read(ResXMLTreeAttrExt.class, index);

    // System.out.println(attrExt);
    System.out.println("element:");
    System.out.println("element ns: " + (attrExt.ns.index != -1 ?
        stringPool[attrExt.ns.index] : null));
    final String elementName = stringPool[attrExt.name.index];

    System.out.println("element name: " + elementName);

    xmlEditor.openElement(elementName);

    index += ObjectIO.sizeOf(ResXMLTreeAttrExt.class);

    for (int i = 0; i < attrExt.attributeCount; i++) {
      ResXMLTreeAttribute attr = objectIO.read(ResXMLTreeAttribute.class, index);

      // System.out.println(attr);
      System.out.println("attr:");

      final String namespace = attr.ns.index != -1 ?
          stringPool[attr.ns.index] : null;

      System.out.println("attr ns: " + namespace);

      final String attrName = stringPool[attr.name.index];

      System.out.println("attr name: " + attrName);

      final String attrText = attr.rawValue.index != -1 ?
          stringPool[attr.rawValue.index] : null;

      System.out.println("attr text: " + attrText);

      final String attrValue = attr.typeValue.dataStr();

      System.out.println("attr value: " + attr.typeValue);

      String nsPrefixx = namespaceMap.get(namespace);

      nsPrefixx = nsPrefixx == null ? "" : nsPrefixx + ":";
      xmlEditor.addAttribute(nsPrefixx + attrName, attrText != null ?
          attrText : attrValue);

      index += ObjectIO.sizeOf(ResXMLTreeAttribute.class);
    }

    mIndex += xmlTreeNode.header.size;
  }

  private void parseCData(ObjectIO objectIO) throws IOException {
    String[] stringPool = stringPoolChunkParser.getStringPool();
    ResXMLTreeNode xmlTreeNode = objectIO.read(ResXMLTreeNode.class, mIndex);
    int index = mIndex + xmlTreeNode.header.headerSize;

    ResXMLTreeCdataExt cdataExt = objectIO.read(ResXMLTreeCdataExt.class, index);

    System.out.println("cdata:");
    final String cdata = stringPool[cdataExt.data.index];

    System.out.println(cdata);
    xmlEditor.addData(cdata);

    mIndex += xmlTreeNode.header.size;
  }

  private void parseEndElement(ObjectIO objectIO) throws IOException {
    String[] stringPool = stringPoolChunkParser.getStringPool();
    ResXMLTreeNode xmlTreeNode = objectIO.read(ResXMLTreeNode.class, mIndex);
    int index = mIndex + xmlTreeNode.header.headerSize;

    ResXMLTreeEndElementExt endElementExt = objectIO.read(ResXMLTreeEndElementExt.class, index);
    System.out.println("element end:");
    System.out.println("element end ns: " + (endElementExt.ns.index != -1 ?
        stringPool[endElementExt.ns.index] : ""));
    final String elementName = stringPool[endElementExt.name.index];

    System.out.println("element end name: " + elementName);

    xmlEditor.closeElement(elementName);

    mIndex += xmlTreeNode.header.size;
  }

  private void parseEndNamespace(ObjectIO objectIO) throws IOException {
    String[] stringPool = stringPoolChunkParser.getStringPool();
    ResXMLTreeNode xmlTreeNode = objectIO.read(ResXMLTreeNode.class, mIndex);
    int index = mIndex + xmlTreeNode.header.headerSize;

    ResXMLTreeNamespaceExt namespaceExt = objectIO.read(ResXMLTreeNamespaceExt.class, index);

    System.out.println();
    System.out.println("namespace: end");
    System.out.println(namespaceExt);
    String namespace = stringPool[namespaceExt.prefix.index];
    System.out.println("namepsace end name: " + namespace);
    String namespaceUri = stringPool[namespaceExt.uri.index];
    System.out.println("namepsace end uri: " + namespaceUri);

    mIndex += xmlTreeNode.header.size;
  }

  private void parse(ObjectIO objectIO) throws IOException {
    while (!objectIO.isEof(mIndex)) {
      ResChunkHeader header = objectIO.read(ResChunkHeader.class, mIndex);

      System.out.println();
      System.out.println("================================ " + ResourceTypes.nameOf(header.type) +
          " ================================");
      System.out.println(header);
      switch (header.type) {
        case ResourceTypes.RES_XML_TYPE:
          parseXMLTreeHeader(objectIO);
          break;

        case ResourceTypes.RES_STRING_POOL_TYPE:
          parseStringPool(objectIO);
          break;

        case ResourceTypes.RES_XML_RESOURCE_MAP_TYPE:
          parseResourceIds(objectIO);
          break;

        case ResourceTypes.RES_XML_START_NAMESPACE_TYPE:
          parseStartNamespace(objectIO);
          break;

        case ResourceTypes.RES_XML_START_ELEMENT_TYPE:
          parseStartElement(objectIO);
          break;

        case ResourceTypes.RES_XML_CDATA_TYPE:
          parseCData(objectIO);
          break;

        case ResourceTypes.RES_XML_END_ELEMENT_TYPE:
          parseEndElement(objectIO);
          break;

        case ResourceTypes.RES_XML_END_NAMESPACE_TYPE:
          parseEndNamespace(objectIO);
          break;

        default:
          break;
      }
    }

    System.out.println();
    System.out.println(xmlEditor.print());
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
