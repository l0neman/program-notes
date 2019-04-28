package com.runing.utilslib.arscparser;

import com.runing.utilslib.arscparser.core.ArscParser2;

public class Main {

  public static void main(String[] args) {
//    new ArscParser().parse("./file/resources_gdt.arsc");

    System.out.println();
    System.out.println("===================== NEW =====================");
    System.out.println();

    new ArscParser2().parse("./file/resources_gdt.arsc");
  }
}
