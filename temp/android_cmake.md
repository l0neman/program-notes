# Android CMake

- cmake_minimum_required

指定构建 native 库的最小 CMake 版本。

```cmake
cmake_minimum_required(VERSION 3.4.1)
```

- add_library

指定库名称，设置库定义为 `STATIC` 或 `SHARED`，并提供源代码的相对路径，可以定义多个库，CMake 将自动构建它们，Gradle 会自动将共享库打包到 APK 中。

```cmake
add_library(# Specifies the name of the library.
			native-lib
			# Sets the library as a shared library.
			SHARED
			# Provides a relative path to your source file(s).
			src/main/cpp/native-lib.cpp)
```

- include_directories

为了确保 CMake 可在编译时确定头文件，需要指定头文件的路径。

```cmake
include_directories(src/main/cpp/include)
```

- find_library

使用 `find_library` 命令添加引用的 NDK 库，并制定一个库名称，可使用此名称在构建脚本的其他部分引用这个 NDK 库，以下为添加 Android 日志库的示例：

```cmake
find_library(# Defines the name of the path variable that stores the
             # location of the NDK library.
			 log-lib
			 # Specifies the name of the NDK library that
             # CMake needs to locate.
             log)
```

- target_link_libraries

为了确保自己的库可以调用 NDK 库，需要使用 `target_link_libraries` 命令建立关联。

```cmake
# Links your native library against one or more other native libraries.
target_link_libraries(# Specifies the target library.
                      native-lib
                      # Links the log library to the target library.
                      ${log-lib})
```

还可以以源代码的形式引用一些库，例如指定 `android_native_app_glue.c` 库，它会将 `NativeActivity` 生命周期事件和触摸输入置于静态库中并将静态库关联到 `native-lib`：

```cmake
dd_library(app-glue
           STATIC
           ${ANDROID_NDK}/sources/android/native_app_glue/android_native_app_glue.c)

# You need to link static libraries against your shared native library.
target_link_libraries( native-lib app-glue ${log-lib} )
```

如果需要添加其他预构建库，需要使用 `IMPORT` 标志，然后使用 `set_target_properties` 命令指定库路径。

```cmake
add_library(imported-lib
            SHARED
            IMPORTED)
set_target_properties(# Specifies the target library.
                      imported-lib
                      # Specifies the parameter you want to define.
                      PROPERTIES IMPORTED_LOCATION
                      # Provides the path to the library you want to import.
                      imported-lib/src/${ANDROID_ABI}/libimported-lib.so )
target_link_libraries( native-lib imported-lib app-glue ${log-lib} )
```

使用 `ANDROID_ABI` 变量适配对应的 CPU 架构，可以使库充分利用特定的 CPU 架构，又能仅使用所需的库版本，避免了为库的每个版本编写多个命令。

## 参考

[向您的项目添加 C 和 C++ 代码](https://developer.android.google.cn/studio/projects/add-native-code.html)

