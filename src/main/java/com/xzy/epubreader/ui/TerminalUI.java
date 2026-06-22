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

    private enum Mode {
        LIBRARY,
        COMMAND,
        READING
    }

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
        initTerminal();

        try {
            openBook(filePath);
            if (currentBook != null) {
                mode = Mode.COMMAND;
                while (running) {
                    updateTerminalSize();
                    if (mode == Mode.COMMAND) commandLoop();
                    else if (mode == Mode.READING) readingLoop();
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

    private void libraryLoop() throws IOException {
        while (mode == Mode.LIBRARY && running) {
            updateTerminalSize();
            screen.drawLibraryScreen(library, librarySelected);

            int key = readKey();
            if (key == Key.CTRL_C) { running = false; return; }

            switch (key) {
                case Key.ENTER:
                    if (!library.isEmpty() && librarySelected >= 0 && librarySelected < library.size()) {
                        openBook(library.get(librarySelected).getPath());
                        if (currentBook != null) {
                            mode = Mode.COMMAND;
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
                    if (!library.isEmpty() && librarySelected >= 0 && librarySelected < library.size()) {
                        library.remove(librarySelected);
                        if (librarySelected >= library.size()) librarySelected = Math.max(0, library.size() - 1);
                        storage.saveLibrary(library);
                    }
                    break;
            }
        }
    }

    /** 添加书籍流程：显示输入画面，读取路径，解析并添加到书架 */
    private void addBookFlow() throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            screen.drawAddBookPrompt(sb.toString());
            int c = readKeyRaw();

            if (c == Key.ESC) return;           // 取消
            if (c == Key.CTRL_C) { running = false; return; }
            if (c == Key.ENTER) break;          // 确认

            if (c == Key.BACKSPACE) {
                if (sb.length() > 0) sb.deleteCharAt(sb.length() - 1);
                continue;
            }

            // 可打印字符，回显在输入栏
            if (c >= 32) {
                sb.append((char) c);
            }
        }

        String path = sb.toString().trim();
        if (!path.isEmpty()) {
            addToLibrary(path);
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

            CommandResult result = commandHandler.handle(line);
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

    private void readingLoop() throws IOException {
        while (mode == Mode.READING && running) {
            updateTerminalSize();
            screen.drawReadingMode(currentBook);

            int key = readKey();
            if (key == Key.CTRL_C) { running = false; return; }

            if (key == Key.ESC) {
                mode = Mode.COMMAND;
                return;
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
        if (!file.exists()) return;
        try {
            screen.drawLoadingScreen(filePath);
            EpubParser parser = new EpubParser();
            currentBook = parser.parse(filePath);
            currentBookPath = filePath;
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
        if (!file.exists() || !file.isFile()) return;

        String absPath = file.getAbsolutePath();
        for (LibraryEntry e : library) {
            if (e.getPath().equalsIgnoreCase(absPath)) return;
        }

        try {
            EpubParser parser = new EpubParser();
            Book book = parser.parse(filePath);
            library.add(new LibraryEntry(absPath, book.getTitle(), book.getAuthor()));
        } catch (Exception e) {
            String name = file.getName();
            if (name.toLowerCase().endsWith(".epub")) name = name.substring(0, name.length() - 5);
            library.add(new LibraryEntry(absPath, name, "未知作者"));
        }
        storage.saveLibrary(library);
        librarySelected = library.size() - 1;
    }

    private void updateLibraryEntry(String filePath, String title, String author) {
        String absPath = new File(filePath).getAbsolutePath();
        for (LibraryEntry e : library) {
            if (e.getPath().equalsIgnoreCase(absPath)) {
                e.setTitle(title);
                e.setAuthor(author);
                storage.saveLibrary(library);
                return;
            }
        }
    }

    // ==================== 输入 ====================

    /**
     * 命令模式行输入：支持字符回显、Backspace、ESC 返回书架。
     * 返回 null 表示用户按了 ESC。
     */
    private String readCommandLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = readKeyRaw();
            if (c == Key.CTRL_C) { running = false; return ""; }
            if (c == Key.ENTER) {
                write("\r\n");
                flush();
                break;
            }
            if (c == Key.ESC) {
                // 在命令模式下 ESC = 返回书架
                return null;
            }
            if (c == Key.BACKSPACE) {
                if (sb.length() > 0) {
                    sb.deleteCharAt(sb.length() - 1);
                    write("\b \b");
                    flush();
                }
                continue;
            }
            if (c >= 32) {
                sb.append((char) c);
                write(String.valueOf((char) c));
                flush();
            }
        }
        return sb.toString().trim();
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
    }
}
