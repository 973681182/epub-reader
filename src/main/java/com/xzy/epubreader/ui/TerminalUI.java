package com.xzy.epubreader.ui;

import com.xzy.epubreader.model.Book;
import com.xzy.epubreader.model.LibraryEntry;
import com.xzy.epubreader.parser.EpubParser;
import com.xzy.epubreader.renderer.PageRenderer;
import com.xzy.epubreader.storage.ConfigManager;
import com.xzy.epubreader.storage.ConfigManager.SettingItem;
import com.xzy.epubreader.storage.ConfigManager.SettingSection;
import com.xzy.epubreader.storage.StorageManager;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 终端交互主控制器：三模式（书架/命令/阅读）+ 交替屏幕缓冲区。
 */
public class TerminalUI {

    private final StorageManager storage;
    private final PageRenderer pageRenderer;
    private ConfigManager config;

    private Terminal terminal;
    private PrintWriter writer;
    private ScreenRenderer screen;

    private Mode mode;
    private Mode prevMode;  // 进入设置页面前的模式，退出时恢复
    private boolean running;

    // 书架
    private List<LibraryEntry> library;
    private int librarySelected;
    private String libraryMessage = null;
    private boolean libraryMessageIsError = false;

    // 确认暂存（添加/删除命令的二次确认）
    private String pendingConfirmType = null;   // "add" | "delete" | null
    private String pendingConfirmPath = null;   // add 的文件路径
    private int pendingConfirmIndex = -1;       // delete 的书架索引
    private String pendingConfirmTitle = null;  // 确认提示用的书名

    // 输入历史
    private final List<String> commandHistory = new ArrayList<>();
    private int commandHistoryIndex = -1;
    private String commandHistoryDraft = null;

    // 当前打开的书
    private Book currentBook;
    private CommandHandler commandHandler;
    private String currentBookPath;

    public TerminalUI() {
        this.storage = new StorageManager();
        this.pageRenderer = new PageRenderer();
        this.mode = Mode.LIBRARY;
        this.running = true;
        this.library = new ArrayList<>();
        this.librarySelected = 0;
    }

    // ==================== 启动 ====================

    public void start() throws IOException {
        storage.init();
        library = storage.loadLibrary();
        config = new ConfigManager(storage.getDataDir());
        scanBooksDirectory();
        initTerminal();

        try {
            while (running) {
                updateTerminalSize();
                switch (mode) {
                    case LIBRARY:  libraryLoop(); break;
                    case COMMAND:  commandLoop(); break;
                    case READING:  readingLoop(); break;
                    case SETTINGS: settingsLoop(); break;
                }
            }
        } finally {
            shutdown();
        }
    }

    public void openDirectly(String filePath) throws IOException {
        storage.init();
        library = storage.loadLibrary();
        config = new ConfigManager(storage.getDataDir());
        scanBooksDirectory();
        initTerminal();

        try {
            openBook(filePath);
            if (currentBook != null) {
                mode = Mode.READING;
                while (running) {
                    updateTerminalSize();
                    switch (mode) {
                        case READING:  readingLoop(); break;
                        case SETTINGS: settingsLoop(); break;
                        default: break;
                    }
                    if (mode != Mode.READING && mode != Mode.SETTINGS) break;
                }
            }
        } finally {
            shutdown();
        }
    }

    private void initTerminal() throws IOException {
        terminal = TerminalBuilder.builder()
                .system(true)
                .streams(System.in, System.out)
                .encoding(StandardCharsets.UTF_8)
                .build();
        writer = terminal.writer();
        screen = new ScreenRenderer(writer, terminal.getWidth(), terminal.getHeight());
        applyDisplayConfigToScreen();
        screen.enterAltScreen();
        terminal.enterRawMode();
    }

    private void shutdown() {
        try {
            screen.exitAltScreen();
            terminal.close();
        } catch (Exception ignored) {}
    }

    /** 将显示相关配置应用到 ScreenRenderer（光标样式、显示开关） */
    private void applyDisplayConfigToScreen() {
        String cursorAnsi;
        switch (config.getCursorStyle()) {
            case "underline": cursorAnsi = "\033[4 q"; break;
            case "bar":       cursorAnsi = "\033[6 q"; break;
            default:          cursorAnsi = "\033[2 q"; // block
        }
        String colorAnsi = "\033]12;" + config.getCursorColor() + "\007";
        screen.setCursorStyleCode(cursorAnsi);
        screen.setCursorColorCode(colorAnsi);
        screen.setProgressBarPosition(config.getProgressBarPosition());
        screen.setShowCommandPanel(config.isShowCommandPanel());
    }

    /** 将页面相关配置应用到 PageRenderer（缩进、底部边距） */
    private void applyPageConfig() {
        // 计算 chrome 实际需要的行数（含顶部标题栏）
        int fromChrome = 0;
        if ("top".equals(config.getProgressBarPosition())) {
            fromChrome += 2;           // 顶部标题栏 + 空行
        } else if ("bottom".equals(config.getProgressBarPosition())) {
            fromChrome += 1;           // 底部标题栏
        }
        if (config.isShowCommandPanel()) fromChrome += 3;
        // effectiveBottomMargin 取 chrome 需求与用户配置的较大值，保证内容不被遮挡
        int effective = Math.max(fromChrome, config.getBottomMargin());
        effective = Math.max(effective, 1); // 至少保留 1 行安全边距
        pageRenderer.setBottomMargin(effective);
        pageRenderer.setFirstLineIndentEnabled(config.isFirstLineIndent());
    }

    /** 配置变更后立即刷新显示：光标样式/颜色立即生效，显示开关同步到 ScreenRenderer */
    private void refreshDisplayConfig() {
        applyDisplayConfigToScreen();
        screen.flushCursorStyle();
    }

    // ==================== LIBRARY 模式 ====================

    /**
     * 书架模式主循环：显示书籍列表，支持单键操作和行内命令输入。
     * 按 / 激活命令行，Enter 执行命令，Esc 取消命令。
     * 按 a 自动进入命令模式预填 /add，按 d 预填 /delete。
     */
    private void libraryLoop() throws IOException {
        // 命令输入状态
        boolean cmdActive = false;
        StringBuilder cmdInput = new StringBuilder();
        int cmdCursor = 0;
        int selectedCmdIndex = 0;
        String cmdErrorMessage = null;

        while (mode == Mode.LIBRARY && running) {
            updateTerminalSize();

            // 捕获上一次操作的消息并清除，确保消息只显示一次
            String msg = libraryMessage;
            boolean msgErr = libraryMessageIsError;
            libraryMessage = null;
            libraryMessageIsError = false;

            screen.drawLibraryScreen(library, librarySelected, msg, msgErr);

            if (cmdActive) {
                // 展开式命令输入区域，匹配命令列表 + 选中高亮
                String[][] matches = matchCommands(cmdInput.toString());
                String leftHint;
                String rightHint = null;
                boolean rightIsError = false;

                // 确认暂存状态时显示确认提示
                if (pendingConfirmType != null) {
                    leftHint = "Enter 确认  任意键取消";
                    if ("add".equals(pendingConfirmType)) {
                        rightHint = "确认添加《" + pendingConfirmTitle + "》？Enter确认";
                    } else {
                        rightHint = "确认删除《" + pendingConfirmTitle + "》？Enter确认";
                    }
                    rightIsError = false;
                } else if (cmdErrorMessage != null) {
                    leftHint = "ESC 退出命令  Enter 重新输入";
                    rightHint = cmdErrorMessage;
                    rightIsError = true;
                } else if (matches.length > 0) {
                    leftHint = "ESC 退出命令  Enter 执行  ↑↓ 选择  Tab 补全";
                } else {
                    leftHint = "ESC 退出命令模式";
                }
                String completion = null;
                if (pendingConfirmType == null && matches.length > 0) {
                    int compIdx = matches.length == 1 ? 0 : selectedCmdIndex;
                    if (compIdx >= 0 && compIdx < matches.length) {
                        completion = getCompletionSuffix(cmdInput.toString(), matches[compIdx][0]);
                    }
                }
                screen.drawExpandedCommandAreaWithHints(
                        cmdInput.toString(), cmdCursor, completion,
                        matches, selectedCmdIndex,
                        leftHint, rightHint, rightIsError);
            }

            // 命令模式下用完整的输入按键处理，否则用简化的按键读取
            int key = cmdActive ? readKeyInput() : readKey();
            if (key == Key.CTRL_C) { running = false; return; }

            if (cmdActive) {
                // ---- 确认暂存处理（优先于其他按键） ----
                if (pendingConfirmType != null) {
                    if (key == Key.ENTER) {
                        // 执行确认动作
                        executePendingConfirm();
                        clearPendingConfirm();
                        cmdInput.setLength(0);
                        cmdCursor = 0;
                        cmdActive = false;
                        selectedCmdIndex = 0;
                        cmdErrorMessage = null;
                        commandHistoryIndex = -1;
                        commandHistoryDraft = null;
                    } else if (key != Key.UP && key != Key.DOWN && key != Key.LEFT && key != Key.RIGHT
                            && key != Key.HOME && key != Key.END && key != Key.UNKNOWN) {
                        // 任意有效按键取消确认（方向键忽略）
                        clearPendingConfirm();
                        cmdInput.setLength(0);
                        cmdCursor = 0;
                        cmdErrorMessage = null;
                        commandHistoryIndex = -1;
                        commandHistoryDraft = null;
                        // 如果是 ESC，退出命令模式
                        if (key == Key.ESC) {
                            cmdActive = false;
                            selectedCmdIndex = 0;
                        }
                    }
                    continue;
                }

                // ---- 命令行输入处理 ----
                if (key == Key.ESC) {
                    // 取消命令输入
                    cmdActive = false;
                    cmdInput.setLength(0);
                    cmdCursor = 0;
                    selectedCmdIndex = 0;
                    cmdErrorMessage = null;
                    commandHistoryIndex = -1;
                    commandHistoryDraft = null;
                    continue;
                }

                if (key == Key.ENTER) {
                    // 如果有匹配命令且有选中项，先用选中的命令补全输入
                    String[][] enterMatches = matchCommands(cmdInput.toString());
                    if (enterMatches.length > 0 && selectedCmdIndex >= 0 && selectedCmdIndex < enterMatches.length) {
                        cmdInput = new StringBuilder(enterMatches[selectedCmdIndex][0] + " ");
                        cmdCursor = cmdInput.length();
                    }

                    // 执行命令
                    String cmd = cmdInput.toString().trim();
                    commandHistoryIndex = -1;
                    commandHistoryDraft = null;

                    if (cmd.isEmpty()) {
                        // 空命令：退出命令模式
                        cmdInput.setLength(0);
                        cmdCursor = 0;
                        cmdActive = false;
                        selectedCmdIndex = 0;
                        cmdErrorMessage = null;
                        continue;
                    }

                    commandHistory.add(cmd);
                    handleLibraryCommand(cmd);
                    // 如果还在书架模式
                    if (mode == Mode.LIBRARY) {
                        // 检查是否进入了确认暂存状态
                        if (pendingConfirmType != null) {
                            cmdInput.setLength(0);
                            cmdCursor = 0;
                            selectedCmdIndex = 0;
                            cmdErrorMessage = null;
                            // cmdActive 保持 true，等待确认
                        } else if (libraryMessage != null && libraryMessageIsError) {
                            cmdInput.setLength(0);
                            cmdCursor = 0;
                            cmdErrorMessage = libraryMessage;
                            libraryMessage = null;
                            libraryMessageIsError = false;
                            selectedCmdIndex = 0;
                            // cmdActive 保持 true（错误消息）
                        } else {
                            cmdInput.setLength(0);
                            cmdCursor = 0;
                            cmdActive = false;
                            selectedCmdIndex = 0;
                            cmdErrorMessage = null;
                        }
                    }
                    if (!running || mode != Mode.LIBRARY) return;
                    continue;
                }

                // ---- 命令行编辑按键 ----
                if (key == Key.BACKSPACE) {
                    if (cmdCursor > 0) {
                        cmdInput.deleteCharAt(cmdCursor - 1);
                        cmdCursor--;
                    }
                    continue;
                }
                if (key == Key.DELETE) {
                    if (cmdCursor < cmdInput.length()) {
                        cmdInput.deleteCharAt(cmdCursor);
                    }
                    continue;
                }
                if (key == Key.LEFT) {
                    if (cmdCursor > 0) cmdCursor--;
                    continue;
                }
                if (key == Key.RIGHT) {
                    if (cmdCursor < cmdInput.length()) cmdCursor++;
                    continue;
                }
                if (key == Key.HOME) {
                    cmdCursor = 0;
                    continue;
                }
                if (key == Key.END) {
                    cmdCursor = cmdInput.length();
                    continue;
                }
                if (key == Key.UP) {
                    String[][] matches = matchCommands(cmdInput.toString());
                    if (matches.length > 0) {
                        if (selectedCmdIndex > 0) selectedCmdIndex--;
                    } else if (!commandHistory.isEmpty()) {
                        if (commandHistoryIndex == -1) {
                            commandHistoryDraft = cmdInput.toString();
                            commandHistoryIndex = commandHistory.size() - 1;
                        } else if (commandHistoryIndex > 0) {
                            commandHistoryIndex--;
                        }
                        cmdInput = new StringBuilder(commandHistory.get(commandHistoryIndex));
                        cmdCursor = cmdInput.length();
                    }
                    cmdErrorMessage = null;
                    continue;
                }
                if (key == Key.DOWN) {
                    String[][] matches = matchCommands(cmdInput.toString());
                    if (matches.length > 0) {
                        if (selectedCmdIndex < matches.length - 1) selectedCmdIndex++;
                    } else if (commandHistoryIndex != -1) {
                        if (commandHistoryIndex < commandHistory.size() - 1) {
                            commandHistoryIndex++;
                            cmdInput = new StringBuilder(commandHistory.get(commandHistoryIndex));
                        } else {
                            commandHistoryIndex = -1;
                            cmdInput = new StringBuilder(commandHistoryDraft != null ? commandHistoryDraft : "");
                            commandHistoryDraft = null;
                        }
                        cmdCursor = cmdInput.length();
                    }
                    cmdErrorMessage = null;
                    continue;
                }
                if (key == Key.TAB) {
                    // /add 命令：路径补全；否则：命令补全
                    String input = cmdInput.toString();
                    if (input.startsWith("/add ") && input.length() > 5) {
                        String pathArg = input.substring(5);
                        String comp = getPathCompletion(pathArg);
                        if (comp != null) {
                            cmdInput.append(comp);
                            cmdCursor = cmdInput.length();
                        }
                    } else {
                        String[][] matches = matchCommands(input);
                        if (matches.length > 0 && selectedCmdIndex >= 0 && selectedCmdIndex < matches.length) {
                            cmdInput = new StringBuilder(matches[selectedCmdIndex][0] + " ");
                            cmdCursor = cmdInput.length();
                        }
                    }
                    cmdErrorMessage = null;
                    continue;
                }
                if (key >= 32) {
                    cmdInput.insert(cmdCursor, (char) key);
                    cmdCursor++;
                    selectedCmdIndex = 0;
                    cmdErrorMessage = null;
                }
                continue;
            }

            // ---- 书架模式按键处理 ----
            if (key == config.getLibraryCommandKey()) {
                // 激活命令行，预填 /
                cmdActive = true;
                cmdInput.setLength(0);
                cmdInput.append('/');
                cmdCursor = 1;
                continue;
            }

            if (key == config.getLibraryOpenKey()) {
                if (!library.isEmpty() && librarySelected >= 0 && librarySelected < library.size()) {
                    openBook(library.get(librarySelected).getPath());
                    if (currentBook != null) {
                        mode = Mode.READING;
                        return;
                    }
                }
            } else if (key == config.getLibrarySelectUpKey()) {
                if (librarySelected > 0) librarySelected--;
            } else if (key == config.getLibrarySelectDownKey()) {
                if (librarySelected < library.size() - 1) librarySelected++;
            } else if (matchesKey(key, config.getLibraryQuitKey()) || key == Key.ESC) {
                running = false;
                return;
            } else if (matchesKey(key, config.getLibraryQuickAddKey())) {
                // 自动进入命令模式，预填 /add
                cmdActive = true;
                cmdInput = new StringBuilder("/add ");
                cmdCursor = 5;
                selectedCmdIndex = 0;
                cmdErrorMessage = null;
                commandHistoryIndex = -1;
                commandHistoryDraft = null;
            } else if (matchesKey(key, config.getLibraryQuickDeleteKey())) {
                // 自动进入命令模式，预填 /delete 书名
                if (librarySelected >= 0 && librarySelected < library.size()) {
                    cmdActive = true;
                    cmdInput = new StringBuilder("/delete " + library.get(librarySelected).getTitle());
                    cmdCursor = cmdInput.length();
                    selectedCmdIndex = 0;
                    cmdErrorMessage = null;
                    commandHistoryIndex = -1;
                    commandHistoryDraft = null;
                }
            }
        }
    }

    /** 处理书架模式下的斜杠命令 */
    private void handleLibraryCommand(String input) throws IOException {
        Command cmd = Command.parse(input, Mode.LIBRARY);
        String[] parts = input.trim().split("\\s+", 2);

        if (cmd == null) {
            String cmdName = parts[0].toLowerCase();
            if (cmdName.startsWith("/")) {
                libraryMessage = "未知命令: " + cmdName + "。输入 /help 查看可用命令";
                libraryMessageIsError = true;
            }
            return;
        }

        switch (cmd) {
            case READ_SHELF: {
                if (library.isEmpty()) {
                    libraryMessage = "书架为空，请先添加书籍";
                    libraryMessageIsError = true;
                    return;
                }
                if (parts.length < 2 || parts[1].isBlank()) {
                    libraryMessage = "用法: /read <序号>  例如: /read 1";
                    libraryMessageIsError = true;
                    return;
                }
                try {
                    int idx = Integer.parseInt(parts[1].trim());
                    if (idx < 1 || idx > library.size()) {
                        libraryMessage = "序号超出范围 (1-" + library.size() + ")";
                        libraryMessageIsError = true;
                        return;
                    }
                    librarySelected = idx - 1;
                    openBook(library.get(librarySelected).getPath());
                    if (currentBook != null) {
                        mode = Mode.READING;
                    }
                } catch (NumberFormatException e) {
                    libraryMessage = "无效的序号: " + parts[1];
                    libraryMessageIsError = true;
                }
                return;
            }

            case ADD: {
                String arg = parts.length > 1 ? parts[1].trim() : "";
                if (arg.isEmpty()) {
                    libraryMessage = "用法: /add <文件路径>  例如: /add D:\\books\\书.epub";
                    libraryMessageIsError = true;
                    return;
                }
                // 去掉两端引号
                if (arg.length() >= 2) {
                    char first = arg.charAt(0);
                    char last = arg.charAt(arg.length() - 1);
                    if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                        arg = arg.substring(1, arg.length() - 1).trim();
                    }
                }
                File file = new File(arg);
                if (!file.exists() || !file.isFile()) {
                    libraryMessage = "文件不存在: " + arg;
                    libraryMessageIsError = true;
                    return;
                }
                String canonical;
                try { canonical = file.getCanonicalPath(); }
                catch (IOException e) { canonical = file.getAbsolutePath(); }
                // 检查重复
                for (LibraryEntry e : library) {
                    if (e.getPath().equalsIgnoreCase(canonical)) {
                        libraryMessage = "该书籍已在书架中";
                        libraryMessageIsError = true;
                        return;
                    }
                }
                // 尝试解析书名用于确认提示
                String displayTitle;
                try {
                    EpubParser parser = new EpubParser();
                    Book book = parser.parse(arg);
                    displayTitle = book.getTitle();
                } catch (Exception ex) {
                    String name = file.getName();
                    if (name.toLowerCase().endsWith(".epub")) name = name.substring(0, name.length() - 5);
                    displayTitle = name;
                }
                if (config.isConfirmAdd()) {
                    // 暂存确认信息
                    pendingConfirmType = "add";
                    pendingConfirmPath = canonical;
                    pendingConfirmTitle = displayTitle;
                } else {
                    // 跳过确认，直接添加
                    addToLibrary(canonical);
                    libraryMessage = "已添加: 《" + displayTitle + "》";
                    libraryMessageIsError = false;
                }
                return;
            }

            case DELETE: {
                String arg = parts.length > 1 ? parts[1].trim() : "";
                if (arg.isEmpty()) {
                    libraryMessage = "用法: /delete <书名或序号>  例如: /delete 三体";
                    libraryMessageIsError = true;
                    return;
                }
                if (library.isEmpty()) {
                    libraryMessage = "书架为空";
                    libraryMessageIsError = true;
                    return;
                }
                // 尝试按序号匹配
                try {
                    int idx = Integer.parseInt(arg);
                    if (idx < 1 || idx > library.size()) {
                        libraryMessage = "序号超出范围 (1-" + library.size() + ")";
                        libraryMessageIsError = true;
                        return;
                    }
                    if (config.isConfirmDelete()) {
                        pendingConfirmType = "delete";
                        pendingConfirmIndex = idx - 1;
                        pendingConfirmTitle = library.get(idx - 1).getTitle();
                    } else {
                        String title = library.get(idx - 1).getTitle();
                        library.remove(idx - 1);
                        if (librarySelected >= library.size()) librarySelected = Math.max(0, library.size() - 1);
                        storage.saveLibrary(library);
                        libraryMessage = "已删除: 《" + title + "》";
                        libraryMessageIsError = false;
                    }
                    return;
                } catch (NumberFormatException ignored) {
                    // 不是序号，尝试按书名匹配
                }
                // 按书名模糊匹配（包含即可，大小写不敏感）
                String lowerArg = arg.toLowerCase();
                int matchIdx = -1;
                for (int i = 0; i < library.size(); i++) {
                    if (library.get(i).getTitle().toLowerCase().contains(lowerArg)) {
                        if (matchIdx == -1) {
                            matchIdx = i;
                        } else {
                            libraryMessage = "多个匹配: 请用序号指定 (1-" + library.size() + ")";
                            libraryMessageIsError = true;
                            return;
                        }
                    }
                }
                if (matchIdx == -1) {
                    libraryMessage = "未找到匹配的书籍: " + arg;
                    libraryMessageIsError = true;
                    return;
                }
                if (config.isConfirmDelete()) {
                    pendingConfirmType = "delete";
                    pendingConfirmIndex = matchIdx;
                    pendingConfirmTitle = library.get(matchIdx).getTitle();
                } else {
                    String title = library.get(matchIdx).getTitle();
                    library.remove(matchIdx);
                    if (librarySelected >= library.size()) librarySelected = Math.max(0, library.size() - 1);
                    storage.saveLibrary(library);
                    libraryMessage = "已删除: 《" + title + "》";
                    libraryMessageIsError = false;
                }
                return;
            }

            case HELP:
                screen.drawHelpScreen();
                waitForAnyKey();
                return;

            case SETTINGS:
                prevMode = mode;
                mode = Mode.SETTINGS;
                return; // 返回后由主循环进入 settingsLoop

            case QUIT:
            case EXIT:
                running = false;
                return;

            default:
                libraryMessage = "该命令在当前模式下不可用: " + cmd.getName();
                libraryMessageIsError = true;
        }
    }

    /** 执行暂存的确认动作（添加或删除） */
    private void executePendingConfirm() {
        if ("add".equals(pendingConfirmType) && pendingConfirmPath != null) {
            addToLibrary(pendingConfirmPath);
        } else if ("delete".equals(pendingConfirmType) && pendingConfirmIndex >= 0) {
            String title = library.get(pendingConfirmIndex).getTitle();
            library.remove(pendingConfirmIndex);
            if (librarySelected >= library.size()) librarySelected = Math.max(0, library.size() - 1);
            storage.saveLibrary(library);
            libraryMessage = "已删除: 《" + title + "》";
            libraryMessageIsError = false;
        }
    }

    /** 清除确认暂存状态 */
    private void clearPendingConfirm() {
        pendingConfirmType = null;
        pendingConfirmPath = null;
        pendingConfirmIndex = -1;
        pendingConfirmTitle = null;
    }

    // ==================== COMMAND 模式 ====================

    private void commandLoop() throws IOException {
        while (mode == Mode.COMMAND && running) {
            updateTerminalSize();
            screen.drawCommandMode(currentBook, commandHandler.getLastMessage());

            String line = readCommandLine();
            if (!running) return;
            if (line == null) {  // ESC pressed
                handleCommandEscape();
                return;
            }

            CommandResult result = commandHandler.handle(line, Mode.READING);
            switch (result) {
                case ENTER_READING:
                    pageRenderer.reRender(currentBook, terminal.getWidth(), terminal.getHeight());
                    mode = Mode.READING;
                    return;

                case BACK_TO_LIBRARY:
                    handleCommandEscape();
                    return;

                case SHOW_TOC:
                    screen.drawTocScreen(currentBook);
                    waitForAnyKey();
                    break;

                case SHOW_PROGRESS:
                    screen.drawProgressScreen(currentBook);
                    waitForAnyKey();
                    break;

                case SHOW_INFO:
                    screen.drawInfoScreen(currentBook);
                    waitForAnyKey();
                    break;

                case SHOW_HELP:
                    screen.drawHelpScreen();
                    waitForAnyKey();
                    break;

                case QUIT:
                    saveCurrentProgress();
                    running = false;
                    return;
            }
        }
    }

    private void handleCommandEscape() {
        saveCurrentProgress();
        currentBook = null;
        commandHandler = null;
        mode = Mode.LIBRARY;
    }

    // ==================== READING 模式 ====================

    /**
     * 阅读模式主循环：显示书籍内容，支持翻页和行内命令输入。
     * 按 / 激活命令行，Enter 执行命令，Esc 取消命令或返回书架。
     */
    private void readingLoop() throws IOException {
        // 命令输入状态
        boolean cmdActive = false;
        StringBuilder cmdInput = new StringBuilder();
        int cmdCursor = 0;
        int selectedCmdIndex = 0;
        String cmdErrorMessage = null;

        while (mode == Mode.READING && running) {
            updateTerminalSize();

            // 绘制阅读画面（内容 + 进度条 + 底部提示）
            screen.drawReadingMode(currentBook);

            if (cmdActive) {
                // 展开式命令输入区域，匹配命令列表 + 选中高亮
                String[][] matches = matchCommands(cmdInput.toString());
                String leftHint;
                String rightHint = null;
                boolean rightIsError = false;

                if (cmdErrorMessage != null) {
                    leftHint = "ESC 退出命令  Enter 重新输入";
                    rightHint = cmdErrorMessage;
                    rightIsError = true;
                } else if (matches.length > 0) {
                    leftHint = "ESC 退出命令  Enter 执行  ↑↓ 选择  Tab 补全";
                } else {
                    leftHint = "ESC 退出命令模式";
                }
                String completion = null;
                if (matches.length > 0) {
                    int compIdx = matches.length == 1 ? 0 : selectedCmdIndex;
                    if (compIdx >= 0 && compIdx < matches.length) {
                        completion = getCompletionSuffix(cmdInput.toString(), matches[compIdx][0]);
                    }
                }
                screen.drawExpandedCommandAreaWithHints(
                        cmdInput.toString(), cmdCursor, completion,
                        matches, selectedCmdIndex,
                        leftHint, rightHint, rightIsError);
            }

            // 命令模式下用完整的输入按键处理，否则用简化的按键读取
            int key = cmdActive ? readKeyInput() : readKey();
            if (key == Key.CTRL_C) { running = false; return; }

            if (cmdActive) {
                // ---- 命令行输入处理 ----
                if (key == Key.ESC) {
                    // 取消命令输入
                    cmdActive = false;
                    cmdInput.setLength(0);
                    cmdCursor = 0;
                    selectedCmdIndex = 0;
                    cmdErrorMessage = null;
                    commandHistoryIndex = -1;
                    commandHistoryDraft = null;
                    continue;
                }

                if (key == Key.ENTER) {
                    // 如果有匹配命令且有选中项，先用选中的命令补全输入
                    String[][] enterMatches = matchCommands(cmdInput.toString());
                    if (enterMatches.length > 0 && selectedCmdIndex >= 0 && selectedCmdIndex < enterMatches.length) {
                        cmdInput = new StringBuilder(enterMatches[selectedCmdIndex][0] + " ");
                        cmdCursor = cmdInput.length();
                    }

                    // 执行命令
                    String cmd = cmdInput.toString().trim();
                    commandHistoryIndex = -1;
                    commandHistoryDraft = null;

                    if (cmd.isEmpty()) {
                        // 空命令：退出命令模式
                        cmdInput.setLength(0);
                        cmdCursor = 0;
                        cmdActive = false;
                        selectedCmdIndex = 0;
                        cmdErrorMessage = null;
                        continue;
                    }

                    commandHistory.add(cmd);

                    CommandResult result = commandHandler.handle(cmd, Mode.READING);
                    switch (result) {
                        case SHOW_TOC:
                            cmdInput.setLength(0); cmdCursor = 0; cmdActive = false;
                            selectedCmdIndex = 0; cmdErrorMessage = null;
                            screen.drawTocScreen(currentBook);
                            waitForAnyKey();
                            break;
                        case SHOW_PROGRESS:
                            cmdInput.setLength(0); cmdCursor = 0; cmdActive = false;
                            selectedCmdIndex = 0; cmdErrorMessage = null;
                            screen.drawProgressScreen(currentBook);
                            waitForAnyKey();
                            break;
                        case SHOW_INFO:
                            cmdInput.setLength(0); cmdCursor = 0; cmdActive = false;
                            selectedCmdIndex = 0; cmdErrorMessage = null;
                            screen.drawInfoScreen(currentBook);
                            waitForAnyKey();
                            break;
                        case SHOW_SETTINGS:
                            prevMode = mode;
                            mode = Mode.SETTINGS;
                            return;

                        case SHOW_HELP:
                            cmdInput.setLength(0); cmdCursor = 0; cmdActive = false;
                            selectedCmdIndex = 0; cmdErrorMessage = null;
                            screen.drawHelpScreen();
                            waitForAnyKey();
                            break;
                        case ENTER_READING:
                            // /read 或 /goto 跳转 — 已在 handle 中更新位置，保存进度即可
                            cmdInput.setLength(0); cmdCursor = 0; cmdActive = false;
                            selectedCmdIndex = 0; cmdErrorMessage = null;
                            saveCurrentProgress();
                            break;
                        case BACK_TO_LIBRARY:
                            saveCurrentProgress();
                            currentBook = null;
                            commandHandler = null;
                            mode = Mode.LIBRARY;
                            return;
                        case NONE:
                        default:
                            // 命令执行失败：保持在命令模式，显示错误消息
                            String msg = commandHandler.getLastMessage();
                            cmdErrorMessage = (msg != null && !msg.isEmpty()) ? msg : null;
                            cmdInput.setLength(0);
                            cmdCursor = 0;
                            selectedCmdIndex = 0;
                            // cmdActive 保持 true
                            break;
                    }
                    continue;
                }

                // ---- 命令行编辑按键 ----
                if (key == Key.BACKSPACE) {
                    if (cmdCursor > 0) {
                        cmdInput.deleteCharAt(cmdCursor - 1);
                        cmdCursor--;
                    }
                    continue;
                }
                if (key == Key.DELETE) {
                    if (cmdCursor < cmdInput.length()) {
                        cmdInput.deleteCharAt(cmdCursor);
                    }
                    continue;
                }
                if (key == Key.LEFT) {
                    if (cmdCursor > 0) cmdCursor--;
                    continue;
                }
                if (key == Key.RIGHT) {
                    if (cmdCursor < cmdInput.length()) cmdCursor++;
                    continue;
                }
                if (key == Key.HOME) {
                    cmdCursor = 0;
                    continue;
                }
                if (key == Key.END) {
                    cmdCursor = cmdInput.length();
                    continue;
                }
                if (key == Key.UP) {
                    String[][] matches = matchCommands(cmdInput.toString());
                    if (matches.length > 0) {
                        // 有匹配命令：上下选择
                        if (selectedCmdIndex > 0) selectedCmdIndex--;
                    } else if (!commandHistory.isEmpty()) {
                        // 无匹配命令：命令历史回溯
                        if (commandHistoryIndex == -1) {
                            commandHistoryDraft = cmdInput.toString();
                            commandHistoryIndex = commandHistory.size() - 1;
                        } else if (commandHistoryIndex > 0) {
                            commandHistoryIndex--;
                        }
                        cmdInput = new StringBuilder(commandHistory.get(commandHistoryIndex));
                        cmdCursor = cmdInput.length();
                    }
                    cmdErrorMessage = null;  // 任何输入清除错误
                    continue;
                }
                if (key == Key.DOWN) {
                    String[][] matches = matchCommands(cmdInput.toString());
                    if (matches.length > 0) {
                        // 有匹配命令：上下选择
                        if (selectedCmdIndex < matches.length - 1) selectedCmdIndex++;
                    } else if (commandHistoryIndex != -1) {
                        // 无匹配命令：命令历史前进
                        if (commandHistoryIndex < commandHistory.size() - 1) {
                            commandHistoryIndex++;
                            cmdInput = new StringBuilder(commandHistory.get(commandHistoryIndex));
                        } else {
                            commandHistoryIndex = -1;
                            cmdInput = new StringBuilder(commandHistoryDraft != null ? commandHistoryDraft : "");
                            commandHistoryDraft = null;
                        }
                        cmdCursor = cmdInput.length();
                    }
                    cmdErrorMessage = null;
                    continue;
                }
                if (key == Key.TAB) {
                    // Tab 补全：优先使用选中的命令
                    String[][] matches = matchCommands(cmdInput.toString());
                    if (matches.length > 0 && selectedCmdIndex >= 0 && selectedCmdIndex < matches.length) {
                        cmdInput = new StringBuilder(matches[selectedCmdIndex][0] + " ");
                        cmdCursor = cmdInput.length();
                    }
                    cmdErrorMessage = null;
                    continue;
                }
                if (key >= 32) {
                    cmdInput.insert(cmdCursor, (char) key);
                    cmdCursor++;
                    selectedCmdIndex = 0;  // 输入变化后重置选中
                    cmdErrorMessage = null;
                }
                continue;
            }

            // ---- 阅读模式按键处理 ----
            if (key == config.getReadingExitKey()) {
                // 返回书架
                saveCurrentProgress();
                currentBook = null;
                commandHandler = null;
                mode = Mode.LIBRARY;
                return;
            }

            if (key == config.getReadingCommandKey()) {
                // 激活命令行，预填 /
                cmdActive = true;
                cmdInput.setLength(0);
                cmdInput.append('/');
                cmdCursor = 1;
                continue;
            }

            if (config.getReadingNextPageKeys().contains(key)) {
                handleNextPage();
            } else if (config.getReadingPrevPageKeys().contains(key)) {
                handlePrevPage();
            }
        }
    }

    // ==================== SETTINGS 模式 ====================

    private void settingsLoop() throws IOException {
        List<ConfigManager.SettingSection> sections = config.getSettingSections();
        boolean[] expanded = new boolean[sections.size()];
        int selectedIndex = 0;
        String message = null;
        boolean msgIsError = false;

        // 命令输入状态
        boolean cmdActive = false;
        StringBuilder cmdInput = new StringBuilder();
        int cmdCursor = 0;
        int selectedCmdIndex = 0;
        String cmdErrorMessage = null;
        String cmdSuccessMsg = null; // /set 成功提示，显示在命令区域右侧

        // 进入设置时同步显示配置（处理从 READING/LIBRARY 模式 /set 后进入的情况）
        refreshDisplayConfig();

        while (mode == Mode.SETTINGS && running) {
            updateTerminalSize();

            // 计算总行数，用于导航边界
            int totalRows = sections.size();
            for (int i = 0; i < sections.size(); i++) {
                if (expanded[i]) totalRows += sections.get(i).items.size();
            }
            if (selectedIndex >= totalRows) selectedIndex = totalRows - 1;
            if (selectedIndex < 0) selectedIndex = 0;

            String focusedKey = screen.drawSettingsScreen(
                    sections, expanded, selectedIndex, config,
                    cmdActive ? null : message, msgIsError);

            if (cmdActive) {
                String[][] matches = matchCommands(cmdInput.toString());
                String leftHint;
                String rightHint = null;
                boolean rightIsError = false;
                if (cmdErrorMessage != null) {
                    leftHint = "ESC 退出命令  Enter 重新输入";
                    rightHint = cmdErrorMessage;
                    rightIsError = true;
                } else if (cmdSuccessMsg != null) {
                    leftHint = "ESC 退出命令  Enter 执行  ↑↓ 选择  Tab 补全";
                    rightHint = cmdSuccessMsg;
                    rightIsError = false;
                } else if (matches.length > 0) {
                    leftHint = "ESC 退出命令  Enter 执行  ↑↓ 选择  Tab 补全";
                } else {
                    leftHint = "ESC 退出命令模式";
                }
                String completion = null;
                if (matches.length > 0) {
                    int compIdx = matches.length == 1 ? 0 : selectedCmdIndex;
                    if (compIdx >= 0 && compIdx < matches.length) {
                        completion = getCompletionSuffix(cmdInput.toString(), matches[compIdx][0]);
                    }
                }
                screen.drawExpandedCommandAreaWithHints(
                        cmdInput.toString(), cmdCursor, completion,
                        matches, selectedCmdIndex,
                        leftHint, rightHint, rightIsError);
            }

            int key = cmdActive ? readKeyInput() : readKey();
            if (key == Key.CTRL_C) { running = false; return; }

            // 清除一次性消息
            message = null;
            msgIsError = false;
            cmdSuccessMsg = null; // 成功提示只显示一帧，按键后清除

            if (cmdActive) {
                // ---- 命令输入处理 ----
                if (key == Key.ESC) {
                    cmdActive = false;
                    cmdInput.setLength(0);
                    cmdCursor = 0;
                    selectedCmdIndex = 0;
                    cmdErrorMessage = null;
                    cmdSuccessMsg = null;
                    commandHistoryIndex = -1;
                    commandHistoryDraft = null;
                    continue;
                }
                if (key == Key.ENTER) {
                    String[][] enterMatches = matchCommands(cmdInput.toString());
                    if (enterMatches.length > 0 && selectedCmdIndex >= 0 && selectedCmdIndex < enterMatches.length) {
                        cmdInput = new StringBuilder(enterMatches[selectedCmdIndex][0] + " ");
                        cmdCursor = cmdInput.length();
                    }
                    String cmd = cmdInput.toString().trim();
                    commandHistoryIndex = -1;
                    commandHistoryDraft = null;
                    if (cmd.isEmpty()) {
                        cmdActive = false;
                        continue;
                    }
                    commandHistory.add(cmd);
                    // 在设置模式下处理命令
                    Command parsedCmd = Command.parse(cmd, Mode.SETTINGS);
                    if (parsedCmd == Command.SET) {
                        String[] parts = cmd.trim().split("\\s+", 3);
                        if (parts.length >= 3) {
                            String err = config.set(parts[1], parts[2]);
                            if (err != null) {
                                cmdErrorMessage = err;
                            } else {
                                // 成功：右侧绿色提示，保持命令行激活让用户手动 ESC 退出
                                cmdSuccessMsg = "已设置 " + parts[1] + " = " + parts[2];
                                cmdErrorMessage = null;
                                cmdInput.setLength(0);
                                cmdCursor = 0;
                                selectedCmdIndex = 0;
                                refreshDisplayConfig();
                            }
                        } else {
                            cmdErrorMessage = "用法: /set <属性名> <值>";
                        }
                    } else if (parsedCmd == Command.SETTINGS) {
                        cmdActive = false;
                        cmdInput.setLength(0);
                        cmdCursor = 0;
                        selectedCmdIndex = 0;
                        cmdErrorMessage = null;
                    } else if (parsedCmd == Command.HELP) {
                        screen.drawHelpScreen();
                        waitForAnyKey();
                        cmdActive = false;
                        cmdInput.setLength(0);
                        cmdCursor = 0;
                        selectedCmdIndex = 0;
                        cmdErrorMessage = null;
                    } else if (parsedCmd != null) {
                        cmdErrorMessage = "设置模式下不支持该命令，请使用 /set 或 /settings";
                    } else {
                        cmdErrorMessage = commandHandler != null
                                ? commandHandler.getLastMessage()
                                : "未知命令";
                        if (cmdErrorMessage == null) cmdErrorMessage = "未知命令";
                    }
                    continue;
                }
                // 编辑按键
                if (key == Key.BACKSPACE) {
                    if (cmdCursor > 0) { cmdInput.deleteCharAt(cmdCursor - 1); cmdCursor--; }
                    continue;
                }
                if (key == Key.DELETE) {
                    if (cmdCursor < cmdInput.length()) { cmdInput.deleteCharAt(cmdCursor); }
                    continue;
                }
                if (key == Key.LEFT) { if (cmdCursor > 0) cmdCursor--; continue; }
                if (key == Key.RIGHT) { if (cmdCursor < cmdInput.length()) cmdCursor++; continue; }
                if (key == Key.HOME) { cmdCursor = 0; continue; }
                if (key == Key.END) { cmdCursor = cmdInput.length(); continue; }
                if (key == Key.UP) {
                    String[][] matches = matchCommands(cmdInput.toString());
                    if (matches.length > 0) {
                        if (selectedCmdIndex > 0) selectedCmdIndex--;
                    } else if (!commandHistory.isEmpty()) {
                        if (commandHistoryIndex == -1) {
                            commandHistoryDraft = cmdInput.toString();
                            commandHistoryIndex = commandHistory.size() - 1;
                        } else if (commandHistoryIndex > 0) {
                            commandHistoryIndex--;
                        }
                        cmdInput = new StringBuilder(commandHistory.get(commandHistoryIndex));
                        cmdCursor = cmdInput.length();
                    }
                    cmdErrorMessage = null;
                    continue;
                }
                if (key == Key.DOWN) {
                    String[][] matches = matchCommands(cmdInput.toString());
                    if (matches.length > 0) {
                        if (selectedCmdIndex < matches.length - 1) selectedCmdIndex++;
                    } else if (commandHistoryIndex != -1) {
                        if (commandHistoryIndex < commandHistory.size() - 1) {
                            commandHistoryIndex++;
                            cmdInput = new StringBuilder(commandHistory.get(commandHistoryIndex));
                        } else {
                            commandHistoryIndex = -1;
                            cmdInput = new StringBuilder(commandHistoryDraft != null ? commandHistoryDraft : "");
                            commandHistoryDraft = null;
                        }
                        cmdCursor = cmdInput.length();
                    }
                    cmdErrorMessage = null;
                    continue;
                }
                if (key == Key.TAB) {
                    String[][] matches = matchCommands(cmdInput.toString());
                    if (matches.length > 0 && selectedCmdIndex >= 0 && selectedCmdIndex < matches.length) {
                        cmdInput = new StringBuilder(matches[selectedCmdIndex][0] + " ");
                        cmdCursor = cmdInput.length();
                    }
                    cmdErrorMessage = null;
                    continue;
                }
                if (key >= 32) {
                    cmdInput.insert(cmdCursor, (char) key);
                    cmdCursor++;
                    selectedCmdIndex = 0;
                    cmdErrorMessage = null;
                }
                continue;
            }

            // ---- 设置模式按键处理 ----
            if (key == Key.ESC) {
                mode = prevMode;
                // 返回阅读模式时，重新应用页面配置并分页（缩进/边距变更即时生效）
                if (prevMode == Mode.READING && currentBook != null) {
                    applyPageConfig();
                    pageRenderer.render(currentBook, terminal.getWidth(), terminal.getHeight());
                }
                return;
            }

            if (key == '/') {
                cmdActive = true;
                cmdInput.setLength(0);
                cmdInput.append('/');
                cmdCursor = 1;
                continue;
            }

            if (key == Key.ENTER) {
                // 确定当前选中是什么
                int[] sel = resolveSelection(sections, expanded, selectedIndex);
                if (sel[0] < 0) continue;

                int si = sel[0], ii = sel[1];
                if (ii < 0) {
                    // 板块头：切换展开/收起
                    expanded[si] = !expanded[si];
                    if (!expanded[si]) {
                        // 收起后调整选中位置
                        if (selectedIndex > 0) selectedIndex = Math.min(selectedIndex, resolveTotalRows(sections, expanded) - 1);
                    }
                } else {
                    // 属性项：自动填充命令行
                    SettingItem item = sections.get(si).items.get(ii);
                    cmdActive = true;
                    cmdInput = new StringBuilder("/set " + item.key + " ");
                    cmdCursor = cmdInput.length();
                    selectedCmdIndex = 0;
                    cmdErrorMessage = null;
                    commandHistoryIndex = -1;
                    commandHistoryDraft = null;
                }
            } else if (key == Key.UP) {
                if (selectedIndex > 0) selectedIndex--;
            } else if (key == Key.DOWN) {
                int max = resolveTotalRows(sections, expanded);
                if (selectedIndex < max - 1) selectedIndex++;
            } else if (key == Key.LEFT || key == Key.RIGHT) {
                // 枚举和布尔值切换
                int[] sel = resolveSelection(sections, expanded, selectedIndex);
                if (sel[0] >= 0 && sel[1] >= 0) {
                    SettingItem item = sections.get(sel[0]).items.get(sel[1]);
                    if (item.type == ConfigManager.SettingType.ENUM && item.options != null) {
                        String current = config.getValueString(item.key);
                        int idx = -1;
                        for (int i = 0; i < item.options.length; i++) {
                            if (item.options[i].equals(current)) { idx = i; break; }
                        }
                        if (key == Key.RIGHT) idx = (idx + 1) % item.options.length;
                        else idx = (idx - 1 + item.options.length) % item.options.length;
                        config.set(item.key, item.options[idx]);
                        refreshDisplayConfig();
                    } else if (item.type == ConfigManager.SettingType.BOOL) {
                        String current = config.getValueString(item.key);
                        config.set(item.key, "true".equals(current) ? "false" : "true");
                        refreshDisplayConfig();
                    }
                }
            }
        }
    }

    /** 计算设置页面当前总行数 */
    private int resolveTotalRows(List<ConfigManager.SettingSection> sections, boolean[] expanded) {
        int total = sections.size();
        for (int i = 0; i < sections.size(); i++) {
            if (expanded[i]) total += sections.get(i).items.size();
        }
        return total;
    }

    /** 将 flat selectedIndex 解析为 [sectionIdx, itemIdx]，itemIdx=-1 表示板块头 */
    private int[] resolveSelection(List<ConfigManager.SettingSection> sections, boolean[] expanded, int selectedIndex) {
        int idx = 0;
        for (int si = 0; si < sections.size(); si++) {
            if (idx == selectedIndex) return new int[]{si, -1};
            idx++;
            if (expanded[si]) {
                int itemCount = sections.get(si).items.size();
                if (selectedIndex < idx + itemCount) {
                    return new int[]{si, selectedIndex - idx};
                }
                idx += itemCount;
            }
        }
        return new int[]{-1, -1};
    }

    // ==================== 导航 ====================

    private void handleNextPage() {
        if (currentBook.nextPage()) {
            saveCurrentProgress();
        }
    }

    private void handlePrevPage() {
        if (currentBook.prevPage()) {
            saveCurrentProgress();
        }
    }

    // ==================== 书籍操作 ====================

    private void openBook(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            // 文件不存在 —— 路径可能已失效，静默跳过
            return;
        }
        try {
            // 标准化路径（解析 ../ 和符号链接）
            currentBookPath = file.getCanonicalPath();
            screen.drawLoadingScreen(currentBookPath);
            EpubParser parser = new EpubParser();
            currentBook = parser.parse(currentBookPath);
            commandHandler = new CommandHandler(currentBook);
            commandHandler.setConfig(config);
            applyPageConfig();

            int[] progress = storage.loadProgress(filePath);
            if (progress != null) {
                pageRenderer.render(currentBook, terminal.getWidth(), terminal.getHeight());
                currentBook.goToChapter(progress[0]);
                int savedPage = Math.min(progress[1], currentBook.getCurrentChapterPageCount() - 1);
                for (int i = 0; i < savedPage; i++) currentBook.nextPage();
            } else {
                pageRenderer.render(currentBook, terminal.getWidth(), terminal.getHeight());
            }
            updateLibraryEntry(filePath, currentBook.getTitle(), currentBook.getAuthor());
        } catch (Exception e) {
            currentBook = null;
            currentBookPath = null;
        }
    }

    private void saveCurrentProgress() {
        if (currentBookPath != null && currentBook != null) {
            storage.saveProgress(currentBookPath,
                    currentBook.getCurrentChapter(),
                    currentBook.getCurrentPage());
        }
    }

    private void addToLibrary(String filePath) {
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            libraryMessage = "文件不存在: " + filePath;
            libraryMessageIsError = true;
            return;
        }

        String canonical;
        try { canonical = file.getCanonicalPath(); }
        catch (IOException e) { canonical = file.getAbsolutePath(); }

        for (LibraryEntry e : library) {
            if (e.getPath().equalsIgnoreCase(canonical)) {
                libraryMessage = "该书籍已在书架中";
                libraryMessageIsError = true;
                return;
            }
        }

        try {
            EpubParser parser = new EpubParser();
            Book book = parser.parse(filePath);
            library.add(new LibraryEntry(canonical, book.getTitle(), book.getAuthor()));
            libraryMessage = "已添加: 《" + book.getTitle() + "》";
            libraryMessageIsError = false;
        } catch (Exception e) {
            String name = file.getName();
            if (name.toLowerCase().endsWith(".epub")) name = name.substring(0, name.length() - 5);
            library.add(new LibraryEntry(canonical, name, "未知作者"));
            libraryMessage = "已添加: " + name + "（无法解析元数据）";
            libraryMessageIsError = false;
        }
        storage.saveLibrary(library);
        librarySelected = library.size() - 1;
    }

    private void updateLibraryEntry(String filePath, String title, String author) {
        String canonical;
        try { canonical = new File(filePath).getCanonicalPath(); }
        catch (IOException e) { canonical = new File(filePath).getAbsolutePath(); }
        for (LibraryEntry e : library) {
            if (e.getPath().equalsIgnoreCase(canonical)) {
                e.setTitle(title);
                e.setAuthor(author);
                storage.saveLibrary(library);
                return;
            }
        }
    }

    // ==================== 输入 ====================

    /**
     * 命令模式行输入：支持光标移动、历史记录、Tab 命令补全。
     * 返回 null 表示用户按了 ESC。
     */
    private String readCommandLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        int cursorPos = 0;

        while (true) {
            String completion = getCommandCompletion(sb.toString());
            screen.redrawCommandLine(sb.toString(), cursorPos, completion);

            int c = readKeyInput();
            if (c == Key.CTRL_C) { running = false; return ""; }
            if (c == Key.ENTER) {
                write("\r\n");
                flush();
                break;
            }
            if (c == Key.ESC) {
                return null;
            }

            if (c == Key.BACKSPACE) {
                if (cursorPos > 0) {
                    sb.deleteCharAt(cursorPos - 1);
                    cursorPos--;
                }
                continue;
            }

            if (c == Key.DELETE) {
                if (cursorPos < sb.length()) {
                    sb.deleteCharAt(cursorPos);
                }
                continue;
            }

            if (c == Key.LEFT) {
                if (cursorPos > 0) cursorPos--;
                continue;
            }

            if (c == Key.RIGHT) {
                if (cursorPos < sb.length()) cursorPos++;
                continue;
            }

            if (c == Key.HOME) {
                cursorPos = 0;
                continue;
            }

            if (c == Key.END) {
                cursorPos = sb.length();
                continue;
            }

            if (c == Key.UP) {
                if (!commandHistory.isEmpty()) {
                    if (commandHistoryIndex == -1) {
                        commandHistoryDraft = sb.toString();
                        commandHistoryIndex = commandHistory.size() - 1;
                    } else if (commandHistoryIndex > 0) {
                        commandHistoryIndex--;
                    }
                    sb = new StringBuilder(commandHistory.get(commandHistoryIndex));
                    cursorPos = sb.length();
                }
                continue;
            }

            if (c == Key.DOWN) {
                if (commandHistoryIndex != -1) {
                    if (commandHistoryIndex < commandHistory.size() - 1) {
                        commandHistoryIndex++;
                        sb = new StringBuilder(commandHistory.get(commandHistoryIndex));
                    } else {
                        commandHistoryIndex = -1;
                        sb = new StringBuilder(commandHistoryDraft != null ? commandHistoryDraft : "");
                        commandHistoryDraft = null;
                    }
                    cursorPos = sb.length();
                }
                continue;
            }

            if (c == Key.TAB) {
                String comp = getCommandCompletion(sb.toString());
                if (comp != null) {
                    sb.append(comp);
                    cursorPos = sb.length();
                }
                continue;
            }

            if (c >= 32) {
                sb.insert(cursorPos, (char) c);
                cursorPos++;
            }
        }

        String result = sb.toString().trim();
        if (!result.isEmpty()) {
            commandHistory.add(result);
            commandHistoryIndex = -1;
            commandHistoryDraft = null;
        }
        return result;
    }

    /** 等待任意键（方向键等被忽略，仅等待有效按键或 ESC/Ctrl+C） */
    private void waitForAnyKey() throws IOException {
        flush();
        while (true) {
            int key = readKey();
            if (key == Key.CTRL_C) { running = false; return; }
            if (key != Key.UP && key != Key.DOWN && key != Key.LEFT && key != Key.RIGHT
                    && key != Key.UNKNOWN) {
                // 任意有效按键即返回
                return;
            }
        }
    }

    // ==================== 底层按键读取 ====================

    /**
     * 读取一个语义化按键。ESC + [ + X 序列合并为方向键。
     * 使用轮询读取（polling），确保在 Windows JLine 上可靠工作。
     */
    private int readKey() throws IOException {
        int first = readOneChar();
        if (first != 27) return classifyKey(first);

        // ESC 收到 — 短暂轮询判断是否为方向键序列（现代终端 <10ms 即可送达）
        int second = pollChar(50);
        if (second == '[' || second == 'O') {
            int third = pollChar(50);
            switch (third) {
                case 'A': return Key.UP;
                case 'B': return Key.DOWN;
                case 'C': return Key.RIGHT;
                case 'D': return Key.LEFT;
                default:  return Key.UNKNOWN;
            }
        }
        return Key.ESC;
    }

    /**
     * 输入框用：ESC 逃逸，方向键被吞掉。
     */
    private int readKeyRaw() throws IOException {
        int c = readOneChar();
        if (c != 27) return classifyKey(c);

        int second = pollChar(50);
        if (second == '[' || second == 'O') {
            pollChar(50);      // 吞掉方向码
            return Key.UNKNOWN;
        }
        return Key.ESC;
    }

    /**
     * 增强输入按键：返回方向键、Home/End/Delete，不吞掉。
     * ESC 仍返回 Key.ESC（带短暂轮询延迟以区分方向键序列）。
     */
    private int readKeyInput() throws IOException {
        int c = readOneChar();
        if (c == 9) return Key.TAB;
        if (c != 27) return classifyKey(c);

        int second = pollChar(50);
        if (second == '[') {
            int third = pollChar(50);
            switch (third) {
                case 'A': return Key.UP;
                case 'B': return Key.DOWN;
                case 'C': return Key.RIGHT;
                case 'D': return Key.LEFT;
                case 'H': return Key.HOME;
                case 'F': return Key.END;
                case '3': {   // Delete: ESC [ 3 ~
                    int fourth = pollChar(50);
                    if (fourth == '~') return Key.DELETE;
                    return Key.UNKNOWN;
                }
                default:  return Key.UNKNOWN;
            }
        }
        if (second == 'O') {
            int third = pollChar(50);
            switch (third) {
                case 'A': return Key.UP;
                case 'B': return Key.DOWN;
                case 'C': return Key.RIGHT;
                case 'D': return Key.LEFT;
                case 'H': return Key.HOME;
                case 'F': return Key.END;
                default:  return Key.UNKNOWN;
            }
        }
        return Key.ESC;
    }

    /**
     * 轮询读取：在 timeoutMs 内反复尝试读取一个字符，超时返回 -1。
     * 每次尝试用很短的超时（最多 30ms），确保整体延迟不超过 timeoutMs。
     */
    private int pollChar(int timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            long remaining = deadline - System.currentTimeMillis();
            int wait = (int) Math.min(remaining, 30);
            if (wait <= 0) break;
            try {
                int c = terminal.reader().read(wait);
                if (c >= 0) return c;
            } catch (Exception e) {
                break;
            }
        }
        return -1;
    }

    /** 阻塞读取单个字符 */
    private int readOneChar() throws IOException {
        return terminal.reader().read();
    }

    /** 将原始字符分类为语义常量 */
    private int classifyKey(int c) {
        if (c == -1 || c == -2 || c == 3) return Key.CTRL_C;
        if (c == '\r' || c == '\n')         return Key.ENTER;
        if (c == 127 || c == '\b' || c == 8) return Key.BACKSPACE;
        return c;
    }

    // ==================== 工具 ====================

    private void updateTerminalSize() {
        int w = terminal.getWidth();
        int h = terminal.getHeight();
        if (w <= 0) w = 80;
        if (h <= 0) h = 24;
        screen.setTerminalSize(w, h);
    }

    private void write(String s) { writer.write(s); }
    private void flush() { writer.flush(); }

    // ==================== 路径/命令补全 ====================

    /** 获取路径 Tab 补全建议：返回当前输入的最佳补全后缀，无补全时返回 null */
    private String getPathCompletion(String partial) {
        if (partial.isEmpty()) return null;

        // Windows 盘符处理：如 "D:" → 补全 "\"
        if (partial.matches("[a-zA-Z]:")) {
            return File.separator;
        }

        File file = new File(partial);
        String parentPath = file.getParent();
        String prefix = file.getName();

        if (parentPath == null) {
            if (partial.endsWith(File.separator)) {
                parentPath = partial;
                prefix = "";
            } else {
                parentPath = ".";
                prefix = partial;
            }
        }

        File parentDir = new File(parentPath);
        if (!parentDir.isDirectory()) return null;

        // prefix 为空（刚打完分隔符），不做补全
        if (prefix.isEmpty()) return null;

        final String matchPrefix = prefix.toLowerCase();
        File[] children = parentDir.listFiles(
                (dir, name) -> name.toLowerCase().startsWith(matchPrefix));
        if (children == null || children.length == 0) return null;

        // 找最长公共前缀
        String commonPrefix = children[0].getName();
        for (int i = 1; i < children.length; i++) {
            commonPrefix = longestCommonPrefix(commonPrefix, children[i].getName());
        }

        if (commonPrefix.length() <= prefix.length()) {
            // 没有更多公共前缀，但如果只有一个匹配且是目录则补分隔符
            if (children.length == 1 && children[0].isDirectory()) {
                return File.separator;
            }
            return null;
        }

        String completion = commonPrefix.substring(prefix.length());
        if (children.length == 1 && children[0].isDirectory()) {
            completion += File.separator;
        }
        return completion;
    }

    /** 获取命令 Tab 补全建议 */
    private String getCommandCompletion(String partial) {
        if (partial.isEmpty()) return null;

        // 使用 Command 枚举在当前模式下查找匹配
        java.util.List<Command> matches = Command.match(partial, this.mode);
        if (matches.isEmpty()) return null;

        String match = matches.get(0).getName();
        for (int i = 1; i < matches.size(); i++) {
            match = longestCommonPrefix(match, matches.get(i).getName());
        }
        if (match.length() > partial.length()) {
            return match.substring(partial.length());
        }
        return null;
    }

    private static String longestCommonPrefix(String a, String b) {
        int minLen = Math.min(a.length(), b.length());
        for (int i = 0; i < minLen; i++) {
            if (a.charAt(i) != b.charAt(i)) return a.substring(0, i);
        }
        return a.substring(0, minLen);
    }

    // ==================== 自动扫描 books 目录 ====================

    /** 扫描 books/ 目录下的 EPUB 文件，按全路径去重后自动添加到书架 */
    private void scanBooksDirectory() {
        if (!config.isAutoScanBooks()) return;

        // 使用配置中的扫描目录列表
        List<String> dirs = config.getScanDirectories();
        String[] candidates = dirs.toArray(new String[0]);
        File booksDir = null;
        for (String candidate : candidates) {
            File dir = new File(candidate);
            if (dir.isDirectory()) {
                booksDir = dir;
                break;
            }
        }
        if (booksDir == null) return;

        File[] epubFiles = booksDir.listFiles(
                (dir, name) -> name.toLowerCase().endsWith(".epub"));
        if (epubFiles == null || epubFiles.length == 0) return;

        int addedCount = 0;
        for (File f : epubFiles) {
            try {
                String canonical = f.getCanonicalPath();
                // 按全路径检查是否已在书架中
                boolean exists = false;
                for (LibraryEntry e : library) {
                    if (e.getPath().equalsIgnoreCase(canonical)) {
                        exists = true;
                        break;
                    }
                }
                if (exists) continue;

                try {
                    EpubParser parser = new EpubParser();
                    Book book = parser.parse(canonical);
                    library.add(new LibraryEntry(canonical, book.getTitle(), book.getAuthor()));
                } catch (Exception ex) {
                    String name = f.getName();
                    if (name.toLowerCase().endsWith(".epub")) name = name.substring(0, name.length() - 5);
                    library.add(new LibraryEntry(canonical, name, "未知作者"));
                }
                addedCount++;
            } catch (IOException ignored) {
                // 跳过无法读取的文件
            }
        }

        if (addedCount > 0) {
            storage.saveLibrary(library);
            librarySelected = library.size() - addedCount;  // 选中第一本新添加的
        }
    }

    // ==================== 命令补全 ====================

    /**
     * 根据用户输入和当前模式匹配命令，返回前 5 条为 {名称, 描述} 数组。
     * 输入不以 / 开头时返回空数组。自动使用当前 this.mode 过滤。
     */
    private String[][] matchCommands(String input) {
        java.util.List<Command> matches = Command.match(input, this.mode);
        String[][] result = new String[matches.size()][];
        for (int i = 0; i < matches.size(); i++) {
            Command cmd = matches.get(i);
            result[i] = new String[]{cmd.getName(), cmd.getDescription()};
        }
        return result;
    }

    /** 比较实际按键与配置的按键，字母键支持大小写等效 */
    private static boolean matchesKey(int pressed, int configured) {
        if (pressed == configured) return true;
        if (configured >= 'a' && configured <= 'z') return pressed == configured - 32;
        if (configured >= 'A' && configured <= 'Z') return pressed == configured + 32;
        return false;
    }

    /** 返回 input 到 target 的补全后缀，例如 input="/r", target="/read" → "ead" */
    private static String getCompletionSuffix(String input, String target) {
        if (target.startsWith(input) && target.length() > input.length()) {
            return target.substring(input.length());
        }
        return null;
    }

    /**
     * 语义化按键常量。
     */
    private static final class Key {
        static final int ESC      = -10;
        static final int ENTER    = -11;
        static final int BACKSPACE = -12;
        static final int CTRL_C   = -13;
        static final int UP       = -20;
        static final int DOWN     = -21;
        static final int RIGHT    = -22;
        static final int LEFT     = -23;
        static final int UNKNOWN  = -99;
        static final int TAB      = 9;
        static final int HOME     = -24;
        static final int END      = -25;
        static final int DELETE   = -26;
    }
}
