package com.runing.utilslib.arscparser;

import com.runing.utilslib.arscparser.core.ArscParser;
import com.runing.utilslib.arscparser.xml.AXmlPrinter;

import java.io.IOException;

public class Main {

  public static void main(String[] args) {

    // 解析 arsc 文件。
    parseArscFile();

    // 解析二进制 xml 文件。
    // parseBinaryXmlFile();
  }

  private static void parseArscFile() {
    try {
      new ArscParser().parse("./file/app.arsc");
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private static void parseBinaryXmlFile() {
    try {
      new AXmlPrinter().print("./file/AM.xml");
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
