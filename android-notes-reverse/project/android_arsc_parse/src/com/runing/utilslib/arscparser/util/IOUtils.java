package com.runing.utilslib.arscparser.util;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.FileInputStream;
import java.io.IOException;

public class IOUtils {

  private static void closeQuietly(Closeable closeable) {
    if (closeable != null) {
      try {
        closeable.close();
      }
      catch (RuntimeException e) {
        throw new RuntimeException(e);
      }
      catch (Exception ignore) { }
    }
  }

  public static byte[] fileToBytes(String path) throws IOException {
    FileInputStream fis = null;
    ByteArrayOutputStream bos = null;
    try {
      fis = new FileInputStream(path);
      bos = new ByteArrayOutputStream();
      byte[] buffer = new byte[1024];
      int l;
      while ((l = fis.read(buffer)) != -1) {
        bos.write(buffer, 0, l);
      }
      return bos.toByteArray();
    }
    finally {
      closeQuietly(bos);
      closeQuietly(fis);
    }
  }
}
