package com.xzy.epubreader.ui;

import com.xzy.epubreader.model.Book;
import com.xzy.epubreader.model.LibraryEntry;
import com.xzy.epubreader.parser.EpubParser;
import com.xzy.epubreader.renderer.PageRenderer;
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

    private Terminal terminal;
    private PrintWriter writer;
    private ScreenRenderer screen;

    private Mode mode;
    private boolean running;

    // 书架
    private List<LibraryEntry> library;
    private int librarySelected;
    private String libraryMessage = null;
    private boolean libraryMessageIsError = false;

    // 输入历史
    private final List<String> pathHistory = new ArrayList<>();
    private int pathHistoryIndex = -1;
    private String pathHistoryDraft = null;

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
        scanBooksDirectory();
        initTerminal();

        try {
            while (running) {
                updateTerminalSize();
                switch (mode) {
                    case LIBRARY:  libraryLoop(); break;
                    case COMMAND:  commandLoop(); break;
                    case READING:  readingLoop(); break;
                }
            }
        } finally {
            shutdown();
        }
    }

    public void openDirectly(String filePath) throws IOException {
        storage.init();
        library = storage.loadLibrary();
        scanBooksDirectory();
        initTerminal();

        try {
            openBook(filePath);
            if (currentBook != null) {
                mode = Mode.READING;
                while (running) {
                    updateTerminalSize();
                    if (mode == Mode.READING) readingLoop();
                    else break;
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
        screen.enterAltScreen();
        terminal.enterRawMode();
    }

    private void shutdown() {
        try {
            screen.exitAltScreen();
            terminal.close();
        } catch (Exception ignored) {}
    }

    // ==================== LIBRARY 模式 ====================

    /**
     * 书架模式主循环：显示书籍列表，支持单键操作和行内命令输入。
     * 按 / 激活命令行，Enter 执行命令，Esc 取消命令。
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
                    handleLibraryCommand(cmd);
                    // handleLibraryCommand 内部设置 libraryMessage；错误信息已通过该机制处理
                    // 如果还在书架模式，退出命令模式
                    if (mode == Mode.LIBRARY) {
                        cmdInput.setLength(0);
                        cmdCursor = 0;
                        // 根据是否有错误消息决定是否保持命令模式
                        if (libraryMessage != null && libraryMessageIsError) {
                            cmdErrorMessage = libraryMessage;
                            libraryMessage = null;
                            libraryMessageIsError = false;
                            selectedCmdIndex = 0;
                            // cmdActive 保持 true
                        } else {
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

            // ---- 书架模式按键处理 ----
            if (key == '/') {
                // 激活命令行，预填 /
                cmdActive = true;
                cmdInput.setLength(0);
                cmdInput.append('/');
                cmdCursor = 1;
                continue;
            }

            switch (key) {
                case Key.ENTER:
                    if (!library.isEmpty() && librarySelected >= 0 && librarySelected < library.size()) {
                        openBook(library.get(librarySelected).getPath());
                        if (currentBook != null) {
                            mode = Mode.READING;
                            return;
                        }
                    }
                    break;

                case Key.UP:
                    if (librarySelected > 0) librarySelected--;
                    break;

                case Key.DOWN:
                    if (librarySelected < library.size() - 1) librarySelected++;
                    break;

                case 'q': case 'Q':
                    running = false;
                    return;

                case 'a': case 'A':
                    addBookFlow();
                    break;

                case 'd': case 'D':
                    confirmDeleteFlow();
                    break;
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

            case ADD:
                addBookFlow();
                return;

            case HELP:
                screen.drawHelpScreen();
                waitForAnyKey();
                return;

            case QUIT:
            case EXIT:
                running = false;
                return;

            default:
                libraryMessage = "该命令在当前模式下不可用: " + cmd.getName();
                libraryMessageIsError = true;
        }
    }

    /** 添加书籍流程：支持光标移动、历史记录、Tab 路径补全 */
    private void addBookFlow() throws IOException {
        StringBuilder sb = new StringBuilder();
        int cursorPos = 0;

        while (true) {
            String completion = getPathCompletion(sb.toString());
            screen.drawAddBookPrompt(sb.toString(), cursorPos, completion);

            int c = readKeyInput();
            if (c == Key.ESC) return;
            if (c == Key.CTRL_C) { running = false; return; }
            if (c == Key.ENTER) break;

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
                if (!pathHistory.isEmpty()) {
                    if (pathHistoryIndex == -1) {
                        pathHistoryDraft = sb.toString();
                        pathHistoryIndex = pathHistory.size() - 1;
                    } else if (pathHistoryIndex > 0) {
                        pathHistoryIndex--;
                    }
                    sb = new StringBuilder(pathHistory.get(pathHistoryIndex));
                    cursorPos = sb.length();
                }
                continue;
            }

            if (c == Key.DOWN) {
                if (pathHistoryIndex != -1) {
                    if (pathHistoryIndex < pathHistory.size() - 1) {
                        pathHistoryIndex++;
                        sb = new StringBuilder(pathHistory.get(pathHistoryIndex));
                    } else {
                        pathHistoryIndex = -1;
                        sb = new StringBuilder(pathHistoryDraft != null ? pathHistoryDraft : "");
                        pathHistoryDraft = null;
                    }
                    cursorPos = sb.length();
                }
                continue;
            }

            if (c == Key.TAB) {
                String comp = getPathCompletion(sb.toString());
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

        String path = sb.toString().trim();
        if (!path.isEmpty()) {
            // 去掉两端引号（复制路径时常带引号）
            if (path.length() >= 2) {
                char first = path.charAt(0);
                char last = path.charAt(path.length() - 1);
                if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                    path = path.substring(1, path.length() - 1).trim();
                }
            }
            addToLibrary(path);
            // 添加到历史记录
            pathHistory.add(path);
            pathHistoryIndex = -1;
            pathHistoryDraft = null;
        }
    }

    /** 删除确认流程：覆盖底部栏为确认提示，Enter 确认删除，ESC 取消 */
    private void confirmDeleteFlow() throws IOException {
        if (library.isEmpty() || librarySelected < 0 || librarySelected >= library.size()) {
            return;
        }
        String title = library.get(librarySelected).getTitle();

        // 重绘书架画面并覆盖底部栏为红色确认提示
        screen.drawLibraryScreen(library, librarySelected, libraryMessage, libraryMessageIsError);
        screen.drawDeleteConfirmBar(title);

        while (true) {
            int key = readKey();
            if (key == Key.CTRL_C) { running = false; return; }
            if (key == Key.ENTER) {
                library.remove(librarySelected);
                if (librarySelected >= library.size()) librarySelected = Math.max(0, library.size() - 1);
                storage.saveLibrary(library);
                libraryMessage = "已删除: 《" + title + "》";
                libraryMessageIsError = false;
                return;
            }
            if (key == Key.ESC) {
                return;
            }
            // 其他按键忽略
        }
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
            if (key == Key.ESC) {
                // 返回书架
                saveCurrentProgress();
                currentBook = null;
                commandHandler = null;
                mode = Mode.LIBRARY;
                return;
            }

            if (key == '/') {
                // 激活命令行，预填 /
                cmdActive = true;
                cmdInput.setLength(0);
                cmdInput.append('/');
                cmdCursor = 1;
                continue;
            }

            if (key == Key.ENTER || key == ' ' || key == Key.DOWN || key == Key.RIGHT) {
                handleNextPage();
            } else if (key == Key.UP || key == Key.LEFT) {
                handlePrevPage();
            }
        }
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

        // ESC 收到 — 轮询等待后续字符
        int second = pollChar(500);
        // 支持两种方向键编码: ESC [ X (ANSI) 和 ESC O X (应用模式)
        if (second == '[' || second == 'O') {
            int third = pollChar(200);
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

        int second = pollChar(300);
        if (second == '[' || second == 'O') {
            pollChar(200);      // 吞掉方向码
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

        int second = pollChar(300);
        if (second == '[') {
            int third = pollChar(200);
            switch (third) {
                case 'A': return Key.UP;
                case 'B': return Key.DOWN;
                case 'C': return Key.RIGHT;
                case 'D': return Key.LEFT;
                case 'H': return Key.HOME;
                case 'F': return Key.END;
                case '3': {   // Delete: ESC [ 3 ~
                    int fourth = pollChar(100);
                    if (fourth == '~') return Key.DELETE;
                    return Key.UNKNOWN;
                }
                default:  return Key.UNKNOWN;
            }
        }
        if (second == 'O') {
            int third = pollChar(200);
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
        // 尝试多个可能的 books 目录位置
        String[] candidates = {"books", "../books"};
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
