package com.runing.utilslib.arscparser;

import com.runing.utilslib.arscparser.core.ArscParser;
import com.runing.utilslib.arscparser.core2.ArscParser2;
import com.runing.utilslib.arscparser.type2.ResTableConfig;
import com.runing.utilslib.arscparser.util.objectio.ObjectIO;

public class Main {

  public static void main(String[] args) {
//    new ArscParser().parse("./file/resources_gdt.arsc");

    System.out.println();
    System.out.println("===================== NEW =====================");
    System.out.println();

    new ArscParser2().parse("./file/resources_gdt.arsc");

//    System.out.println(ObjectIO.sizeOf(ResTableConfig.class));
  }
}
