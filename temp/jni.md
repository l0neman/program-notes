# JNI Guide

## JNI 对象的保持

在 JNI 层使用 new 操作创建 C++ 对象，需要再次使用时，则需要在 Java 对象中保存 C++ 对象的信息。那么可以采用下面的办法。

1. 在 Java 类中创建一个 long 型字段，用来存储 C++ 类对象的地址。
2. 在 JNI 层创建完 C++ 对象时，将它的地址存入 Java 类对象中。
3. 当 Java 对象再次访问 JNI 方法时，通过 jobject 中存的地址取出，转化为 C++ 对象的指针。