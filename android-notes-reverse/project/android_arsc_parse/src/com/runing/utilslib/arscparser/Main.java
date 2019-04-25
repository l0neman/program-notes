package com.runing.utilslib.arscparser;

import com.runing.utilslib.arscparser.core.ArscParser;
import com.runing.utilslib.arscparser.core2.ArscParser2;

public class Main {

  public static void main(String[] args) {
    new ArscParser().parse("./file/resources_gdt1.arsc");

    System.out.println();
    System.out.println("===================== NEW =====================");
    System.out.println();

    new ArscParser2().parse("./file/resources_gdt1.arsc");
  }
}
