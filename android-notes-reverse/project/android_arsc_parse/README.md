# ArscParser

Arsc 解析器，包含 Arsc 文件解析和二进制 Xml 文件解析。

示例入口：[Main.java](./src/com/runing/utilslib/arscparser/Main.java)

## ArscParser

Arsc 文件解析器。 

源码 [AXmlParser.java](./src/com/runing/utilslib/arscparser/xml/AXmlParser.java)

## AXmlParser

二进制 Xml 解析器，可将 APK 包中的二进制 Xml 文件输出为文本。

将内部的 `DEBUG_INFO` 和 `PARSE_INFO` 设置为 `false`，`XML_PRINT` 设置为 `true` 即可输出纯 Xml 文档。 

使用方法：

```java
AXmlParser xmlParser = new AXmlParser();
xmlParser.parse("./file/AndroidManifest.xml");
```

## AXmlParser export

对外提供接口的二进制 Xml 文件解析器，解析方法类似 Sax 解析器。 

源码 [AXmlParser.java](./src/com/runing/utilslib/arscparser/xml/export/AXmlParser.java)

```java
...
// 创建解析器对象。
AXmlParser xmlParser = new AXmlParser();

// 设置解析处理器。
xmlParser.setHandler(new AXmlParser.Handler() {
  @Override
  protected void startNamespace(String ns, String nsUri) {
    System.out.println("start ns :" + ns + " nsUri: " + nsUri);
  }

  @Override
  protected void startElement(String ns, String nsUri, String elementName) {
    System.out.println("start e: " + elementName + " ns: " + ns);
  }

  @Override
  protected void onData(String data) {
    System.out.println("data: " + data);
  }

  @Override
  protected void onAttribute(String ns, String nsUri, String attrName, String attrValue) {
    System.out.println("attr: " + attrName + " value: " + attrValue + " ns: " + ns);
  }

  @Override
  protected void endElement(String ns, String nsUri, String elementName) {
    System.out.println("end e: " + elementName + " ns: " + ns);
  }

  @Override
  protected void endNamespace(String ns, String nsUri) {
    System.out.println("end ns :" + ns + " nsUri: " + nsUri);
  }
});

// 开始解析 Xml 文件。
// xmlParser.parse("./file/AndroidManifest.xml");
xmlParser.parse("./file/drawable.xml");
```