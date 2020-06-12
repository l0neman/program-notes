# Makefile 编写

[TOC]

## 编写原则

1. 如果这个工程没有编译过，那么所有的 C 文件都需要编译并链接；
2. 如果这个工程的某几个 C 文件被修改，那么只编译被修改的 C 文件，并连接目标程序；
3. 如果这个工程的头文件被改变了，那么需要编译引用了这几个头文件的 C 文件，并连接目标程序。



## Makefile 规则

```makefile
target ...: prerequisites ...
    command
    ...
    ...
```

- target

一个目标文件（Object File），或一个可执行文件，或一个标签（Label）

- prerequisites

生成该 target 所依赖的文件以或/和 target

- command

该 target 要执行的命令



# Makefile 示例

```makefile
edit: main.o kbd.o command.o display.o \
      insert.o search.o files.o utils.o
      cc -o edit main.o kbd.o command.o display.o \
      insert.o search.o files.o utils.o

main.o:    main.c defs.h
           cc -c main.c
kbd.o:     kdb.c defs.h command.h
           cc -c command.c
command.o: commnd.c defs.h command.h
           cc -c command.c
display.o: display.c defs.h buffer.h
           cc -c dislay.c
insert.o:  insert.c defs.h buffer.h
           cc -c insert.c
serch.o:   search.c defs.h buffer.h
           cc -c search.c
files.o:   files.c defs.h buffer.h command.h
           cc -c files.c
utils.o:   utils.c defs.h
           cc -c utils.c
clean:
           rm edit main.o kbd.o command.o display.o \
           insert.o search.o files.o utils.o
```