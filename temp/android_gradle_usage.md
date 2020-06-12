# Gradle 的使用

- [常用命令](#常用命令)
- [android 配置](#android-配置)
- [自定义 task](#自定义-task)
- [Android tasks](#android-tasks)

一般使用 gradlew 命令替代 gradle 命令，项目中的 gradlew 脚本可自动下载项目对应版本的 gradle，方便兼容项目的 gradle 版本

## 常用命令

- gradle [task...]
  执行任务，任务可指定全名或缩写

- gradle projects
  查看子项目相关信息

- gradle <project-path>:tasks
  查看所有任务

## android 配置

- 配置构建类型

```groovy
android {
    ...
    release {
      minifyEnabled true
      proguardFiles getDefaultProguardFile('proguard-android.txt'), 'proguard-rules.pro'
    }
    mytype {
        initWith debug //复制debug的配置
        applicationIdSuffix '.mytype'
        ...
    }
}
```

- 配置产品风味

首先需要声明风味维度，标明不同类型的风味。

```groovy
android {
    ...
    flavorDimensions 'version', 'api'
}
```

然后配置不同的产品风味

```groovy
android {
    ...
    bate {
        dimension 'version'
        ...
    }
    full {
        dimension 'version'
        ...
    }
    api21 {
    minSdkVersion '21'
    ...
    }
    api21 {
        minSdkVersion '24'
        ...
    }
}
```

- 构建源集

在 src 下建立与构建类型、产品风味、构建变体相对应的资源。

`src/main/` 包括所有构建变体共用的代码和资源。

`src/<buildType>/` 构建类型专用的代码和资源。

`src/<productFlavor>/` 定构建变体专用的代码和资源。

`src/<productFlavorBuildType>/` 构建变体专用的代码和资源。

不同产品风味可组合构建

## 自定义 task

```groovy
task myTask(type: Copy) {
    from 'src/main'
    into 'src/test'
}
```

括号内的type为基础task类型。

想要添加到android任务序列中，将自定义的任务添加为 android task 的依赖即可，
android Task 执行之前将首先执行自定义的任务。

```groovy
task myTask(type: Copy) {
    ...
}

clean.dependsOn myTask
```

## Android tasks

```
Android tasks
-------------
androidDependencies  - 显示Android的项目依赖关系。
signingReport        - 显示每个变体的签名信息。
sourceSets           - 打印出在这个项目中定义的所有源集。

Build tasks
-----------
assemble             - 汇编所有应用程序和辅助包的所有变体。
assembleAndroidTest  - 汇编所有的Android测试程序。
assembleDebug        - 汇编所有的调试版本。
assembleRelease      - 汇编所有的发布版本。
build                - 汇编和测试此项目。
buildDependents      - 组装和测试这个项目以及所有依赖它的项目。
buildNeeded          - 组装和测试这个项目以及它所依赖的所有项目。
clean                - 删除生成的目录。
cleanBuildCache      - 删除构建缓存目录。
compileDebugAndroidTestSources
compileDebugSources
compileDebugUnitTestSources
compileReleaseSources
compileReleaseUnitTestSources
mockableAndroidJar   - 创建适合单元测试的android.jar版本。

构建安装任务
-----------------
init                 - 初始化一个新的Gradle构建。
wrapper              - 生成Gradle包装器文件。

Help tasks
----------
buildEnvironment     - 显示在根项目“xxx”中声明的所有buildscript依赖项。
components           - 显示由根项目“xxx”生成的组件。 [incubating]
dependencies         - 显示在根项目“xxx”中声明的所有依赖项。
dependencyInsight    - 显示对根项目“xxx”中指定依赖项的洞察。
dependentComponents	 - 显示根项目“xxx”中组件的相关组件。 [incubating]
help                 - 显示帮助信息。
model                - 显示根项目“xxx”的配置模型。 [incubating]
projects             - 显示根项目“xxx”的子项目。
properties           - 显示根项目“xxx”的属性。
tasks                - 显示可从根项目“xxx”运行的任务（某些显示的任务可能属于子项目）。

Install tasks
-------------
installDebug        - 安装Debug版本。
installDebugAndroidTest - 为Debug版本安装android(on device)测试。
uninstallAll        - 卸载所有应用程序。
uninstallDebug      - 卸载调试版本。
uninstallDebugAndroidTest - 卸载Debug版本的android(on device)测试。
uninstallRelease    - 卸载发布版本。

Verification tasks
------------------
check               - 运行所有检查。
connectedAndroidTest- 安装并运行连接设备上所有功能的检测测试。
connectedCheck      - 在当前连接的设备上运行所有设备检查。
connectedDebugAndroidTest - 在连接的设备上安装并运行测试以进行调试。
deviceAndroidTest   - 使用所有设备提供程序安装并运行检测测试。
deviceCheck         - 使用设备提供者和测试服务器运行所有设备检查。
lint                - 在所有变体上运行lint。
lintDebug           - 在Debug版本上运行lint。
lintRelease         - 在发布版本上运行lint。
lintVitalRelease    - 运行发布版本中致命的问题。
test                - 运行所有变体的单元测试。
testDebugUnitTest   - 运行调试版本的单元测试。
testReleaseUnitTest - 为发布版本运行单元测试。
```

## gradle config

```groovy
apply plugin: 'com.android.application'

android {
    compileSdkVersion 29
    buildToolsVersion "29.0.3"

    // DefaultConfig: 使用与所有构建版本的默认值
    defaultConfig {
        // 应用程序 Id
        applicationId "io.l0neman.androiddslexample"
        // applicationId 后缀，打包时附加到后面
        applicationIdSuffix ".test"
        // （仅支持 lib 模块）proguard 文件将包含在 aar 包中，并使用混淆配置
        consumerProguardFiles "xxx.pro"
        // 此产品风味所属的风味维度
        dimension "simple"
        // 指定 native 构建选项
        externalNativeBuild({})
        //（废弃）
        generatedDensities ""
        // java 编译选项
        javaCompileOptions
        // 清单占位符
        manifestPlaceholders(hostName: "www.example.com")
        // 启用多 dex 文件
        multiDexEnabled
        // 确定将哪些类编译到主 dex 文件中
        multiDexKeepFile "xx"
        multiDexKeepProguard "com/example/MyClass.class"
        // NdkOptions: 封装 NDK 的每个变量配置，例如 ABI 过滤
        ndk {
            abiFilters "armeabli-v7a"
        }
        // 指定 proguard 配置文件
        proguardFiles
        // 签名配置文件
        signingConfig {}
        // 测试应用程序 Id
        testApplicationId
        testFunctionalTest
        testHandleProfiling
        // 测试器名称
        testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"
        // 测试器自定义参数
        testInstrumentationRunnerArguments
        // 用于配置矢量可绘制对象的构建时支持的选项
        vectorDrawables
        // 版本号
        versionCode 1
        // 版本名称
        versionName "1.0"
        // 版本名称后缀
        versionNameSuffix
        // 穿戴式设备相关
        wearAppUnbundled

        // >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>

        // 添加一个新字段到 BuildConfig 中
        buildConfigField("int", "hello", "1")
        // （仅支持 lib 模块）proguard 文件将包含在 aar 包中，并使用混淆配置
        consumerProguardFile("")
        // 设置最大 sdk 级别
        maxSdkVersion(30)
        // 设置最小 sdk 级别
        minSdkVersion(21)
        // 设置目标 sdk 级别
        targetSdkVersion(29)
        // 不知道
        missingDimensionStrategy("", "")
        // 指定 proguard 配置文件
        proguardFile("")
        proguardFiles("", "")
        // 指定要保留的备用资源
        resConfig("")
        resConfigs("", "")
        // 添加一个新生成的资源
        resValue("int", "hello", "1")
        setConsumerProguardFiles()
        setProguardFiles()
        setTestProguardFiles()
        testInstrumentationRunnerArgument()
        testInstrumentationRunnerArguments()
        testProguardFile()
        testProguardFiles()
    }

    // AaptOptions: 为 Android Asset Packaging Tool (AAPT) 工具指定相关选项
    aaptOptions {
        // aapt 额外参数
        additionalParameters "-d", "-f", "-g"
        // 是否处理 png
        cruncherEnabled true
        // 指定处理器数量
        cruncherProcesses 4
        // 如果找不到对应条目，则返回失败
        failOnMissingConfigEntry true
        // 描述需要忽略的资源
        ignoreAssets ""
        ignoreAssetsPattern ""
        // 设置不以 apk 格式压缩的文件
        noCompress ".so"

        // >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>

        // aapt 额外参数
        additionalParameters("xx")
        // 设置不以 apk 格式压缩的文件
        noCompress("xx")
    }

    // AdbOptions: 指定 adb 的选项
    adbOptions {
        // 安装选项列表
        installOptions ""
        // 所有 adb 选项的超时时间
        timeOutInMs 100
    }

    // BuildType: 封装此项目所有的构建类型
    buildTypes {
        // 构建类型
        debug {
            // applicationId 后缀，打包时附加到后面
            applicationIdSuffix ".test"
            // （仅支持 lib 模块）proguard 文件将包含在 aar 包中，并使用混淆配置
            consumerProguardFiles "xxx.pro"
            // 启用 png 文件压缩
            crunchPngs true
            // 是否生成可调试的 apk
            debuggable true
            // 是否链接可穿戴设备应用的构建变体
            embedMicroApp
            // 指定 java 编译选项
            javaCompileOptions "x"
            // 是否生成可调试 native 代码的 apk
            jniDebuggable true
            // 清单占位符
            manifestPlaceholders(hostName: "www.example.com")
            // 不知道
            matchingFallbacks
            // 是否删除未使用的 java 代码
            minifyEnabled
            // 启用多 dex 文件
            multiDexEnabled
            // 确定将哪些类编译到主 dex 文件中
            multiDexKeepProguard "com/example/MyClass.class"
            // 构建变体名字
            name "test"
            // 指定 proguard 配置文件
            proguardFiles
            // 是否应为伪语言环境生成资源
            pseudoLocalesEnabled true
            // 是否构建使用可调试的 RenderScript 代码构建 apk
            renderscriptDebuggable
            // renderscript 优化级别
            renderscriptOptimLevel 3
            // 是否压缩未使用资源
            shrinkResources true
            // 签名配置文件
            signingConfig {}
            // 是否启用测试覆盖率
            testCoverageEnabled true
            // 是否使用 proguard 进行代码和资源压缩
            useProguard true
            // 版本名称后缀
            versionNameSuffix ".t"
            // 是否启用 zipAlignEnabled
            zipAlignEnabled true

            // >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>

            // 添加一个新字段到 BuildConfig 中
            buildConfigField("int", "hello", "1")
            // 添加一个 proguard 文件包含在 aar 包中
            consumerProguardFile("")
            // 指定 native 构建选项
            externalNativeBuild({})
            // 复制 BuildType 的所有属性进来
            initWith({})
            //  添加 proguard 混淆配置
            proguardFile("")
            // 添加一个新生成的资源
            resValue("int", "hello", "1")
            // 设置 proguard 配置
            setProguardFiles({})
        }

        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }

    // CompileOptions: 指定 java 编译器选项
    compileOptions {
        // 指定 java 源码编码
        encoding "utf8"
        // 是否启用 java 增量编译
        incremental true
        // java 语言级别
        sourceCompatibility {}
        // java 生成直接码级别
        targetCompatibility {}
    }

    // DataBindingOptions: 数据绑定框架支持
    dataBinding {
        // 是否添加默认适配器
        addDefaultAdapters true
        // 是否开启数据绑定
        enabled true
        // 是否为测试项目运行数据绑定代码生成
        enabledForTests true
        // 要使用的数据绑定版本
        version "x.xx"
    }

    // DexOptions: 指定 DEX 工具的选项
    dexOptions {
        // 传递给 dx 工具的参数列表
        additionalParameters "xx"
        // 指定 java 堆最大值
        javaMaxHeapSize "2048m"
        // 启用巨型模式（--force-jumbo）
        jumboMode true
        // 在旧版 multidex 的主 dex 中保留运行时注解
        keepRuntimeAnnotatedClasses true
        // 最大处理器数量
        maxProcessCount 4
        // 是否预索引库
        preDexLibraries true
        // 使用的线程数量
        threadCount 4
    }

    // ExternalNativeBuild: 配置 native 构建外部工具脚本
    externalNativeBuild {
        // CmakeOptions: 封装 CMake 构建选项
        cmake {
            // 指定构建输出目录
            buildStagingDirectory "./outputs/cmake"
            // 指定构建脚本相对路径
            path "CMakeLists.txt"
            // 使用的 CMake 版本
            version "3.7.1"
        }

        //  NdkBuildOptions: 封装 ndk-build 选项
        ndkBuild {
            // 指定构建输出目录
            buildStagingDirectory "./outputs/ndk-build"
            // 指定构建脚本相对路径
            path "Android.mk"
        }
    }

    // JacocoOptions: 单元测试代码覆盖率工具
    jacoco {
        version "<jacoco-version>"
    }

    // LintOptions: 指定 lint 工具的选项
    lintOptions {
        // 如果发现错误，是否需要 lint 设置进程的退出代码
        abortOnError true
        // lint 是否应在错误输出中显示完整路径
        absolutePaths true
        // 要检查的确切问题集
        check ""
        // lint 是否应检查所有警告
        checkAllWarnings true
        // 在 relese 版本期间，lint 是否应检查致命错误
        checkReleaseBuilds true
        // 要取消的问题 ID 集合
        disable ""
        // 要启用的问题 ID 集合
        enable ""
        // lint 是否应包括问题错误的说明
        explainIssues true
        // HTML 报告应写入的可选路径
        htmlOutput ""
        // 是否应该编写 HTML 报告
        htmlReport true
        // 是否仅检查错误（忽略警告）
        ignoreWarnings true
        // 备用的默认配置文件
        lintConfig
        // 是否应在发生错误的输出中包括源行
        noLines
        // 是否应该保持安静
        quiet
        // 严重性覆盖选项
        severityOverrides(xx: "xx")
        // 是否应包含所有输出
        showAll true
        // 编写文本报告的可选路径
        textOutput ""
        // 是否应该编写文本报告
        textReport true
        // lint 是否应将所有警告视为错误
        warningsAsErrors true
        // XML 报告应写入的可选路径
        xmlOutput ""
        // 是否应该编写 XML 报告
        xmlReport true

        // >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>

        // 指定问题 ID
        check("")
        check("")
        disable("")
        disable("")
        enable("")
        enable("")
        error("")
        error("")
        fatal("")
        fatal("")
        ignore("")
        ignore("")
        warning("")
        warning("")
    }

    // PackagingOptions: 文件打包选项
    packagingOptions {
        // 不应去除调试符号的本机库匹配列表
        doNotStrip "liba.so", ""
        // 排除路径的列表
        excludes
        merges
        pickFirsts
    }

    // 封装此项目的所有产品风味配置
    productFlavors {
        // ProductFlavor
        hello {
            // ...
        }
    }

    // SigningConfig: 签名配置
    signingConfigs {
        debug {
            // 密钥别名
            keyAlias "xxx"
            // 密钥密码
            keyPassword "xxx"
            // 签名文件
            storeFile "xxx"
            // 签名文件密码
            storePassword "xxx"
            // 签名文件类型
            storeType "xxx"
            // 是否启用 v1 签名方案
            v1SigningEnabled true
            // 是否启用 v2 签名方案
            v2SigningEnabled true
        }
    }

    // 封装所有变体的源集配置
    sourceSets {
        hello {
            // 指定 Android AIDL 源目录
            aidl ""
            // 指定 Android Assets 目录
            assets ""
            //（废弃）编译配置的名称
            compileConfigurationName ""
            // AndroidSourceDirectorySet: Java 源代码路径
            java {
                // 从源目录中选择源的过滤器
                filter ""
                // 源目录的简单名称
                name
                // 源文件列表作为 FileTree
                sourceFiles
                // 解析的目录
                srcDirs
            }
            // AndroidSourceDirectorySet: Android JNI 源目录
            jni {}
            // AndroidSourceDirectorySet: Android JNI libs 目录
            jniLibs {}
            // AndroidSourceFile: Android 清单文件
            manifest {
                // 源目录的简洁名称
                name ""
                // 文件
                srcFile ""
            }
            // 此源集的名称
            name ""
            //（废弃）运行时配置的名称
            packageConfigurationName ""
            //（废弃）仅编译配置的名称
            providedConfigurationName ""
            // todo: Android RenderScript 源目录
            renderscript {}
            // todo: Android 资源目录
            res {}
            // todo: 复制到 javaResources 输出目录中的 Java 资源
            resources {}
        }
    }

    // Splits: 用于构建多个 APK 或 APK 拆分的配置
    splits {
        // AbiSplitOptions: 封装用于构建每个ABI APK的设置
        abi {
            include 'armeabi', 'mips', 'mips64'
        }
        // 生成单独的 APK 的 ABI 列表
        abiFilters "armeabi-v7a"
        // DensitySplitOptions: 封装用于构建每个密度 APK 的设置
        density {}
        // 生成单独的 APK 的屏幕密度配置列表
        densityFilters
        // 封装用于构建每个语言（或区域设置）APK 的设置
        language {}
        // 成单独的 APK 的语言（或语言环境）列表
        languageFilters ""
    }

    // TestOptions: 指定如何运行本地测试和检测测试的选项
    testOptions {
        // 在从命令行运行的测试期间，禁用动画
        animationsDisabled true
        // 是否启用设备上的测试流程
        execution 'ANDROID_TEST_ORCHESTRATOR'
        // 报告目录的名称
        reportDir
        // 结果目录的名称
        resultsDir
        // 配置单元测试选项
        unitTests

        // >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>

        unitTests()
    }

    // >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>

    // 指定 Android SDK 的路径
    sdkDirectory ""
    // 将指定的库包含到类路径中
    useLibrary("x")
    flavorDimensions("")
}

dependencies {
    implementation fileTree(dir: 'libs', include: ['*.jar'])

    implementation 'androidx.appcompat:appcompat:1.1.0'
    implementation 'androidx.constraintlayout:constraintlayout:1.1.3'
    testImplementation 'junit:junit:4.12'
    androidTestImplementation 'androidx.test.ext:junit:1.1.1'
    androidTestImplementation 'androidx.test.espresso:espresso-core:3.2.0'
}
```