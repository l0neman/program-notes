package com.runing.utilslib.arscparser;

import com.runing.utilslib.arscparser.core.ArscParser;
import com.runing.utilslib.arscparser.xml.AXmlParser;
import com.runing.utilslib.arscparser.xml.AXmlParserX;
import sun.misc.Unsafe;

import java.io.File;
import java.io.IOException;

public class Main {

  public static void main(String[] args) {

    // 解析 arsc 文件。
//     parseArscFile();

    // 解析二进制 xml 文件。
    parseBinaryXmlFile();
  }

  private static void parseArscFile() {
    try {
      new ArscParser().parse("./file/app.arsc");
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private static void parseBinaryXmlFile() {

    File inDir = new File("./file/in/");
    final File[] files = inDir.listFiles();
    for(File file : files) {
      System.out.println(file.getName());
    }

//    try {
//      new AXmlParserX().parse("./file/in/csjAM.xml", "./file/out/csjAM.xml");
//    } catch (IOException e) {
//      e.printStackTrace();
//    }

    /*
    try {
      new AXmlParser().parse("./file/out/csjAM.xml");
    } catch (IOException e) {
      e.printStackTrace();
    }
    // */
  }
}
