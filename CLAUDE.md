# EPUB 终端阅读器

## 项目概述

Java 11 Maven 项目，在终端中阅读 EPUB 电子书。三模式切换：书架 → 命令 → 阅读。使用 JLine + JNA 实现 Windows 原生终端交互，支持交替屏幕缓冲区和 raw mode 单键输入。

## 构建与运行

```bash
mvn package -DskipTests                          # 编译打包，输出到 dist/
java -jar dist/epub-reader.jar                   # 直接运行
dist/epub-reader.vbs                             # 双击启动（Windows，调用 java.exe）
java -jar dist/epub-reader.jar <epub文件路径>    # 跳过书架直接打开文件
```

## 模块结构

```
com.xzy.epubreader
├── App.java                      入口：无参→书架模式；有参→直接打开文件
├── model/
│   ├── Book.java                 书籍模型：章节列表、翻页导航、进度计算
│   ├── Chapter.java              章节模型：标题、纯文本、分页列表
│   └── LibraryEntry.java         书架条目：路径、书名、作者
├── parser/
│   └── EpubParser.java           用 epublib+Jsoup 解析 EPUB，提取章节纯文本
├── renderer/
│   └── PageRenderer.java         按终端宽高分页，CJK 字符宽度=2
├── storage/
│   └── StorageManager.java       持久化：library.json(书架) + progress/目录(每书一个文件)
└── ui/
    ├── TerminalUI.java           主控制器：三模式循环 + 按键处理 + 交替屏幕
    ├── ScreenRenderer.java       ANSI 屏幕绘制：书架/命令/阅读三种画面 + 命令条
    ├── CommandHandler.java       斜杠命令解析：/read /toc /goto /progress /info /help /back
    └── CommandResult.java        命令执行结果枚举
```

## 三种模式

```
LIBRARY(书架) ←→ COMMAND(命令) ←→ READING(阅读)
  ↑↓选书 Enter打开   /read进入          Enter/空格/↓/→ 下页
  a添加 d移除 q退出   Esc回书架          ↑/← 上页  Esc退出
```

## 关键技术细节

### 按键处理（TerminalUI.readKey）
- `readOneChar()` 阻塞读首字节
- 若为 ESC(27)：`pollChar(500ms)` 轮询等第二个字节
- 支持两种方向键编码：`ESC [ X`（ANSI）和 `ESC O X`（Windows 应用模式）
- `pollChar()` 使用短超时轮询，因为 JLine `read(timeout)` 在 Windows 上行为不可靠

### 交替屏幕缓冲区
- 启动：`\033[?1049h` → 进入交替屏幕（无回滚历史）
- 退出：`\033[?1049l` → 恢复主屏幕

### 进度持久化
- 每本书单独文件：`~/.epub-reader/progress/<路径转安全文件名>.txt`
- 格式：`章节索引,页码`（例如 `3,12`），约 10 字节
- 翻页时自动保存，不累积数据

### 书架持久化
- `~/.epub-reader/library.json`，手动 JSON 序列化
- 只用 `\` 和 `"` 转义，不用 `\n` `\r` `\t`（避免与 Windows 路径 `\new`、`\test` 冲突）

### 编码
- 启动时 `chcp 65001` 切 Windows 控制台为 UTF-8
- 所有输出通过 JLine `terminal.writer()` 进行（不直接用 System.out）

## 依赖

| 依赖 | 用途 |
|------|------|
| com.positiondev.epublib:epublib-core:3.1 | EPUB 解析（原 nl.siegmann 的 fork） |
| org.jsoup:jsoup:1.17.2 | HTML→纯文本 |
| org.jline:jline:3.25.1 | 终端尺寸、Writer |
| org.jline:jline-terminal-jna:3.25.1 | Windows raw mode（必须显式依赖） |
| org.slf4j:slf4j-nop:1.7.36 | 静默日志（epublib 需要 slf4j-api） |
| junit:junit:4.13.2 | 测试 |

## 已知问题 & 注意事项

- Launch4j 生成 .exe 需要 MinGW（windres.exe），当前环境没有，用 .vbs 替代
- 翻页时 OOM：来自旧版 progress.json 累积过多数据，已改为每书单独小文件
- epublib-core 原版 `nl.siegmann.epublib:epublib-core:3.1` 在 Maven Central 不存在，改用 `com.positiondev.epublib` fork
- JLine 必须显式依赖 `jline-terminal-jna`，否则 Windows 上 raw mode 不生效
- 带中文路径的 EPUB：输入框允许所有 `c >= 32` 的字符（不限于 ASCII）

## src/main/launcher/

包含 Windows 启动脚本，`mvn package` 时自动复制到 `dist/`：
- `epub-reader.bat` — 用 `java.exe` 启动，设置 UTF-8 和堆大小
- `epub-reader.vbs` — 双击启动，无控制台窗口闪烁
