package com.runing.utilslib.arscparser;

import com.runing.utilslib.arscparser.core.ArscParser;
import com.runing.utilslib.arscparser.xml.XmlParser;

public class Main {

  public static void main(String[] args) {

//     parseArscFile();
    parseBinaryXmlFile();
  }

  private static void parseArscFile() {
    new ArscParser().parse("./file/resources.arsc");
  }

  private static void parseBinaryXmlFile() {
    new XmlParser().parse("./file/AM.xml");
  }

}
