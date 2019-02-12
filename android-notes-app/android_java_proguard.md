# Java Proguard配置

* [介绍](#介绍)
* [使用](#使用)
  * [输入/输出](#输入-输出)
  * [keep(保持)](#keep(保持))
  * [shrinking(压缩) ](#shrinking)
  * [optimization(优化)](#optimization(优化))
  * [obfuscation(混淆)](#obfuscation(混淆))
  * [preverification(预校验)](#preverification(预校验))
  * [general(普通选项)](#general(普通选项))
* [过滤规则及附加配置](#过滤及附加配置)
  * [Class Paths](#class_paths)
  * [File Names](#file_names)
  * [File Filters](#file_filters)
  * [Filters](#filters)
  * [Overview Of Keep Options](#overview_of_keep_options)
  * [Keep Option Modifiers](#keep_option_modifiers)
  * [Class Specifications](#class_specifications)
* [android默认proguard文件](#android默认proguard文件)
* [参考](#参考)

## 介绍

### 处理过程

`shrinker(压缩)` -> `optimizer(优化)` -> `obfusctor(混淆)` -> `preverifier`(预校验)

![](/image/android_java_proguard/proguardprocess.png)

### 入口点

- `shrink` 混淆器递归确定哪些类和类成员被使用，并移除其他的类和类成员
- `optimize` 混淆器进一步优化，对于非入口类将会加上 `private/static/final` 的修饰。未使用的参数将被删除，部分方法变成内联方法。
- `obfuscation` 对于非入口的类将会被重命名，对于肉口类将会保持原名称以便被外部访问。
- `preverification` 与入口类无关的步骤，预校验代码是否符合Java1.6或者更高的规范。

### 反射

Proguard 将会自动自动检测处理以下反射调用

```java
Class.forName("SomeClass")
SomeClass.class
SomeClass.class.getField("someField")
SomeClass.class.getDeclaredField("someField")
SomeClass.class.getMethod("someMethod", new Class[] {})
SomeClass.class.getMethod("someMethod", new Class[] { A.class })
SomeClass.class.getMethod("someMethod", new Class[] { A.class, B.class })
SomeClass.class.getDeclaredMethod("someMethod", new Class[] {})
SomeClass.class.getDeclaredMethod("someMethod", new Class[] { A.class })
SomeClass.class.getDeclaredMethod("someMethod", new Class[] { A.class, B.class })
AtomicIntegerFieldUpdater.newUpdater(SomeClass.class, "someField")
AtomicLongFieldUpdater.newUpdater(SomeClass.class, "someField")
AtomicReferenceFieldUpdater.newUpdater(SomeClass.class, SomeType.class, "someField")
```

## 使用

执行proguard

`java -jar proguard.jar options ...`

指定混淆规则

`java -jar proguard.jar @myRule.pro`

参数加混淆规则使用

`java -jar proguard.jar @myRule.pro -verbose`

- 配置文件中使用 `#` 符号作为注释行的开头。
- 文件名之间多余的空格或分隔符会被忽略。
- 包含特殊符号的文件名应当用单引号或者双引号括起来。
- 参数与指定配置文件顺序无关。

<h3 id="输入-输出">输入/输出</h3>

`@_filename_` 和 `-include` 相同，指定混淆规则文件。

`-include` filename  指定混淆规则文件。

`-basedirectory` directory 为所有引用的相对路径指定一个根路径。

`injars classpath` 可以包括 jar, aar, war, ear, zip, apk或者文件目录。这些包或者目录下的class文件将被处理后写入到输出文件中。默认情况下非class文件会被原封不动的复制到输出文件中。

`-outjars` classpath 指定输出文件，类型包括 jar, aar, war, ear, zip, apk和 目录。（注意不要覆盖输入文件）

`-libraryjars` classpaath 指定输入文件引用的类库。这些类库不会被写入到输出文件中。每个库至少要有一个类被引用。

`-skipnonpubliclibraryclasses` 指定读取引用库文件的时候跳过非public类。这样做可以提高处理速度并节省内存。一般情况下非public在应用内是不会被直接引用的，可以跳过。不过，在一些java类库中中出现了public类继承非public类的情况，这时就不能用这个选项了。这种情况下，会打印找不到类的警告。

`-dontskipnonpubliclibraryclasses` 不跳过非public类，在4.5版本以上为默认选项。

`-keepdirectories`[ directory_filter] 指定输出jar包中需要保留的目录名。为了减少输出文件的体积，默认情况下所有的目录都会被删除，如果你的代码中有类似 `MyClass.class.getResource("")` 这样的通过目录获取文件的逻辑时，就需要保持目录名不变。

`-target` version 指定处理的class文件中java的目标版本。版本号是1.0, 1.1, 1.2, 1.3, 1.4, 1.5(或者5)， 1.6（或者6）, 1.7（或者7）,1.8（或者8）之中的一个。默认情况下，class文件的版本号是不会变的。

`-forceprocessing`尽管输出文件已经是最新的，还是强制进行处理一次。

<h3 id="keep(保持)">keep(保持)</h3>

`-keep` [,modifier,...] class_specification 根据类名过滤规则指定类和类成员是入口节点，保护它们不被移除混淆。例如，对一个可执行jar包来说，需要保护main的入口类；对一个类库来说需要保护入口的所有public元素。

```java
-injars       myapplication.jar
-outjars      myapplication_out.jar
-libraryjars  <java.home>/lib/rt.jar
-printmapping myapplication.map

-keep public class mypackage.MyMain {
    public static void main(java.lang.String[]);
}
```

`-keepclassmembers` [,modifier,...] class_specification 仅指定类的成员不被移除优化和混淆。例如保护序列化的类的成员不被混淆。

```java
# 继承了Serizalizable的类的如下成员将不会被移除混淆
-keepclassmembers class * implements java.io.Serializable {
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
```

`keepclasseswithmembers` [,modifier,...] class_specification 和keep相似，通过类成员来指定一些将要被保护的类和类所包含的成员。例如保护所有含有main方法的类：

```java
# 这种情况下含有main方法的类和mainf方法都不会被混淆。
-injars      in.jar
-outjars     out.jar
-libraryjars <java.home>/lib/rt.jar
-printseeds

-keepclasseswithmembers public class * {
    public static void main(java.lang.String[]);
}
```

`-keepnames` class_specification 是 ` -keep,allowshrinking` 的简写形式，指定一些类名受到保护，前提是他们在shrink这一阶段没有被移除。

`-keepclassmembernames` class_specification 是 ` -keepclassmembers,allowshrinking` 的简写形式，保护指定的类成员，前提是这些成员在shrink阶段没有被移除。

`-keepclasseswithmembernames` class_specification 是 `-keepclasseswithmembers,allowshrinking` 的简写形式，保护指定的类，如果它们没有在shrink阶段被移除。

`printseeds` [filename] 指定通过-keep配置匹配的类或者类成员的详细列表。列表可以打印到标准输出流或者文件里面。这个列表可以看到我们想要保护的类或者成员有没有被真正的保护到，尤其是那些使用通配符匹配的类。

<h3 id="shrinking">shrinking(压缩) </h3>

`-dontshrink` 声明不压缩输入文件。默认情况下，除了-keep相关配置指定的类，所有其它没有被引用到的类都会被移除。每次optimizate操作之后，也会执行一次压缩操作，因为每次optimizate操作可能移除一部分不再需要的类。

`-printusage` [filename] 打印出那些被移除的元素。可能打印到标准输出流或者一个文件中。仅在shrink阶段有效。

`-whyareyoukeeping` class_specification 打印一个类或类的成员变量被保护的原因。这对检查一个输出文件中的类的结果有所帮助。

<h3 id="optimization(优化)">optimization(优化)</h3>

`-dontoptimize` 声明不优化输入文件。默认情况下，优化选项是开启的，并且所有的优化都是在字节码层进行的。

`-optimizations` optimization_filter 更加细粒度地声明优化开启或者关闭。只在optimize这一阶段有效。这个选项的使用难度较高。

`-optimizationpasses` n 指定执行几次优化，默认情况下，只执行一次优化。执行多次优化可以提高优化的效果，但是，如果执行过一次优化之后没有效果，就会停止优化，剩下的设置次数不再执行。这个选项只在optimizate阶段有效

`-assumenosideeffects` class_specification 指定一些方法被移除也没有影响（尽管这些方法可能有返回值），在optimize阶段，如果确定这些方法的返回值没有使用，那么就会删除这些方法的调用。proguard会自动的分析你的代码，但不会分析处理类库中的代码。例如，可以指定System.currentTimeMillis(),这样在optimize阶段就会删除所有的它的调用。还可以用它来删除打印Log的调用。这条配置选项只在optimizate阶段有用。

`-allowaccessmodification` 这项配置是设置是否允许改变作用域的。使用这项配置之后可以提高优化的效果。如果你的代码是一个库的话，最好不要配置这个选项，因为它可能会导致一些private变量被改成public。

`-mergeinterfacesaggressively` 指定一些接口可能被合并，即使一些子类没有同时实现两个接口的方法。这种情况在java源码中是不允许存在的，但是在java字节码中是允许存在的。它的作用是通过合并接口减少类的数量，从而达到减少输出文件体积的效果。仅在optimize阶段有效。

```java
# 删除代码中Log相关的代码
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}
```

<h3 id="obfuscation(混淆)">obfuscation(混淆)</h3>

`-dontobfuscate` 声明不混淆。默认混淆开启。除了keep配置中声明的类，其它的类或者类的成员混淆后会改成简短随机的名字。

`-printmapping` [filename] 指定输出新旧元素名的对照表的文件。映射表会被输出到标准输出流或者是一个指定的文件。

`-applymapping` filename 指定重用一个已经写好了的map文件作为新旧元素名的映射。元素名已经存在在mapping文件中的元素，按照映射表重命名；没有存在到mapping文件的元素，重新赋一个新的名字。mapping文件可能引用到输入文件中的类和类库中的类。这里只允许设置一个mapping文件。仅在obfuscate阶段有效。

`-obfuscationdictionary` filename 指定一个文本文件用来生成混淆后的名字。默认情况下，混淆后的名字一般为a,b，c这种。通过使用 `-obfuscationdictionary` 配置的字典文件，可以使用一些非英文字符做为类名。成员变量名、方法名。字典文件中的空格，标点符号，重复的词，还有以'#'开头的行都会被忽略。需要注意的是添加了字典并不会显著提高混淆的效果，只不过是更不利与人类的阅读。正常的编译器会自动处理他们，并且输出出来的jar包也可以轻易的换个字典再重新混淆一次。最有用的做法一般是选择已经在类文件中存在的字符串做字典，这样可以稍微压缩包的体积。字典文件的格式：一行一个单词，忽略空行和重复的单词

```java
# 使用java关键字混淆，真是6啊
# This obfuscation dictionary contains reserved Java keywords. They can't
# be used in Java source files, but they can be used in compiled class files.
# Note that this hardly improves the obfuscation. Decent decompilers can
# automatically replace reserved keywords, and the effect can fairly simply be
# undone by obfuscating again with simpler names.
# Usage:
#     java -jar proguard.jar ..... -obfuscationdictionary keywords.txt
#

do
if
for
int
new
try
byte
case
char
else
goto
long
this
void
break
catch
class
const
final
float
short
super
throw
while
double
import
native
public
return
static
switch
throws
boolean
default
extends
finally
package
private
abstract
continue
strictfp
volatile
interface
protected
transient
implements
instanceof
synchronized
```

`-classobfuscationdictionary` filename 指定一个混淆类名的字典，字典的格式与 `-obfuscationdictionary` 相同

`-packageobfuscationdictionary` filename 指定一个混淆包名的字典，字典格式与 `-obfuscationdictionary` 相同

`-overloadaggressively` 混淆的时候大量使用重载，多个方法名使用同一个混淆名，但是他们的方法签名不同。这可以使包的体积减小一部分，也可以加大理解的难度。仅在混淆阶段有效。

注意，这项配置有一定的限制：

Sun的JDK1.2上会报异常

Sun JRE 1.4上重载一些方法之后会导致序列化失败

Sun JRE 1.5上pack200 tool重载一些类之后会报错

java.lang.reflect.Proxy类不能处理重载的方法

Google's Dalvik VM can't handle overloaded static fields dalvik不能处理静态变量重载

`-useuniqueclassmembernames` 指定相同的混淆名对应相同的方法名，不同的混淆名对应不同的方法名。如果不设置这个选项，同一个类中将会有很多方法映射到相同的方法名。这项配置会稍微增加输出文件中的代码，但是它能够保证保存下来的mapping文件能够在随后的增量混淆中继续被遵守，避免重新命名。比如说，两个接口拥有同名方法和相同的签名。如果没有这个配置，在第一次打包混淆之后，他们两个方法可能会被赋予不同的混淆名。如果说下一次添加代码的时候有一个类同时实现了两个接口，那么混淆的时候必然会将两个混淆后的方法名统一起来。这样就必须要改混淆文件其中一处的配置，也就不能保证前后两次混淆的mapping文件一致了。（如果你只想保留一份mapping文件迭代更新的话，这项配置比较有用）

`-dontusemixedcaseclassnames` 指定在混淆的时候不使用大小写混用的类名。默认情况下，混淆后的类名可能同时包含大写字母和小写字母。这样生成jar包并没有什么问题。只有在大小写不敏感的系统（例如windows）上解压时，才会涉及到这个问题。因为大小写不区分，可能会导致部分文件在解压的时候相互覆盖。如果有在windows系统上解压输出包的需求的话，可以加上这个配置。

`-keeppackagenames` [package_filter] 声明不混淆指定的包名。 配置的过滤器是逗号隔开的一组包名。包名可以包含?,,*通配符，并且可以在前面加!否定符。

`-flatternpackagehierarchy` [packagename] 所有重新命名的包都重新打包，并把所有的类移动到packagename包下面。如果没有指定packagename或者packagename为""，那么所有的类都会被移动到根目录下

`-repackageclasses` [package_name] 所有重新命名过的类都重新打包，并把他们移动到指定的packagename目录下。如果没有指定packagename，同样把他们放到根目录下面。这项配置会覆盖-flatternpackagehierarchy的配置。它可以代码体积更小，并且更加难以理解。这个与废弃的配置-defaultpackage作用相同。

`-keepattributes` [attribute_filter] 指定受保护的属性，可以有一个或者多个-keepattributes配置项，每个配置项后面跟随的是Java虚拟机和proguard支持的attribute(具体支持的属性先看这里)，两个属性之间用逗号分隔。属性名中可以包含*,\**,?等通配符。也可以加!做前导符，将某个属性排除在外。当混淆一个类库的时候，至少要保持InnerClasses, Exceptions,  Signature属性。为了跟踪异常信息，需要保留SourceFile, LineNumberTable两个属性。如果代码中有用到注解，需要把Annotion的属性保留下来。

```java
-keepattributes SourceFile, LineNumberTable
-keepattributes *Annotation*
-keepattributes EnclosingMethod

-keepattributes Exceptions, InnerClasses, Signature, Deprecated,
                SourceFile, LineNumberTable, *Annotation*, EnclosingMethod
```

`-keepparameternames` 指定被保护的方法的参数类型和参数名不被混淆。这项配置在混淆一些类库的时候特别有用，因为根据IDE提示的参数名和参数类型，开发者可以根据他们的语义获得一些信息，这样的类库更友好。

`-renamesourcefileattribute` [string] 指定一个字符串常量设置到源文件的类的属性当中。这样就可以在-keepattributes配置中使用。（这条我理解的也不是很清楚）

`-adaptclassstrings`  [classfilter] 指定字符串常量如果与类名相同，也需要被混淆。如果没有加classfilter，所有的符合要求的字符串常量都会被混淆；如果带有classfilter，只有在匹配的类中的字符串常量才会受此影响。例如，在你的代码中有大量的类名对应的字符串的hard-code,并且不想保留他们的本名，那就可以利用这项配置完成。这项配置只在混淆阶段有效，但是在压缩/优化阶段，涉及到的类会自动保留下来。

`adaptresourcefilenames` [file_filter] 如果资源文件与某类名同，那么混淆后资源文件被命名为与之对应的类的混淆名。不加file_filter的情况下，所有资源文件都受此影响；加了file_filter的情况下，只有匹配到的类受此影响。

`adaptresourcefilecontents` [file_filter] 指定资源文件的中的类名随混淆后的名字更新。根据被混淆的名字的前后映射关系，更新文件中对应的包名和类名。同样，如果不配置file_filter，所有的资源文件都会受此影响；配置了filter之后，只有对应的资源文件才受此影响。

<h3 id="preverification(预校验)">preverification(预校验)</h3>

`-dontpreverify` 声明不预校验即将执行的类。默认情况下，在类文件的编译版本为java micro 版本或者大于1.6版本时，预校验是开启的。目标文件针对java6的情况下，预校验是可选的；针对java7的情况下，预校验是必须的，除非目标运行平台是Android平台，设置它可以节省一点点时间。目标为Java Micro版本的情况下，预校验是必须的。如果你声明了这项配置，你还需要加上下面一条配置。

`-microedition` 声明目标平台是java micro版本。预校验会根据这项配置加载合适的StackMap，而不是用标准的StackMap。

<h3 id="general(普通选项)">general(普通选项)</h3>

`-verbose` 声明在处理过程中输出更多信息。添加这项配置之后，如果处理过程中出现异常，会输出整个StackTrace而不是一条简单的异常说明。

`-dontnote` [class_filter] 声明不输出那些潜在的错误和缺失，比如说错别字或者重要的配置缺失。配置中的class_filter是一串正则表达式，混淆过程中不会输出被匹配到的类相关的内容。

`-dontwarn` [class_filter] 声明不输出那些未找到的引用和一些错误，但续混淆。配置中的class_filter是一串正则表达式，被匹配到的类名相关的警告都不会被输出出来。

`-ignorewarnings` 输出所有找不到引用和一些其它错误的警告，但是继续执行处理过程。不处理警告有些危险，所以在清楚配置的具体作用的时候再使用。

`-printconfiguration` [filename] 输出整个处理过程中的所有配置参数，包括文件中的参数和命令行中的参数。可以不加文件名在标准输出流中输出，也可以指定文件名输出到文件中。它在调试的时候比较有用。

`-dump` [filename] 声明输出整个处理之后的jar文件的类结构，可以输出到标准输出流或者一个文件中。

## 过滤及附加配置

<h3 id="class_paths">Class Paths</h3>

对应上文中的所有class_path，用来指定输入输出文件的路径的。它可以有多个路径用分隔符隔开。
可以使用过滤器来过滤需要输出的文件。过滤器的格式如下:

```java
classpathentry([[[[[[aarfilter;]apkfilter;]zipfilter;]earfilter;]warfilter;]jarfilter;]filefilter)
```

`[]` 代表内容可选，

```java
-injars rt.jar(java/**.class,javax/**.class) # 只引入rt.jar中的java/ javax/目录下的类
-injars input.jar(!**.gif,images/**) # 引入images/下面的资源而不引入.gif格式的文件
```

<h3 id="file_names">File Names</h3>

file names可以设置相对路径和绝对路径

- 如果设置了base diectory，则相对于此目录。

- 如果有配置文件，则相对于配置文件的路径。

- 相对于工作目录。

路径中也可使用 `<` 和 `>` 包含java系统的属性，将会自动替换为java属性路径 ，例如
`<java.home>/lib/rt.jar` 映射到/usr/local/java/jdk/jre/lib/rt.jar路径
`<user.home>` 映射到用户主目录
`<user.dir>` 映射到当前工作目录
在使用特殊字符例如空格时需要加单引号或者双引号，注意在使用命令行时引号本身可能需要转义

<h3 id="file_filters">File Filters</h3>

类似于下面的filter,可以使用通配符匹配。

`?` 代表文件名中的一个字符
`*` 代表文件名中的一部分,不包括文件分隔符
`**` 代表文件名中的一部分，包括文件分隔符
`!` 放在文件名前面表示排除在外

<h3 id="filters">Filters</h3>

与file filters匹配规则相同，匹配范围更广，可以匹配文件，目录，类，包，属性，优化等属性。

<h3 id="overview_of_keep_options">Overview Of Keep Options</h3>

下面是 `-keep` 选项的分类整理

| 保留项            | 不会被移除和重命名                 | 不会被重命名                        |
| -------------- | ------------------------- | ----------------------------- |
| 类和类成员          | `-keep`                   | `-keepnames`                  |
| 只有类成员          | `-keepclassmebers`        | `-keepclassmembernames`       |
| 如果指定类成员，则保留类成员 | `-keepclasseswithmembers` | `-keepclasseswithmembernames` |

如果不确定使用哪个选项，就使用 `-keep` ，他能保证类和类成员在压缩过程中不被移除和重命名。

- 如果只指定一个类，没有指定类成员的话，Proguard只会将类和它的无参构造器保留，依然可以删除，优化和混淆其他类成员。

- 如果指定成员方法，Proguard只会将该方法保留，它的代码还是会被优化和调整。

<h3 id="keep_option_modifiers">Keep Option Modifiers</h3>

`-keep` 附加选项。

`includedescriptorclasses`

指定 `-keep` 选项所指定的方法和字段的所包含的任何类都会被保护，这在保护 native方法时很有效，保持本地的方法的参数类型也不会被重命名，保持方法签名不变。

`allowshrinking` 

指定 `-keep` 选项中指定的入口点可以在压缩阶段被移除，如果它们没有被使用的话。

`allowoptimization`

指定 `-keep` 选项中指定的入口点可以被优化，但是不会被移除和混淆，特殊情况下使用。

`allowobfuscation`

指定 `-keep` 选项中指定的入口点可以被混淆，但是不会被移除和优化，特殊情况下使用。

<h3 id="class_specifications">Class Specifications</h3>

类的指定方式可用于 `-keep` 选项和 `-assumenosideeffects` 选项，它与java原生代码形式有相似之处，可以包含通配符，下面是通用模版：

```java
[@annotationtype] [[!]public|final|abstract|@ ...] [!]interface|class| enum classname
				  [extends|implements [@annotationtype] classname] [{
    [@annotationtype] [[!]public|private|protected|static|volatile|transient ...] <fields> |
                                                                      (fieldtype fieldname);
    [@annotationtype] [[!]public|private|protected|static|synchronized|native|abstract|strictfp ...] <methods> |
                                                                                      <init>(argumenttype,...) |
                                                                                   classname(argumenttype,...) |
                                                                      (returntype methodname(argumenttype,...));
    [@annotationtype] [[!]public|private|protected|static ... ] *;
    ...
}]
```

- 其中 `[]` 代表可选配置， `...` 代表更多的类似选项，`|` 代表选择其中一种方式，`()` 代表组合的规则。

- `class` 关键字可指定任何类和接口，而 `interface` 只能指定接口，`enum` 限制了枚举类型，`!` 指定非这种类型。

- 类名必须是完整的类名，例如 `java.lang.String`，内部类用 `$` 符号分隔，例如 `java.lang.Thread$State`，类名可使用以下通配符匹配

> `?` 匹配类名中的任何单个字符，但不匹配包分隔符。
>
> `*` 匹配不包含包分隔符的类名称的任何部分。
>
> `**` 匹配类名称的任何部分，可能包含任意数量的包分隔符。

- 类名也可以是通过 `,` 分隔的类名列表，因为不像java代码形式，可以在适合的时候使用。

- `extends` 和 `implements` 关键字用于限制具有通配符的类，它们是等价的，指定扩展或者实现指定类的类，注意，指定的类不包含在列表中，可以单独指定。

- `@` 指定类是使用指定注释类型注释的类。

- 字段和方法与java语言形式相似，不过参数列表不包含参数名称

 > `<init>`匹配任何构造函数。
 >
 > `<fields>`	匹配任何字段。
 >
 > `<methods>`	匹配任何方法。
 >
 > `*` 匹配任何字段或方法。

上面的通配符不能指定返回类型，只有 `<init>` 包含参数列表，也可使用下面通配符来指定方法和字段：

> `?`	匹配方法名称中的任何单个字符。
>
> `*`	匹配方法名称的任何部分。

匹配类型可以包括以下通配符：

> `%` 匹配任何原始类型(“ boolean”，“ int”等，不包括“ void”)。
>
> `?` 匹配类名中的任何单个字符。
>
> `*` 匹配不包含包分隔符的类名称的任何部分。
>
> `**` 匹配类名称的任何部分，可能包含任意数量的包分隔符。
>
> `***` 匹配任何类型（原始或非原始数组或非数组）。
>
> `...` 匹配任何数量的任何类型的参数。

- `*` 和 `**` 不会匹配原始类型，只有 `***` 可以匹配任何多维数组，例如：

`** get*()` 可以匹配 `java.lang.Object getObject()`，但不会匹配 `float getFloat()` 也不会是 `java.lang.Objact[] getObjects()`。

- 构造函数可以使用简单类名或完整类名指定，它不包含返回类型。

- 可以组合多个限制符，例如 `public static`，但是它们不能彼此冲突。

# android默认proguard文件

`{android_sdk_home}/tools/proguard/proguard-android.txt` 文件中是android默认的混淆配置文件。

```java
# This is a configuration file for ProGuard.
# http://proguard.sourceforge.net/index.html#manual/usage.html
# 关闭大小写混淆
-dontusemixedcaseclassnames
# 不跳过检测任何非public的类
-dontskipnonpubliclibraryclasses
# 打印日志
-verbose

# Optimization is turned off by default. Dex does not like code run
# through the ProGuard optimize and preverify steps (and performs some
# of these optimizations on its own).
-dontoptimize
-dontpreverify
# Note that if you want to enable optimization, you cannot just
# include optimization flags in your own project configuration file;
# instead you will need to point to the
# "proguard-android-optimize.txt" file instead of this one from your
# project.properties file.

-keepattributes *Annotation*
-keep public class com.google.vending.licensing.ILicensingService
-keep public class com.android.vending.licensing.ILicensingService

# 保持包含本地方法的类
# For native methods, see http://proguard.sourceforge.net/manual/examples.html#native
-keepclasseswithmembernames class * {
    native <methods>;
}

# 保持view中的set和get方法，保证属性动画的正确调用
# see http://proguard.sourceforge.net/manual/examples.html#beans
-keepclassmembers public class * extends android.view.View {
   void set*(***);
   *** get*();
}

# 保持布局文件中映射到activity中的点击事件方法
-keepclassmembers class * extends android.app.Activity {
   public void *(android.view.View);
}

# For enumeration classes, see http://proguard.sourceforge.net/manual/examples.html#enumerations
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keepclassmembers class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator CREATOR;
}

# 保持R类和其中的常量
-keepclassmembers class **.R$* {
    public static <fields>;
}

# The support library contains references to newer platform versions.
# Don't warn about those in case this app is linking against an older
# platform version.  We know about them, and they are safe.
-dontwarn android.support.**

# Understand the @Keep support annotation.
-keep class android.support.annotation.Keep

-keep @android.support.annotation.Keep class * {*;}

# 保持包含keep注解的类和类成员
-keepclasseswithmembers class * {
    @android.support.annotation.Keep <methods>;
}

-keepclasseswithmembers class * {
    @android.support.annotation.Keep <fields>;
}

-keepclasseswithmembers class * {
    @android.support.annotation.Keep <init>(...);
}
```

# 参考

- [Android Proguard(混淆)-简书](http://www.jianshu.com/p/60e82aafcfd0)

- [官方文档](https://stuff.mit.edu/afs/sipb/project/android/sdk/android-sdk-linux/tools/proguard/docs/index.html#manual/introduction.html)