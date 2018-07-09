# Git 常用命令

[TOC]

## 创建库

- 首次安装后设置名字和email地址 

`git config --global user.name "Your Name"`

`git config --global user.email "email@example.com"`

- 进入目录并指定为git仓库 

`git init`

- 添加文件到缓冲区 

`git add 文件/路径名`

- 提交并添加注释 

`git commit -m "注释"`

- 查看当前仓库状态 

`git status`

- 查看提交日志 

`git log [--pretty=oneline] (参数为简化显示方)`

## 版本控制

- 回退版本 

`git reset --hard HEAD^`

- 查看命令记录 

`git reflog`

- 查看修改部分 

`git diff HEAD -- 文件名`

- 放弃修改 

`git checkout -- readme.txt`

- 删除 

`git rm test.txt`

- 创建ssh Key 

`ssh-keygen -t rsa -C "youremail@example.com"`

- 添加到远程库 

`git remote add origin git@github.com:你的远程库 or https://github.com/你的远程库`

- 首次推到远程库 

`git push -u origin master`

- 推到远程库 

`git push origin master`

- 克隆远程库到本地 

`git clone git@github.com:你的远程库 or https://github.com/你的远程库`

## 分支管理

- 创建并切换分支dev 

`git checkout -b dev`

- 切换dev分支 

`git branch dev

- 查看分支 

`git branch`

- 合并dev分支到 

`git merge dev`

- 删除分支dev 

`git branch -d dev`

- 合并分支并禁用Fast forward模式 

`git merge --no-ff -m "merge with no-ff" dev`

- 保存当前 

`git stash`

- 查看保存的工作 

`git stash list`

- 恢复并删除stash内容 

`git stash pop`

- 恢复stash内容 

`git stash apply stash@{0}`

- 删除stash内容 

`git stash drop`

- 强行删除dev分支 

`git branch -D dev`

## 远程库

- 查看远程库信息 

`git remote [-v] 详细`

- 指定本地与远程分支链接 

`git branch --set-upstream dev origin/dev`

- 抓取远程库最新提交 

`git pull`

- 添加标签 

`git tag v1.0`