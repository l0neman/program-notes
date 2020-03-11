# Xfermode 使用方法

## 示例

```java
Paint mPaint       = new Paint(Paint.ANTI_ALIAS_FLAG);
Xfermode mXfermode = new PorterDuffXfermode(PorterDuff.Mode.SRC_IN);

...
int sc = canvas.saveLayer(...)  // 创建离屏缓存，防止绘制干扰。
canvas.drawRect(..., mPaint);   // 绘制 dst 图形。
mPaint.setXfermode(mXfermode);  // 设置 Xfermode。
canvas.drawBitmap(..., mPaint); // 绘制 src 图形。
canvas.restoreToCount(sc);      // 恢复到画布。
```

## 参考

![](./xfermode.jpg)