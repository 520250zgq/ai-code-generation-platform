# Git 远程仓库推送说明

本文档说明如何把本项目推送到 GitHub 远程仓库，供其他开发者手动执行。

- 仓库主页：<https://github.com/520250zgq/ai-code-generation-platform>
- SSH 地址：`git@github.com:520250zgq/ai-code-generation-platform.git`
- HTTPS 地址：`https://github.com/520250zgq/ai-code-generation-platform.git`
- 默认分支：`main`

> 推荐使用 SSH 方式，配置一次密钥后无需每次输入账号密码。

## 1. 前置条件

| 工具 | 说明 |
| --- | --- |
| Git | 建议 2.30 及以上，`git --version` 检查 |
| OpenSSH | Windows 10/11 自带；`ssh -V` 检查 |
| GitHub 账号 | 需要有该仓库的写入权限（仓库所有者或 Collaborator） |

首次使用 Git 时先配置提交身份：

```bash
git config --global user.name "你的名字"
git config --global user.email "你的邮箱@example.com"
```

## 2. 生成 SSH 密钥（已有密钥可跳过）

```bash
ssh-keygen -t ed25519 -C "你的邮箱@example.com"
```

按提示一路回车即可；建议设置一个密钥口令（passphrase），更安全。
Windows 下密钥默认位于 `C:\Users\<用户名>\.ssh\`，Linux/macOS 下位于 `~/.ssh/`。

查看公钥内容：

```powershell
# Windows PowerShell
Get-Content $env:USERPROFILE\.ssh\id_ed25519.pub
```

```bash
# Git Bash / Linux / macOS
cat ~/.ssh/id_ed25519.pub
```

## 3. 把公钥添加到 GitHub

1. 打开 <https://github.com/settings/ssh/new>（GitHub → Settings → SSH and GPG keys → New SSH key）。
2. `Title` 填写便于识别的名称，例如 `work-laptop`。
3. `Key type` 保持 `Authentication Key`。
4. 把第 2 步输出的整行公钥（以 `ssh-ed25519` 或 `ssh-rsa` 开头）粘贴到 `Key` 输入框。
5. 点击 `Add SSH key`。

> 注意：粘贴的是 `.pub` 公钥文件内容，绝对不要上传没有 `.pub` 后缀的私钥。

## 4. 测试 SSH 连接

```bash
ssh -T git@github.com
```

首次连接会询问是否信任主机指纹，输入 `yes` 回车。成功时输出类似：

```text
Hi 520250zgq! You've successfully authenticated, but GitHub does not provide shell access.
```

看到 `Hi <用户名>!` 即表示认证成功（该命令返回非 0 退出码是正常现象）。

如果密钥设置了口令，需要先加载到 ssh-agent：

```powershell
# Windows：以管理员身份打开 PowerShell，启动并设置 ssh-agent 开机自启
Set-Service ssh-agent -StartupType Automatic
Start-Service ssh-agent
ssh-add $env:USERPROFILE\.ssh\id_ed25519
```

```bash
# Git Bash / Linux / macOS
eval "$(ssh-agent -s)"
ssh-add ~/.ssh/id_ed25519
```

## 5. 克隆仓库（新开发者）

```bash
git clone git@github.com:520250zgq/ai-code-generation-platform.git
cd ai-code-generation-platform
```

## 6. 关联远程仓库并首次推送

如果本地已有项目、还没有远程地址：

```bash
# 在项目根目录执行
git remote add origin git@github.com:520250zgq/ai-code-generation-platform.git

# 确认当前分支名为 main（不是 master 时重命名）
git branch -M main

# 首次推送并建立上游跟踪关系
git push -u origin main
```

如果已经配置过远程地址，需要修改时：

```bash
git remote set-url origin git@github.com:520250zgq/ai-code-generation-platform.git
```

检查远程配置是否正确：

```bash
git remote -v
# origin  git@github.com:520250zgq/ai-code-generation-platform.git (fetch)
# origin  git@github.com:520250zgq/ai-code-generation-platform.git (push)
```

## 7. 日常推送流程

```bash
# 1. 查看改动
git status

# 2. 暂存并提交（提交信息遵循 Conventional Commits）
git add .
git commit -m "feat: 完成 xxx 功能"

# 3. 推送当前分支
git push
```

提交信息建议格式：

| 前缀 | 用途 | 示例 |
| --- | --- | --- |
| `feat` | 新功能 | `feat: 完成 AI 工作流初步搭建` |
| `fix` | 修复缺陷 | `fix: 修复 SSE 断流问题` |
| `docs` | 文档变更 | `docs: 添加远程推送说明` |
| `refactor` | 重构 | `refactor: 拆分工作流节点` |
| `test` | 测试相关 | `test: 补充路由节点测试` |
| `chore` | 构建/配置 | `chore: 升级依赖版本` |

## 8. 常见问题

### 8.1 `Permission denied (publickey)`

说明 GitHub 没有接受本机的 SSH 公钥，按顺序排查：

```bash
# 查看当前加载了哪些密钥
ssh-add -l

# 查看 SSH 实际使用了哪把密钥、被拒绝的过程
ssh -vT git@github.com
```

- 确认公钥已添加到**正确的 GitHub 账号**（本仓库为 `520250zgq`）。
- 确认 `~/.ssh/config` 没有把 `github.com` 指向其他密钥。
- 使用多把密钥时，可在 `~/.ssh/config` 中显式指定：

```text
Host github.com
  HostName github.com
  User git
  IdentityFile ~/.ssh/id_ed25519
  IdentitiesOnly yes
```

### 8.2 `Host key verification failed`

把 GitHub 主机指纹加入 `known_hosts`：

```bash
ssh-keyscan github.com >> ~/.ssh/known_hosts
```

### 8.3 22 端口被网络屏蔽

改用 GitHub 的 443 端口：

```bash
ssh -T -p 443 git@ssh.github.com
```

或在 `~/.ssh/config` 中配置：

```text
Host github.com
  HostName ssh.github.com
  Port 443
  User git
```

### 8.4 `rejected - non-fast-forward`

远程分支有本地没有的提交，先拉取再推送：

```bash
git pull --rebase origin main
# 解决冲突后
git add .
git rebase --continue
git push
```

### 8.5 远程仓库初始化过 README，首次推送被拒绝

允许合并无关历史后再推送：

```bash
git pull origin main --allow-unrelated-histories
git push -u origin main
```

### 8.6 `Authentication failed`（HTTPS 方式）

GitHub 已不支持账号密码推送，HTTPS 方式需要使用 Personal Access Token（PAT）代替密码；
Windows 下也可以使用 Git Credential Manager 弹出的浏览器登录完成授权。推荐优先使用 SSH。

## 9. 安全约定

- `application-local.yml` 存放本地密钥和数据库密码，已被 `.gitignore` 忽略，**禁止提交**。
- `cookies-cmd.txt`、`cp.txt`、`target/`、`tmp/`、`node_modules/`、`dist/` 等调试产物和构建产物不要入库。
- 推送前可执行 `git status` 确认暂存内容，避免把密钥、日志、临时文件带入远程仓库。
- 如密钥意外泄露，应立即在对应平台（DeepSeek、腾讯云 COS、GitHub 等）重置，并清理 Git 历史。

## 10. 本次推送所用命令

```bash
git remote add origin git@github.com:520250zgq/ai-code-generation-platform.git
git push -u origin main
```

推送完成后验证：

```bash
git remote -v
git log --oneline -3
git ls-remote --heads origin
```
