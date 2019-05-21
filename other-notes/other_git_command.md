# Git 常用命令

- [创建库](#创建库)
- [版本控制](#版本控制)
- [分支管理](#分支管理)
- [远程库](#远程库)

## 创建库

- 首次安装后设置名字和 email 地址。

`git config --global user.name "Your Name"`

`git config --global user.email "email@example.com"`

- 进入目录并初始化为 git 仓库。

`git init`

- 添加文件到缓冲区。

`git add 文件/路径名`

- 提交并添加注释。

`git commit -m "注释"`

- 修改最近的提交注释。

`git commit -amend`

- 查看当前仓库状态。

`git status`

- 查看提交日志。

`git log [--pretty=oneline] (参数为简化显示)`

## 版本控制

- 回退版本。

`git reset --hard HEAD^`

- 撤销暂存区的文件

`git reset HEAD <file>`

- 查看命令记录。

`git reflog`

- 查看修改部分。

`git diff HEAD -- 文件名`

- 放弃工作区的修改。

`git checkout -- readme.txt`

- 删除并添加到缓存区。

`git rm test.txt`

- 创建 ssh Key。

`ssh-keygen -t rsa -C "youremail@example.com"`

- 添加到远程库。

`git remote add origin git@github.com:你的远程库 or https://github.com/你的远程库`

- 首次推到远程库。

`git push -u origin master`

- 推到远程库。

`git push origin master`

- 克隆远程库到本地。

`git clone git@github.com:你的远程库 or https://github.com/你的远程库`

## 分支管理

- 创建并切换到目标分支。

`git checkout -b [branch]`

- 切换到目标分支。

`git branch [branch]`

- 查看分支。

`git branch`

- 查看分支合并图

`git log --graph`

- 合并目标分支到当前分支，相当于指针直接指向目标分支。

`git merge [branch]`

- 合并目标分支到当前分支，禁用 `Fast forward` 模式。

`git merge --no-ff -m "no-ff" dev`

- 删除目标分支。

`git branch -d [branch]`

- 合并分支并禁用 Fast forward 模式。

`git merge --no-ff -m "merge with no-ff" dev`

- 保存当前工作现场。

`git stash`

- 查看保存的 stash。

`git stash list`

- 恢复并删除 stash 内容。

`git stash pop`

- 恢复 stash 内容。

`git stash apply stash@{0}`

- 删除 stash 内容。

`git stash drop`

- 强行删除目标分支。

`git branch -D dev`

## 远程库

- 查看远程库信息。

`git remote [-v] 详细`

- 指定本地与远程分支链接。

`git branch --set-upstream dev origin/dev`

- 抓取远程库最新提交。

`git pull`

- 添加标签。

`git tag v1.0`