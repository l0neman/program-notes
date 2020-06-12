# GDB 操作

## b（breakpoint） 设置断点

b main 设置函数断点

b * 0x4004f4 使用地址设置断点

b 5 设置行数断点

## r（run） 选项 执行程序，程序停止在第一个断点

## p（print） 打印变量值

p a 打印变量 a 的值
p {a, b} 同时打印两个变量的值

## d（delete breakpoint） 删除断点

## n 单行执行

## s 单步执行

## l（list） 列出源代码，每次 10 行

## c（continue） 继续执行程序

## q（quit） 退出调试

## i（info） 显示信息

## help [命令]

