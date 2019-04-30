package com.runing.utilslib.arscparser.xml.export;

import com.runing.utilslib.arscparser.xml.export.type.*;
import com.runing.utilslib.arscparser.xml.export.util.objectio.ObjectInput;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@SuppressWarnings("ALL")
public class AXmlParser {
  private int mIndex;
  private String[] mStringPool;
  private Map<String, String> mNamespaceMap = new HashMap<>();
  private Handler mHandler;

  /** 解析处理器 */
  public static abstract class Handler {
    /**
     * 命名空间解析起始点。
     *
     * @param ns    命名空间名称。
     * @param nsUri 命名空间 uri。
     */
    protected void startNamespace(String ns, String nsUri) {}

    /**
     * 元素解析起始点。
     *
     * @param ns          元素命名空间名称（可能为 null）。
     * @param nsUri       元素命名空间 uri（可能为 null）。
     * @param elementName 元素名称。
     */
    protected void startElement(String ns, String nsUri, String elementName) {}

    /**
     * 元素标签中包含的数据。
     *
     * @param data 字符串数据。
     */
    protected void onData(String data) {}

    /**
     * 元素属性解析数据回调。
     *
     * @param ns        属性命名空间名称（可能为 null）。
     * @param nsUri     属性命名空间 uri（可能为 null）。
     * @param attrName  属性名。
     * @param attrValue 属性值。
     */
    protected void onAttribute(String ns, String nsUri, String attrName, String attrValue) {}

    /**
     * 元素解析终止点。
     *
     * @param ns          元素命名空间名称（可能为 null）。
     * @param nsUri       元素命名空间 uri（可能为 null）。
     * @param elementName 元素名称。
     */
    protected void endElement(String ns, String nsUri, String elementName) {}

    /**
     * 命名空间解析终止点。
     *
     * @param ns    命名空间名称。
     * @param nsUri 命名空间 uri。
     */
    protected void endNamespace(String ns, String nsUri) {}
  }

  private void parseXMLTreeHeader(ObjectInput ObjectInput) throws IOException {
    ResXMLTreeHeader xmlTreeHeader = ObjectInput.read(ResXMLTreeHeader.class, mIndex);

    mIndex += xmlTreeHeader.header.headerSize;
  }

  private void parseStringPool(ObjectInput objectInput) throws IOException {
    final long stringPoolIndex = mIndex;
    ResStringPoolHeader stringPoolHeader = objectInput.read(ResStringPoolHeader.class, stringPoolIndex);

    StringPoolChunkParser stringPoolChunkParser = new StringPoolChunkParser();
    stringPoolChunkParser.parseStringPoolChunk(objectInput, stringPoolHeader, stringPoolIndex);

    mStringPool = stringPoolChunkParser.getStringPool();

    // 向下移动字符串池的大小。
    mIndex += stringPoolHeader.header.size;
  }

  private void parseResourceIds(ObjectInput ObjectInput) throws IOException {
    ResChunkHeader header = ObjectInput.read(ResChunkHeader.class, mIndex);
    // 解析 xml 文件中出现的资源 ID。
    /*
    final int size = header.size;
    final int count = size / Integer.BYTES;

    int index = mIndex + Integer.BYTES * 2;
    // */

    mIndex += header.size;
  }

  private void parseStartNamespace(ObjectInput ObjectInput) throws IOException {
    ResXMLTreeNode node = ObjectInput.read(ResXMLTreeNode.class, mIndex);
    int namespaceExtIndex = mIndex + node.header.headerSize;

    final ResXMLTreeNamespaceExt namespaceExt =
        ObjectInput.read(ResXMLTreeNamespaceExt.class, namespaceExtIndex);
    String namespace = mStringPool[namespaceExt.prefix.index];
    String namespaceUri = mStringPool[namespaceExt.uri.index];

    mHandler.startNamespace(namespace, namespaceUri);
    mNamespaceMap.put(namespaceUri, namespace);

    mIndex += node.header.size;
  }

  private void parseStartElement(ObjectInput objectInput) throws IOException {
    ResXMLTreeNode node = objectInput.read(ResXMLTreeNode.class, mIndex);
    int index = mIndex + node.header.headerSize;

    ResXMLTreeAttrExt attrExt = objectInput.read(ResXMLTreeAttrExt.class, index);
    String ns = attrExt.ns.index != -1 ?
        mStringPool[attrExt.ns.index] : null;
    final String elementName = mStringPool[attrExt.name.index];

    mHandler.startElement(mNamespaceMap.get(ns), ns, elementName);

    index += ObjectInput.sizeOf(ResXMLTreeAttrExt.class);

    for (int i = 0; i < attrExt.attributeCount; i++) {
      ResXMLTreeAttribute attr = objectInput.read(ResXMLTreeAttribute.class, index);

      final String nsUri = attr.ns.index != -1 ?
          mStringPool[attr.ns.index] : null;
      final String attrName = mStringPool[attr.name.index];
      final String attrText = attr.rawValue.index != -1 ?
          mStringPool[attr.rawValue.index] : null;
      final String attrValue = attrText == null ? String.valueOf(attr.typeValue.data) : attrText;
      String nsName = mNamespaceMap.get(nsUri);

      mHandler.onAttribute(nsName, nsUri, attrName, attrValue);

      index += ObjectInput.sizeOf(ResXMLTreeAttribute.class);
    }

    mIndex += node.header.size;
  }

  private void parseCData(ObjectInput objectInput) throws IOException {
    ResXMLTreeNode node = objectInput.read(ResXMLTreeNode.class, mIndex);
    int index = mIndex + node.header.headerSize;

    ResXMLTreeCdataExt cdataExt = objectInput.read(ResXMLTreeCdataExt.class, index);
    final String cdata = mStringPool[cdataExt.data.index];

    mHandler.onData(cdata);

    mIndex += node.header.size;
  }

  private void parseEndElement(ObjectInput objectInput) throws IOException {
    ResXMLTreeNode node = objectInput.read(ResXMLTreeNode.class, mIndex);
    int index = mIndex + node.header.headerSize;

    ResXMLTreeEndElementExt endElementExt = objectInput.read(ResXMLTreeEndElementExt.class, index);
    final String nsUri = endElementExt.ns.index != -1 ?
        mStringPool[endElementExt.ns.index] : "";
    final String elementName = mStringPool[endElementExt.name.index];

    mHandler.endElement(mNamespaceMap.get(nsUri), nsUri, elementName);

    mIndex += node.header.size;
  }

  private void parseEndNamespace(ObjectInput objectInput) throws IOException {
    ResXMLTreeNode node = objectInput.read(ResXMLTreeNode.class, mIndex);
    int index = mIndex + node.header.headerSize;

    ResXMLTreeNamespaceExt namespaceExt = objectInput.read(ResXMLTreeNamespaceExt.class, index);
    String namespace = mStringPool[namespaceExt.prefix.index];
    String namespaceUri = mStringPool[namespaceExt.uri.index];

    mHandler.endNamespace(namespace, namespaceUri);

    mIndex += node.header.size;
  }

  private void parse(ObjectInput objectInput) throws IOException {
    while (!objectInput.isEof(mIndex)) {
      ResChunkHeader header = objectInput.read(ResChunkHeader.class, mIndex);

      switch (header.type) {
        case ResourceTypes.RES_XML_TYPE:
          parseXMLTreeHeader(objectInput);
          break;

        case ResourceTypes.RES_STRING_POOL_TYPE:
          parseStringPool(objectInput);
          break;

        case ResourceTypes.RES_XML_RESOURCE_MAP_TYPE:
          parseResourceIds(objectInput);
          break;

        case ResourceTypes.RES_XML_START_NAMESPACE_TYPE:
          parseStartNamespace(objectInput);
          break;

        case ResourceTypes.RES_XML_START_ELEMENT_TYPE:
          parseStartElement(objectInput);
          break;

        case ResourceTypes.RES_XML_CDATA_TYPE:
          parseCData(objectInput);
          break;

        case ResourceTypes.RES_XML_END_ELEMENT_TYPE:
          parseEndElement(objectInput);
          break;

        case ResourceTypes.RES_XML_END_NAMESPACE_TYPE:
          parseEndNamespace(objectInput);
          break;

        default:
          break;
      }
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

  public void setHandler(Handler handler) {
    this.mHandler = handler;
  }

  public void parse(String file) {
    if (mHandler == null) {
      throw new NullPointerException("handler is null");
    }

    ObjectInput objectInput = null;
    try {
      objectInput = new ObjectInput(file);
      parse(objectInput);
    } catch (IOException e) {
      e.printStackTrace();
    } finally {
      closeQuietly(objectInput);
    }
  }
}
