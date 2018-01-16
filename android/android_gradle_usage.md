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

