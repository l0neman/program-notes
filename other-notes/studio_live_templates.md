# Android Studio Live Templates

[TOC]

## Android

### const

Define android style int const.

```java
private static final int $name$ = $value$;
```

### fbc

findViewById with cast.

```java
($cast$) findViewById(R.id.$resId$);
```

### foreach

Create a for each loop.

```java
for ($i$ : $data$) {
    $cursor$
}
```

### gone

Set view visibility to GONE.

```java
$VIEW$.setVisibility(android.view.View.GONE);
```

### IntentView

Creates an Intent with ACTION_VIEW.

```java
android.content.Intent view = new Intent();
view.setAction(Intent.ACTION_VIEW);
view.setData(android.net.Uri.parse($url$));
startActivity(view);
```

### key

Key for a bundle.

```java
private static final String KEY_$value$ = "$value$";
```

### newInstance

create a new Fragment instance with arguments.

```java
public static $fragment$ newInstance($args$) {
    $nullChecks$
    android.os.Bundle args = new Bundle();
    $addArgs$
    $fragment$ fragment = new $fragment$();
    fragment.setArguments(args);
    return fragment;
}
```

### noInstance

private empty constructor to prohibit instance creation.

```java
private $class$() {
    //no instance
}
```

### rgS

get a String from resources

```java
$resources$.getString(R.string.$stringId$)
```

###  rouiT

runOnUIThread.

```java
getActivity().runOnUiThread(new java.lang.Runnable() {
    @Override
    public void run() {
        $cursor$
    }
});
```

### sbc

block comment for structuring code.

```java
///////////////////////////////////////////////////////////////////////////
// $blockName$
///////////////////////////////////////////////////////////////////////////
```

### Sfmt

String format.

```java
String.format("$string$", $params$);
```

### starter

Creates a static start(...) helper method to start an Activity.

```java
public static void start(android.content.Context context) {
    android.content.Intent starter = new Intent(context, $ACTIVITY$.class);
    starter.putExtra($CURSOR$);
    context.startActivity(starter);
}
```

### Toast

Create a new Toast.

```java
android.widget.Toast.makeText($context$, "$text$", Toast.LENGTH_SHORT).show();
```

### ViewConstructors

Adds generic view constructors.

```java
public $class$(android.content.Context context) {
    this(context, null);
}

public $class$(Context context, android.util.AttributeSet attrs) {
    this(context, attrs, 0);
}

public $class$(Context context, AttributeSet attrs, int defStyle) {
    super(context, attrs, defStyle);
    $cursor$
}
```

### visible

Set view visibility to VISIBLE.

```java
$VIEW$.setVisibility(View.VISIBLE);
```

### wrapIt

adds a gradle wrapper task.

```gro
task wrapper(type: Wrapper) {
    gradleVersion = "$version$"
}
```

