package com.runing.utilslib.arscparser;

import com.runing.utilslib.arscparser.core.ArscParser;
import com.runing.utilslib.arscparser.xml.export.AXmlParser;

public class Main {

  public static void main(String[] args) {

    // 解析 arsc 文件。
//     parseArscFile();

    // 解析二进制 xml 文件。
    parseBinaryXmlFile();
  }

  private static void parseArscFile() {
    new ArscParser().parse("./file/resources_gdt1.arsc");
  }

  private static void parseBinaryXmlFile() {
    new AXmlParser().parse("./file/AM.xml");
  }

}
