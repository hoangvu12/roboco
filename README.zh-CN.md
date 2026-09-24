# Roboco

在自己的机器上运行和管理编码 agent，包括 Claude Code、Codex、Cursor、Grok、Hermes、Pi 和 Antigravity。会话和文件由运行它们的引擎保存。

*[English](README.md) | 简体中文*

桌面应用直接连接本地引擎，无需 Roboco 账号。Roboco 支持 Windows 和 Linux，基于 [Zeron](https://github.com/zeronsh/zeron) 开发；上游改动按需移植。

## 安装

### Windows

```powershell
irm https://github.com/hoangvu12/roboco/releases/latest/download/install.ps1 | iex
```

安装到 `%LOCALAPPDATA%\Programs\Roboco` 并创建开始菜单快捷方式，之后应用会自行检查并完成应用内更新。用户数据保存在 `%LOCALAPPDATA%\Roboco`，安装与更新均不会触碰。

手动方式：从[发布页](https://github.com/hoangvu12/roboco/releases)下载便携版 ZIP，解压到任意可写目录后运行 `roboco.exe`（保留旁边的 `roboco-update.json` 以支持应用内更新）。

### Linux

从[发布页](https://github.com/hoangvu12/roboco/releases)下载压缩包并运行其中的 `install.sh`（免 root 安装到 `~/.local`）。

## 从源码运行

```bash
git clone https://github.com/hoangvu12/roboco
cd roboco
cargo run -p roboco
```

Windows 源码构建请参阅 [Windows 开发说明](docs/reference/windows-development.md)。

```bash
roboco headless    # 仅运行引擎
roboco status      # 查看本地引擎状态
roboco update      # 更新应用
```

## 远程访问

远程访问采用引擎直接配对。桌面客户端分别连接每个引擎；会话、队列和设置始终属于各自的引擎，不进行跨引擎同步，也不依赖账号服务或云中继。

实现进度见[远程访问规格和任务](.scratch/remote-access/spec.md)。内部结构见 [ARCHITECTURE.md](ARCHITECTURE.md)。

采用 [MIT License](LICENSE)。