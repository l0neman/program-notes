# Android编译时View注入工具的实现

* [使用反射实现view注入](#使用反射实现view注入)
  * [定义注解](#定义注解)
  * [实现注入方法](#实现注入方法)
* [java实现编译时注解](#java实现编译时注解)
  * [准备工作](#准备工作a)
  * [实现注解处理器](#实现注解处理器)
  * [配置注解处理器](#配置注解处理器)
  * [测试](#测试a)
* [android实现编译时注解view注入工具](#android实现编译时注解view注入工具)
  * [准备工作](#准备工作b)
  * [实现思路](#实现思路)
  * [整体设计](#整体设计)
  * [具体实现](#具体实现)
  * [测试](#测试b)
* [项目代码](#项目代码)
* [参考](#参考)

一般我们项目中为了避免过多的调用findViewById方法，经常会用到View注入框架，它可以用在Activity或者ViewHolder或一些内部包含需要被findViewById的View成员变量的类上，一般使用方式如下代码所示，首先需要在需要被注入的View上注解绑定对应id，然后在适当的时机调用注入方法，一次性注入所有View

```java
//Activity
public final class MainActivity extends Activity {
    @ViewInject(R.id.tv_bottom)  //绑定id
    TextView mTextView;

    @ViewInject(R.id.btn_bottom)
    Button mButton;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		ViewInjector.inject(this);  //一次性注入View
		...
}
```

```java
class ViewHolder {
    @ViewInject(R.id.tv_item)
    TextView mItemTv;

    ViewHolder(View itemView) {
	    ViewInjector.inject(this, itemView);
	}
	...
}
```

使用起来确实比起自己调用findViewById方便多了，而且使代码更加简洁，如果实在想使用findViewById的话，可以写一个泛型转换方法，放在抽象类里面，在子类里调用，就不用每次强制类型转换了

```java
public abstract class BaseActivity extends Activity｛
    ...
	@SuppressWarings("unchecked") //安全转换
    public <T extends View> findCastViewById(int id) {
	    return (T) findViewById(id);
	}
｝

......
调用
mTextView = findCastViewById(tv_bottom);
```

但现在如果让我们自己实现一个注解工具，应该怎么办？如果需要实现使用注解实现的工具，一般有两种选择，一种是通过Java反射在运行时获取目标注解，然后再利用反射进行一些方法的调用，另一种是利用Java注解处理器在编译时处理目标注解，动态生成Java代码以供调用，下面是两种方法的详细实现

## 使用反射实现view注入

- 使用反射的方法实现不是很复杂，但是反射执行方法开销比较大，所以不建议使用

### 定义注解

实现反射注解框架，首先需要定义一个可以使用的注解类，它需要包含一个值来保存View的id

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)  //只能修饰类成员
public @interface ReflectionInject {
    int value();  //保存id
}
```

根据需要，使用元注解修饰自定义的注解，下面是其他的一些修饰

> - **@ Retention(修饰Annotation定义)**
>   用于指定被修饰的Annotation可以保留多长时间

> `RententionPolicy.CLASS` 编译器将把Annotation记录在class中,当java程序退出,JVM不再保留Annotation。这是默认值

> `RententionPolicy.RUNTIME` 编译器将把Annotation记录在class中,当java程序退出,JVM也会保留Annotation，可通过反射获取该Annotation信息

> `RententionPolicy.SOURCE` Annotation只要保留在源代码中，编译器会直接丢弃这种Annotation.

> - **@ Target(修饰Annotation定义)**
>   用于指定被修饰的Annotation能用于修饰哪些程序单元

> `ElementType.TYPE` 指定可以修饰类、接口(包括注释类型) 或枚举定义

> `ElementType.FIELD` 指定只能修饰成员变量

> `ElementType.METHOD` 指定只能修饰方法定义

> `ElementType.PARAMETER` 指定可以修饰参数

> `ElementType.CONSTRUCTOR` 指定只能修饰构造器

> `ElementType.LOCAL_VARIABLE` 指定只能修饰局部变量

> `ElementType.ANNOTATION_TYPE` 指定只能修饰Annotation

> `ElementType.PACKAGE` 指定只能修饰包定义

### 实现注入方法

现在只需要利用反射实现注入方法就行了，首先获取目标对象所有的成员变量，然后遍历处理每个使用ReflectionInject注解的View成员变量，反射调用目标对象的findViewById方法，最后将方法返回值赋予View成员变量即可

```java
public final class ReflectionViewInject {
    ...
    private static final String METHOD_FIND_NAME = "findViewById";

	public static void inject(Activity activity) {
		Class<? extends Activity> clazz = activity.getClass();
		Field[] fields = clazz.getDeclaredFields();
		//遍历所有元素
		for (Field field : fields) {
		    //查询注解
			ReflectionInject inject =  field.getAnnotation(ReflectionInject.class);
			if (inject == null) {
				continue;
			}
			//从目标注解和获取与view绑定的id
			final int id = inject.value();
			try {
			    //获取findViewById方法
				Method method = clazz.getMethod(METHOD_FIND_NAME, int.class);
				//反射调用目标方法
				Object result = method.invoke(activity, id);
				//注入结果
				field.set(activity, result);
			} catch (NoSuchMethodException e) {
				e.printStackTrace();
			} catch (InvocationTargetException e) {
				e.printStackTrace();
			} catch (IllegalAccessException e) {
				e.printStackTrace();
			}
		}
	}
}
```

此时就可以在Activity内使用了

```java
//XXXActivity

@ReflectionInject
private Button mButton;
...
onCreate() {
    ...
	ReflectionViewInject(this);
}

```

上面实现的只是对目标Activity内的View进行注入，当然也可以对其他成员变量进行注入，我们还可以实现OnClick事件的注入，基本思想就是利用反射调用方法或者设置结果

## Java实现编译时注解

所谓编译时注解就是编译时对注解进行处理，而不是运行时，主要思想是，定义一个注解处理器，指定需要处理的注解，当项目编译时，注解处理器的处理方法将被回调，然后在处理方法内进行处理，一般可以在处理方法里生成Java文件供程序调用，或者生成日志文档，所以主要是实现注解处理方法，下面只是一个实现注解处理的简单例子，不过重点是实现Android注入工具

<h3 id="准备工作a">准备工作</h3>

这里使用的是IntelliJ Idea，也可以使用Eclipse等工具，步骤类似，首先实现注解处理器需要单独的Module，因为它需要被以jar方法引入示例Module，新建Module时需要选择Maven支持，因为需要添加 `maven-compiler-plugin` 的支持，而编译示例Module时，需要指定注解处理器所在位置，即为jar所在位置，下面会细说

![](/image/android_view_injector/20160619203839648.png)

新建Moudle完还需要对pom.xml进行配置，添加 `maven-compiler-plugin` 后如下所示

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>groupId</groupId>
    <artifactId>MyViewInjectTest</artifactId>
    <version>1.0-SNAPSHOT</version>
    <packaging>jar</packaging>
    <build>
        <plugins>
            <plugin>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>2.3.2</version>
                <configuration>
                    <source>1.6</source>
                    <target>1.6</target>
                    <!-- Disable annotation processing for ourselves. -->
                    <compilerArgument>-proc:none</compilerArgument>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

准备工作就这么多，下面开始编写代码

### 定义注解

还是一样是，首先定义一个可用注解

```java
@Retention(RetentionPolicy.CLASS) //这里是CLASS
@Target(ElementType.FIELD)
public @interface InjectTest {
    int value();
}
```

接下来实现注解处理器

### 实现注解处理器

实现注解处理器需要继承自 `javax.annotation.processing.AbstractProcessor` 类，首先建立  `MyProcessor` 类，其中 `process` 是抽象方法，必须实现， `init` 方法为覆写方法，这里只指定了处理 `InjectTest`这一个注解，当然还可以处理多个注解，process方法只输出了注解元素的名字和注解包含的值

```java
@SupportedAnnotationTypes("com.example.InjectTest")  //指定目标注解
@SupportedSourceVersion(SourceVersion.RELEASE_7)  //发布版本
public final class MyProcessor extends AbstractProcessor {

    @Override
	public synchronized void init(ProcessingEnvironment processingEnv) {
		super.init(processingEnv);
		printNote("MyProcessor initialize!");
	}
	@Override
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		//遍历类注解元素
		for (Element e : roundEnv.getElementsAnnotatedWith(InjectTest.class)) {
			printNote("name : " + e.getSimpleName() + " value = "
  + e.getAnnotation(InjectTest.class).value());
		}
		return true;
	}

    private void printNote(String note) {
		//打印消息需要使用printMessage
		processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, note);
	}
}
```

### 配置注解处理器

指定注解处理器信息需要在 `resource` 目录下建立 `META-INF/services/javax.annotation.processing.Processor` 文件，里面是自定义注解处理器的完整类名

```java
com.example.InjectTest
```

![](/image/android_view_injector/20160619203938378.png)

<h3 id="测试a">测试</h3>

在测试之前需要确认开启注解处理，开启方法 点击 `Setting->Annotation Processor`，设置注解处理器路径，为jar包所在位置

![](/image/android_view_injector/20160619204042005.png)

首先需要将当前Module打包成jar，点击 `File->Project Structure->Artifacts` 添加当前Module，然后返回Module选择 `Build->Build Artifacts` 即可生成jar包路径是 `out/artifacts`，然后新建示例Module，新建一个类，像这样

```java
package com;
import com.example.InjectTest;

public class MainClass {

    @InjectTest(1)
    private String a;

    @InjectTest(2)
    private String b;
}
```

直接编译，无需运行，即可看到打印信息

![](/image/android_view_injector/20160619204114509.png)

至此，我们就实现了一个简单的编译时注解的例子，不过没有什么用，因为注解处理方法里什么都没有，只是输出了几行信息，这只是实现一个编译时处理注解的流程，下面我们来实现一个完整的Android的View注入工具

## android实现编译时注解view注入工具

<h3 id="准备工作b">准备工作</h3>

首先使用AndroidStudio建立一个Project，这里的配置和IntelliJ Idea不太一样，Android中没有注解处理器的配置，需要引入apt插件，在Porject的build.gradle内加入如下 `classpath`

```java
classpath 'com.neenbedankt.gradle.plugins:android-apt:1.8'
```

### 实现思路

实现思路是这样的，首先我们**定义注解**，然后**实现注解处理器**，当Module编译时，我们的注解处理器会**生成**若干**Java类**，而这些类就是为了调用findViewById方法调用而实现的类，我们还需要实现一个**注入器**类，这个注解器负责调用生成的类，注入Activity或者ViewHolder目标类，当目标类**调用注入器**时，就会完成对View的注入

### 整体设计

首先我们的Project包含4个Module，分别是

> - `SimpleViewInject-api` Android Library
>   此Module提供Android调用的api，主要是注入器的实现，负责注入逻辑的实现（调用生成类）

> - `SimpleViewInject-annotation` Java Library
>   此Module为Java Library，用于存放注解

> - `SimpleViewInject-compiler` Java Library
>   此Module为Java Library，用于存放注解处理器，需要引入 `annotation`  的依赖
```java
compile project(':simpleviewinject-annotation')
```

> - `Sample_SimpleViewInject` Android Module
>   测试Module，测试注入工具，需引入 `annotation `和 `compiler` 的依赖和 `compiler` 的apt编译
```java
apt project(':simpleviewinject-compiler')
compile project(':simpleviewinject-api')
compile project(':simpleviewinject-annotation')
```

解释以下上面为什么是Java Library而不是 Android Library，因为Android的api里没有 `AbstractProcessor` 类，Android api不包含完整的Java类库，不过这里我们只是用注解处理器生成文件，不用与Android类有所关联

在Java Library Module的build.gradle还需要加入如下语句，确保不会编译为Java8的库

```java
sourceCompatibility = JavaVersion.VERSION_1_7
targetCompatibility = JavaVersion.VERSION_1_7
```

### 具体实现

首先是注解，在 `SimpleViewInject-annotation` 里建立一个 `ViewInject` 注解即可

```java
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)
public @interface ViewInject {
    int value();
}
```

- 在实现注解处理器之前，需要先想好生成什么样的类提供给 `SimpleViewInject-api` 进行调用，我们生成的类文件主要是为了调用findViewById方法，还需要给目标类的View成员赋值，所以需要提供目标类的对象 `target` 和具有findViewById方法的类对象 `source` ，但是Activity和ViewHolder类findView时有所差别，Activity使用自身的方法，而ViewHolder需要使用itemView的方法，所以还需要提供一个findViewById策略类型对象 `Finder`，在我们生成Java类后，因为是编译时生成的，需要使用 `newInstace` 方法反射创建对象，所以需要抽象一个接口来接收对象

参考 [Butter Knife](https://github.com/JakeWharton/butterknife)，在 `SimpleViewInject-api` 里创建一个接口 `AbstractInjector` 满足以上条件

```java
public interface AbstractInjector<T> {
    /**
     * @param finder fndView策略
     * @param target 目标类对象
     * @param source 提供findView方法的类对象
     */
    void inject(FindStrategy finder, T target, Object source);
}
```

然后在 `SimpleViewInject-api` 里实现FindView策略类，使用enum类型更好

```java
public enum FindStrategy {
    // 实现ViewHolder策略
	VIEW {
		@Override
		@SuppressWarnings("unchecked") //安全转换
		public <T extends View> T findViewById(Object source, int id) {
			return (T) ((View) source).findViewById(id);
		}
	},
	//实现Activity策略
	ACTIVITY {
		@Override
		@SuppressWarnings("unchecked") //安全转换
		public <T extends View> T findViewById(Object source, int id) {
			return (T) ((Activity) source).findViewById(id);
		}
	};

	public abstract <T extends View> T findViewById(Object source, int id);
```

那么我们的生成的代理类需要实现接口 `AbstractInjector` 并对应不同目标实现不同逻辑，先拟定一个模板如下

```java
package /*与目标类包名相同*/;

import com.runing.example.simpleviewinject_api.AbstractInjector;
import com.runing.example.simpleviewinject_api.FindStrategy;
import java.lang.Object;
import java.lang.Override;

public class /*代理类名*/ <T extends /*目标类*/> implements AbstractInjector<T> {
    @Override
    public void inject(final FindStrategy finder, final T target, Object source) {
        target./*view名字*/ = finder.findViewById(source, /*view的id*/);
        ...
    }
}
```

下面我们就可以按照上面的设计，来实现注解处理器了，目标就是通过注解信息来生成需要被调用的Java类，既然需要生成类，就要提供生成类的重要信息，对照上面的模版来看，我们需要

> - 代理类的名字（完整目标类名+$$Proxy）

> - 目标类的名字 (MainActivity ...)

> - 所有需要被注入的View信息

分析了需要提供的信息，所以我们需要在 `SimpleViewInject-compiler` 里建立相应bean类，首先是 `ViewInfo`，它只负责保存View信息

```java
final class ViewInfo {
    private int id;
    private String name;

    public ViewInfo(int id, String name) {
        this.id = id;
        this.name = name;
    }

    public int getId() { return id; }

    public void setId(int id) { this.id = id; }

    public String getName() {  return name; }

    public void setName(String name) { this.name = name; }
}
```

- 接下来是 `ProxyInfo` 类，它会包含ViewInfo的列表，和生成一个Proxy类的完整信息，所以它负责代码生成方法的实现，一个ProxyInfo类只能生成一个类的代码，下面是ProxyInfo类代码，其中生成类文件使用了 `javapoet` 开源库，它提供了方便的生成类的操作 [Javapoet github地址](https://github.com/square/javapoet)

```java
compile 'com.squareup:javapoet:1.0.0'
```

```java
final class ProxyInfo {
    private static final String PROXY = "Proxy";
    /**
     * 包名
     */
    private String packageName;
    /**
     * 目标类名
     */
    private String targetClassName;
    /**
     * 代理类名
     */
    private String proxyClassName;
    /**
     * id到View信息映射
     */
    private Map<Integer, ViewInfo> idViewMap = new LinkedHashMap<>();

    ProxyInfo(String packageName, String targetClassName) {
        this.packageName = packageName;
        this.targetClassName = targetClassName;
        /* TargetClassName$$Proxy */
        this.proxyClassName = targetClassName + "$$" + PROXY;
    }

    /**
     * 添加View信息
     * @param id view id
     * @param viewInfo view info
     */
    void putViewInfo(int id, ViewInfo viewInfo) {
        idViewMap.put(id, viewInfo);
    }

    /**
     * 获取目标类名
     */
    private String getTargetClassName() {
        return targetClassName.replace("$", ".");
    }

    /**
     * 生成Java代码
     */
    void generateJavaCode(ProcessingEnvironment processingEnv) throws IOException {
        final ClassName FINDER_STRATEGY =
                ClassName.get("com.runing.example.simpleviewinject_api", "FindStrategy");
        final ClassName ABSTRACT_INJECTOR =
                ClassName.get("com.runing.example.simpleviewinject_api", "AbstractInjector");
        final TypeName T = TypeVariableName.get("T");

        /*生成方法*/
        MethodSpec.Builder builder = MethodSpec.methodBuilder("inject")
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .returns(void.class)
                .addAnnotation(Override.class)
                .addParameter(FINDER_STRATEGY, "finder", Modifier.FINAL)
                .addParameter(T, "target", Modifier.FINAL)
                .addParameter(TypeName.OBJECT, "source");

        for (Map.Entry<Integer, ViewInfo> viewInfoEntry : idViewMap.entrySet()) {
            ViewInfo info = viewInfoEntry.getValue();
            builder.addStatement("target.$L = finder.findViewById(source, $L)",
                    info.getName(), String.valueOf(info.getId()));
        }
        MethodSpec inject = builder.build();

        /*生成类*/
        String className = proxyClassName;
        TypeSpec proxyClass = TypeSpec.classBuilder(className)
                .addModifiers(Modifier.FINAL)
                .addTypeVariable(TypeVariableName.get("T extends " + getTargetClassName()))
                .addSuperinterface(ParameterizedTypeName.get(ABSTRACT_INJECTOR, T))
                .addMethod(inject)
                .build();
        JavaFile javaFile = JavaFile.builder(packageName, proxyClass).build();
		/*生成类文件*/
        javaFile.writeTo(processingEnv.getFiler());
    }
}
```

- 最后就是注解处理器的实现了，建立 `ViewInjectProcessor` 类，它可以处理所有类中使用了 `ViewInject` 注解的元素，从而获取我们需要的信息来生成代理类，主要是获取View的信息，和目标类的包名和类名，把所获取的信息封装在ProxyInfo中，最后生成类代码

在实现Java注解处理器时，需要建立 `META-INF/services/javax.annotation.processing.Processor` 标识文件，这里我们采用 `@ AutoService` 注解，注解在自定义的注解处理器类上，它将帮我们自动建立标识文件，它是google的 `auto-service`开源库里的注解

```java
compile 'com.google.auto.service:auto-service:1.0-rc2'
```

下面是代码

```java
@AutoService(Processor.class)
public final class ViewInjectProcessor extends AbstractProcessor {

    private Map<String, ProxyInfo> proxyInfoMap = new LinkedHashMap<>();

    private Elements elementsUtils;

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        /*需要处理的注解*/
        return Collections.singleton(ViewInject.class.getCanonicalName());
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        /*发布版本*/
        return SourceVersion.RELEASE_7;
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        elementsUtils = processingEnv.getElementUtils();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        /*遍历所有类元素*/
        for (Element e : roundEnv.getElementsAnnotatedWith(ViewInject.class)) {
            /*只处理成员变量*/
            if (e.getKind() != ElementKind.FIELD) {
                continue;
            }
            VariableElement variableElement = (VariableElement) e;
            TypeElement typeElement = (TypeElement) e.getEnclosingElement();
            PackageElement packageElement = elementsUtils.getPackageOf(typeElement);

            String kClassName;
            String packageName;
            String className;

            /*获取类型信息*/
            kClassName = typeElement.getQualifiedName().toString();
            packageName = packageElement.getQualifiedName().toString();
            className = getClassNameFromType(typeElement, packageName);

            /*对应View信息*/
            int id = variableElement.getAnnotation(ViewInject.class).value();
            String fieldName = variableElement.getSimpleName().toString();
            String fieldType = variableElement.asType().toString();

            printNote("annotated field : fieldName = "
                    + variableElement.getSimpleName().toString()
                    + " , id = " + id + " , fileType = " + fieldType);

            /*寻找已存在的类型*/
            ProxyInfo proxyInfo = proxyInfoMap.get(kClassName);
            /*如果是新类型*/
            if (proxyInfo == null) {
                proxyInfo = new ProxyInfo(packageName, className);
                proxyInfoMap.put(kClassName, proxyInfo);
            }
            proxyInfo.putViewInfo(id, new ViewInfo(id, fieldName));
        }
        //生成对应的代理类
        for (Map.Entry<String, ProxyInfo> proxyInfoEntry : proxyInfoMap.entrySet()) {
            ProxyInfo info = proxyInfoEntry.getValue();
            try {
                info.generateJavaCode(processingEnv);
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        }
        return true;
    }

    /* 从TypeElement获取包装类型 */
    private static String getClassNameFromType(TypeElement element, String packageName) {
        int packageLen = packageName.length() + 1;
        return element.getQualifiedName().toString()
                .substring(packageLen).replace('.', '$');
    }

    /* 输出信息 */
    private void printNote(String note) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, note);
    }

}
```

现在我们已经完成了注解处理器的所有功能，假设所有的代理类已经生成，我们还需要编写一个注入器用来调用这些代理类，来注入目标类的View中，代理类与目标类包名相同的情况下，它主要是根据目标类名来推断代理类名，然后利用  `newInstance` 方法创建代理类对象，对它们的方法进行调用，这里把它们的对象缓存在了LinkedHashMap中，以供重复使用

```java
public final class SimpleViewInjector {

    private SimpleViewInjector() {
        throw new AssertionError("no instance!");
    }

    /**
     * 缓存注解器对象
     */
    private static final Map<Class<?>, AbstractInjector<Object>> INJECTORS =
            new LinkedHashMap<>();

    /**
     * 注入AActivity
     */
    public static void inject(Activity activity) {
        AbstractInjector<Object> injector = findInjector(activity);
        injector.inject(FindStrategy.ACTIVITY, activity, activity);
    }

    /**
     * 注入ViewHolder
     *
     * @param target 目标VH
     * @param view   父View
     */
    public static void inject(Object target, View view) {
        AbstractInjector<Object> injector = findInjector(target);
        injector.inject(FindStrategy.VIEW, target, view);
    }

    @SuppressWarnings("unchecked")
    private static AbstractInjector<Object> findInjector(Object target) {
        Class<?> clazz = target.getClass();
        AbstractInjector<Object> injector = INJECTORS.get(clazz);
        if (injector == null) {
            try {
                /*生成代理类对象*/
                Class<?> injectorClazz = Class.forName(clazz.getName()
                        + "$$Proxy");
                injector = (AbstractInjector<Object>) injectorClazz.newInstance();
                INJECTORS.put(clazz, injector);
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            } catch (InstantiationException e) {
                e.printStackTrace();
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        }
        return injector;
    }

}
```

OK，所有的类都已经完成编写，现在看看Project结构

> **SimpleViewInject-api** Android Library

>> `AbstractInjector` 代理类抽象接口

>> `FindStrategy` findView策略类

>> `SimpleViewInjector` 注入器类

> **SimpleViewInject-annotation** Java Library

>> `ViewInject` 注解类

> **SimpleViewInject-compuiler** Java Library

>> `ProxyInfo` 代理类信息类

>> `ViewInfo` View信息类

>> `ViewInjectProcessor` 注解处理器

<h3 id="测试b">测试</h3>

测试代码很简单，在MainActivity中实现了一个带有ViewHolder的ListAdapter，使用注解绑定了一个Button、一个TextView、一个ListView，ViewHolder里还绑定了一个TextView

需要注意的是，View成员变量和ViewHolder类不能为私有的，应该为包级私有，因为生成的代理类将会访问它们，直接赋予结果

```java
public final class MainActivity extends AppCompatActivity {

    @ViewInject(R.id.tv_bottom)
    TextView mTextView;

    @ViewInject(R.id.btn_bottom)
    Button mButton;

    @ViewInject(R.id.lv_content)
    ListView mListView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SimpleViewInjector.inject(this);

        mButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mTextView.setText(getResources().getText(R.string.app_name));
            }
        });
        mListView.setAdapter(new MyAdapter());
    }

    static final class MyAdapter extends BaseAdapter {

        @Override
        public int getCount() {  return 20; }

        @Override
        public Object getItem(int position) { return null; }

        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = View.inflate(parent.getContext(), R.layout.item_list, null);
                holder = new ViewHolder(convertView);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }
            holder.textView.setText(String.valueOf(position));
            return convertView;
        }

        static final class ViewHolder {

            @ViewInject(R.id.tv_item)
            TextView textView;

            ViewHolder(View itemView) {
                SimpleViewInjector.inject(this, itemView);
            }
        }
    }
}
```

编译后可以发现 `Sample_SimpleViewInject` 的 `build/generated/source/apt/debug` 目录下生成了两个java文件

```java
package com.runing.example.sample_simpleviewinject;

import com.runing.example.simpleviewinject_api.AbstractInjector;
import com.runing.example.simpleviewinject_api.FindStrategy;
import java.lang.Object;
import java.lang.Override;

final class MainActivity$$Proxy<T extends MainActivity> implements AbstractInjector<T> {
  @Override
  public final void inject(final FindStrategy finder, final T target, Object source) {
    target.mTextView = finder.findViewById(source, 2131492946);
    target.mButton = finder.findViewById(source, 2131492947);
    target.mListView = finder.findViewById(source, 2131492945);
  }
}
```

```java
package com.runing.example.sample_simpleviewinject;

import com.runing.example.simpleviewinject_api.AbstractInjector;
import com.runing.example.simpleviewinject_api.FindStrategy;
import java.lang.Object;
import java.lang.Override;

final class MainActivity$MyAdapter$ViewHolder$$Proxy<T extends MainActivity.MyAdapter.ViewHolder> implements AbstractInjector<T> {
  @Override
  public final void inject(final FindStrategy finder, final T target, Object source) {
    target.textView = finder.findViewById(source, 2131492948);
  }
}
```

效果展示

![](/image/android_view_injector/20160619204153962.gif)

## **项目代码**

> [https://github.com/wangruning/ViewInjectTest](https://github.com/wangruning/ViewInjectTest)

## **参考**

> - http://blog.csdn.net/lmj623565791/article/details/43452969

> - http://www.cnblogs.com/avenwu/p/4173899.html

> - https://github.com/JakeWharton/butterknife

> - http://brianattwell.com/android-annotation-processing-pojo-string-generator/