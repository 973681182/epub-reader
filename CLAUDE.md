# EPUB 终端阅读器

## 项目概述

Java 11 Maven 项目，在终端中阅读 EPUB 电子书。使用 JLine + JNA 实现 Windows 原生终端交互，支持交替屏幕缓冲区和 raw mode 单键输入。三种运行模式：书架（LIBRARY）和阅读（READING），两种模式下均可按 `/` 内联激活命令行，无需切换到独立命令画面。

## 构建与运行

```bash
mvn package -DskipTests                          # 编译打包，输出到 dist/
java -jar dist/epub-reader.jar                   # 直接运行（书架模式）
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
│   └── StorageManager.java       持久化：library.json + progress/，手写 JSON 解析
└── ui/
    ├── TerminalUI.java           主控制器：模式循环 + 按键处理 + 命令输入状态机
    ├── ScreenRenderer.java       ANSI 屏幕绘制 + 光标样式 + 展开式命令面板
    ├── Mode.java                 应用模式枚举：LIBRARY / COMMAND / READING
    ├── Command.java              命令枚举（唯一数据源）：名称、描述、可用模式、match/parse
    ├── CommandHandler.java       命令解析与执行：/read /toc /goto /progress /info /help /back
    └── CommandResult.java        命令执行结果枚举
```

## 交互模式

### 书架模式（LIBRARY）
```
  ↑↓ 选择书籍    Enter 打开阅读    a 预填 /add    d 预填 /delete
  / 激活命令行    q 退出程序
```

### 阅读模式（READING）
```
  Enter/空格/↓/→  下一页    ↑/←  上一页
  / 激活命令行      Esc  返回书架
```

### 命令行（两种模式下按 `/` 激活）
```
  内联展开面板覆盖底部区域，不切换画面：
  - 输入区：青色 > 提示符 + 输入文本 + 灰色补全后缀
  - 匹配区：最多 5 条匹配命令，↑↓ 选择，高亮当前选中
  - 提示区：左侧通用提示（灰色） + 右侧结果（绿色成功/红色错误）
  - 左右放不下时右侧结果自动换到下一行
  - Enter 执行（先自动补全选中命令）  Esc 取消
  - Tab 补全命令或文件路径（/add 时）
  - ↑↓ 浏览命令历史（无匹配命令时）
```

## 命令系统

### Command 枚举（`Command.java`）— 唯一数据源

所有命令在此定义，包含名称、描述和可用模式。帮助画面和补全列表均由此动态生成。

| 命令 | 可用模式 | 说明 |
|------|---------|------|
| `/read [n]` | READING, LIBRARY | 进入/跳转阅读（书架需指定序号） |
| `/toc` | READING | 显示章节目录 |
| `/goto <页码\|百分比%>` | READING | 跳转到指定位置 |
| `/progress` | READING | 显示详细阅读进度 |
| `/info` | READING | 显示书籍元信息 |
| `/back` | READING | 返回书架 |
| `/add <路径>` | LIBRARY | 添加 EPUB 到书架（二次确认） |
| `/delete <序号\|书名>` | LIBRARY | 从书架移除（二次确认） |
| `/help` | 全部 | 显示命令帮助 |
| `/quit`, `/exit` | 全部 | 退出程序（书架）/ 返回书架（阅读） |

### 模式校验
- `Command.parse(input, mode)` 按当前模式匹配命令，模式不匹配时返回 null
- `Command.match(input, mode)` 用于 Tab 补全，只返回当前模式可用的命令
- `Command.forMode(mode)` 返回指定模式下所有可用命令

### 添加/删除二次确认流程
1. 用户输入 `/add <路径>` 或 `/delete <书名>`
2. 系统暂存确认信息（`pendingConfirmType/Path/Index/Title`）
3. 底部提示变为"Enter 确认  任意键取消"
4. Enter → 执行动作并清除暂存；其他有效按键 → 取消暂存
5. 确认期间方向键不触发取消

## 关键技术细节

### 按键处理（TerminalUI）
三种读取方法，适用于不同场景：

- **`readKey()`** — 书架/阅读导航用。ESC 序列合并为方向键，超时 500ms
- **`readKeyInput()`** — 命令输入用。额外支持 Home/End/Delete/Tab，超时 300ms
- **`readKeyRaw()`** — 输入框用。ESC 立即逃逸，方向键被吞掉

所有方法基于：
- `readOneChar()` 阻塞读首字节
- `pollChar(timeoutMs)` 短超时轮询（≤30ms 间隔），因为 JLine `read(timeout)` 在 Windows 上行为不可靠
- 支持两种方向键编码：`ESC [ X`（ANSI）和 `ESC O X`（Windows 应用模式）

### 交替屏幕缓冲区
- 启动：`\033[?1049h` → 进入交替屏幕（无回滚历史）
- 退出：`\033[?1049l` → 恢复主屏幕

### 光标样式管理
- 启动时设置：方块光标 `\033[2 q` + 亮灰色 `\033]12;#c0c0c0\007`
- 非输入状态：`\033[?25l` 隐藏光标
- 输入状态：`\033[?25h` 显示光标，定位到输入位置（CJK 感知）
- 退出时：`\033]112\007\033[0 q` 恢复默认

### CJK 显示宽度
- `PageRenderer.getCharDisplayWidth(char)` — 权威实现，检查 UnicodeBlock 范围
- 涵盖：CJK 统一表意文字（含扩展 A/B）、日韩文字、全角符号、中文标点、表情符号等
- **注意**：不包含 `0x2000..0x206F`（通用标点），此范围内的字符如 EM DASH（—）、省略号（…）等属于
  ambiguous-width，Windows 终端实际渲染为宽度 1，标为宽度 2 会导致分页换行和行填充异常
- `ScreenRenderer.displayWidth()` / `padRightVisual()` — 使用上述方法做视觉宽度计算
- 分页和命令面板布局均基于视觉宽度，非字符数

### 段落分页（PageRenderer）
- 段首缩进：两个全角空格 `　　`（共占 4 列）
- 按段落分行 → 宽度感知自动换行 → 按 pageRows 切分页面
- 空白行保留作为段落分隔
- pageRows = terminalHeight - 4（状态栏 1 + 命令面板边框 2 + 命令输入行 1）

### EpubParser HTML→纯文本
- 手动遍历 DOM 树，不在 `text()`/`wholeText()` 上做正则（它们不保留段落结构）
- 块级元素前插入 `\n`，`<br>` 转为 `\n`
- 跳过 `<script>`/`<style>`/`<noscript>`
- 规范化空白：去行首尾空白、合并 3+ 连续空行为单个空行

### 数据目录优先级
1. JAR 位于 `dist/` 目录内 → `dist/.epub-reader/`
2. 当前工作目录存在 `dist/` 子目录 → `<cwd>/dist/.epub-reader/`
3. 回退 → `~/.epub-reader/`

数据目录结构：
```
.epub-reader/
├── library.json      书架列表
└── progress/          每本书一个进度文件
    └── <路径转安全文件名>.txt
```

### 进度持久化
- 格式：`章节索引,页码`（例如 `3,12`），约 10 字节
- 翻页时自动保存，不累积数据

### 书架持久化
- `library.json`，手动 JSON 序列化（不依赖第三方 JSON 库）
- 只用 `\` 和 `"` 转义，不用 `\n` `\r` `\t`（避免与 Windows 路径如 `\new`、`\test` 冲突）
- 每条记录含：path, title, author, lastReadAt
- `StorageManager` 内含手写 JSON 解析器（`splitJsonObjects`, `extractString`, `extractInt`）

### 自动扫描 books/ 目录
- 启动时扫描 `books/` 或 `../books/` 下的 `.epub` 文件
- 按规范化路径去重，自动添加到书架
- 放在书架模式主循环外执行，只扫描一次

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
- 终端窗口缩放时不会自动重新分页（需按任意键触发重绘循环才会更新尺寸）

## 待开发功能（TODO.md）

- 自定义配置文件（dist/ 或用户目录下），考虑与书籍索引/书签的关系
- 阅读时长统计
- 命令结果以覆盖层形式显示在阅读页面上方（而非全屏切换）
- 终端缩放时实时刷新
- 行间距、字体大小、页面字数等显示调节
- 隐蔽模式（如 boss key 功能）
- 连接外部 API 获取书籍资源

## src/main/launcher/

包含 Windows 启动脚本，`mvn package` 时自动复制到 `dist/`：
- `epub-reader.bat` — 用 `java.exe` 启动，设置 UTF-8 和堆大小
- `epub-reader.vbs` — 双击启动，无控制台窗口闪烁
