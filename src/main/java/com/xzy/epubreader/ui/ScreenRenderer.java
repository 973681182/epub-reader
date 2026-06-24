package com.xzy.epubreader.ui;

import com.xzy.epubreader.model.Book;
import com.xzy.epubreader.model.Chapter;
import com.xzy.epubreader.model.LibraryEntry;
import com.xzy.epubreader.renderer.PageRenderer;

import java.io.PrintWriter;
import java.util.List;

/**
 * 屏幕绘制工具：负责所有画面的渲染，包括交替屏幕缓冲区控制。
 */
public class ScreenRenderer {

    // ANSI
    private static final String CLEAR_SCREEN = "\033[2J";
    private static final String CURSOR_HOME = "\033[H";
    private static final String ALT_SCREEN_ON  = "\033[?1049h";
    private static final String ALT_SCREEN_OFF = "\033[?1049l";
    private static final String RESET   = "\033[0m";
    private static final String BOLD    = "\033[1m";
    private static final String DIM     = "\033[2m";
    private static final String CYAN    = "\033[36m";
    private static final String REVERSE = "\033[7m";
    private static final String GREEN   = "\033[32m";
    private static final String RED     = "\033[31m";
    private static final String HINT    = "\033[94m";  // 亮蓝色，与正常文字有明显色差

    private int terminalWidth;
    private int terminalHeight;
    private final PrintWriter writer;

    public ScreenRenderer(PrintWriter writer, int terminalWidth, int terminalHeight) {
        this.writer = writer;
        this.terminalWidth = terminalWidth;
        this.terminalHeight = terminalHeight;
    }

    public void setTerminalSize(int width, int height) {
        this.terminalWidth = width;
        this.terminalHeight = height;
    }

    // DECSCUSR 光标样式
    private static final String CURSOR_STYLE  = "\033[2 q";  // 不闪动方块
    private static final String CURSOR_COLOR  = "\033]12;#c0c0c0\007"; // 亮灰色，与文字颜色协调
    private static final String CURSOR_RESET  = "\033]112\007\033[0 q"; // 恢复默认颜色和样式
    private static final String CURSOR_HIDE   = "\033[?25l";
    private static final String CURSOR_SHOW   = "\033[?25h";

    // ==================== 交替屏幕缓冲区 ====================

    public void enterAltScreen() {
        write(ALT_SCREEN_ON + CLEAR_SCREEN + CURSOR_HOME + CURSOR_STYLE + CURSOR_COLOR + CURSOR_HIDE);
    }

    public void exitAltScreen() {
        write(CURSOR_RESET + ALT_SCREEN_OFF);
        flush();
    }

    // ==================== LIBRARY 模式 ====================

    /**
     * 绘制书架画面。
     * @param entries 书架条目列表
     * @param selectedIndex 当前选中索引 (-1 表示无选中)
     */
    public void drawLibraryScreen(List<LibraryEntry> entries, int selectedIndex, String message, boolean isError) {
        clearContent();
        int row = 0;

        drawLine(row++, centerText(bold("EPUB 书架")));
        drawLine(row++, repeat("-", terminalWidth));
        row++;

        if (entries.isEmpty()) {
            drawLine(row++, "  书架为空");
            drawLine(row++, "  按 [a] 添加 EPUB 文件");
        } else {
            for (int i = 0; i < entries.size(); i++) {
                if (row >= terminalHeight - 4) {
                    drawLine(row++, dim("  ... 共 " + entries.size() + " 本"));
                    break;
                }
                LibraryEntry e = entries.get(i);
                String text = String.format("  %s  %s", e.getTitle(), dim("— " + e.getAuthor()));
                if (i == selectedIndex) {
                    // 高亮选中行
                    text = REVERSE + padRight(text, terminalWidth) + RESET;
                }
                drawLine(row++, text);
            }
        }

        fillRemainingLines(row, terminalHeight - 2);

        // 3 行命令面板：上下横线边框 + 中间内容
        drawCommandPanelFrame();

        // 面板内：消息提示 或 未激活命令输入行
        if (message != null && !message.isEmpty()) {
            moveCursorTo(terminalHeight - 1, 1);
            String text = "  " + message;
            write(isError ? red(padRight(text, terminalWidth)) : green(padRight(text, terminalWidth)));
        } else {
            drawInactiveCommandLine("[▲ ▼ ]选择 [Enter]打开 [a]添加 [d]移除 [q]退出");
        }
        write(CURSOR_HIDE);
        flush();
    }

    /**
     * 绘制正在加载画面。
     */
    public void drawLoadingScreen(String filePath) {
        clearContent();
        int row = 0;

        drawLine(row++, bold("正在解析 EPUB..."));
        drawLine(row++, repeat("-", terminalWidth));
        row++;
        drawLine(row++, "  文件: " + filePath);
        row++;
        drawLine(row++, dim("  请稍候..."));

        fillRemainingLines(row);
        drawBottomBar("");
    }

    // ==================== COMMAND 模式 ====================

    public void drawCommandMode(Book book, String message) {
        clearContent();
        int row = 0;

        drawLine(row++, centerText(bold("EPUB 阅读器")));
        drawLine(row++, "");
        drawLine(row++, "  " + bold("书名: ") + book.getTitle());
        drawLine(row++, "  " + bold("作者: ") + book.getAuthor());
        drawLine(row++, "  " + bold("章节: ") + book.getChapterCount() + " 章  ·  " + book.getTotalPages() + " 页");
        drawLine(row++, "");

        if (book.getTotalPages() > 0) {
            drawLine(row++, "  " + bold("上次阅读: ") + "第" + (book.getCurrentChapter() + 1) + "章 "
                    + book.getCurrentChapterTitle()
                    + " · 页 " + (book.getCurrentPage() + 1) + "/" + book.getCurrentChapterPageCount()
                    + " · 进度 " + String.format("%.1f%%", book.getProgressPercent()));
        }

        row++;
        if (message != null && !message.isEmpty()) {
            drawLine(row++, dim("  " + message));
        }

        if (book.getTotalPages() > 0) {
            drawLine(row++, dim("  输入 /read 开始阅读，/help 查看所有命令"));
        } else {
            drawLine(row++, dim("  输入 /help 查看所有命令"));
        }

        fillRemainingLines(row);
        drawCommandBar();
    }

    public void drawTocScreen(Book book) {
        clearContent();
        int row = 0;

        drawLine(row++, bold("章节目录"));
        drawLine(row++, repeat("-", terminalWidth));
        row++;

        for (int i = 0; i < book.getChapterCount(); i++) {
            Chapter ch = book.getChapter(i);
            String marker = (i == book.getCurrentChapter()) ? " >" : "  ";
            String text = String.format("%s%3d. %s  (%d页)", marker, i + 1, ch.getTitle(), ch.getPageCount());
            if (text.length() > terminalWidth) {
                text = text.substring(0, terminalWidth - 3) + "...";
            }
            drawLine(row++, text);
            if (row >= terminalHeight - 2) {
                drawLine(row++, dim("  ... 共 " + book.getChapterCount() + " 章"));
                break;
            }
        }

        fillRemainingLines(row);
        drawBottomBar(" 按任意键返回 ");
    }

    public void drawHelpScreen() {
        clearContent();
        int row = 0;

        drawLine(row++, bold("命令帮助"));
        drawLine(row++, repeat("-", terminalWidth));
        row++;

        drawLine(row++, bold("  阅读模式命令:"));
        for (Command cmd : Command.values()) {
            if (!cmd.isAvailableIn(Mode.READING)) {
                continue;
            }
            if (row >= terminalHeight - 10) break;
            drawLine(row++, String.format("  %-14s %s", cyan(cmd.getName()), dim(cmd.getDescription())));
        }

        row++;
        drawLine(row++, bold("  书架模式命令:"));
        for (Command cmd : Command.values()) {
            if (!cmd.isAvailableIn(Mode.LIBRARY)) {
                continue;
            }
            if (row >= terminalHeight - 3) break;
            drawLine(row++, String.format("  %-14s %s", cyan(cmd.getName()), dim(cmd.getDescription())));
        }

        row++;
        drawLine(row++, dim("  阅读模式按键:"));
        drawLine(row++, dim("  Enter / 空格 / ▼  / ▶  = 下一页    ▲  / ◀  = 上一页"));
        drawLine(row++, dim("  / = 输入命令（书架/阅读模式通用）"));
        drawLine(row++, dim("  Esc = 返回书架（阅读模式）/ 取消命令输入"));

        fillRemainingLines(row);
        drawBottomBar(" 按任意键返回 ");
    }

    public void drawProgressScreen(Book book) {
        clearContent();
        int row = 0;

        drawLine(row++, bold("阅读进度"));
        drawLine(row++, repeat("-", terminalWidth));
        row++;
        drawLine(row++, String.format("  书名: %s", book.getTitle()));
        drawLine(row++, String.format("  当前章节: 第%d章 %s", book.getCurrentChapter() + 1, book.getCurrentChapterTitle()));
        drawLine(row++, String.format("  章节内页码: %d / %d", book.getCurrentPage() + 1, book.getCurrentChapterPageCount()));
        drawLine(row++, String.format("  全书页码: %d / %d", book.getCurrentGlobalPage() + 1, book.getTotalPages()));
        drawLine(row++, String.format("  进度: %.1f%%", book.getProgressPercent()));
        row++;

        double pct = book.getProgressPercent();
        int barWidth = terminalWidth - 4;
        int filled = (int) (barWidth * pct / 100.0);
        StringBuilder bar = new StringBuilder("  [");
        for (int i = 0; i < barWidth; i++) {
            bar.append(i < filled ? "=" : "-");
        }
        bar.append("]");
        drawLine(row++, bar.toString());

        fillRemainingLines(row);
        drawBottomBar(" 按任意键返回 ");
    }

    public void drawInfoScreen(Book book) {
        clearContent();
        int row = 0;

        drawLine(row++, bold("书籍信息"));
        drawLine(row++, repeat("-", terminalWidth));
        row++;
        drawLine(row++, String.format("  书名: %s", book.getTitle()));
        drawLine(row++, String.format("  作者: %s", book.getAuthor()));
        drawLine(row++, String.format("  章节数: %d", book.getChapterCount()));
        drawLine(row++, String.format("  总页数: %d", book.getTotalPages()));
        drawLine(row++, String.format("  终端尺寸: %d x %d", terminalWidth, terminalHeight));
        row++;
        drawLine(row++, dim("  每页行数: " + (terminalHeight - 2)));

        fillRemainingLines(row);
        drawBottomBar(" 按任意键返回 ");
    }

    // ==================== READING 模式 ====================

    public void drawReadingMode(Book book) {
        clearContent();
        int row = 0;

        Chapter chapter = book.getCurrentChapterObj();
        if (chapter != null) {
            String pageContent = chapter.getPage(book.getCurrentPage());
            if (pageContent != null && !pageContent.isEmpty()) {
                String[] lines = pageContent.split("\n", -1);
                for (String line : lines) {
                    if (row >= terminalHeight - 4) break;
                    if (line.length() > terminalWidth) {
                        line = line.substring(0, terminalWidth);
                    }
                    drawLine(row++, line);
                }
            }
        }

        fillRemainingLines(row, terminalHeight - 3);
        drawStatusBar(book);
        drawCommandPanelFrame();
        drawInactiveCommandLine("[Enter/空格]下一页 [▲ ]上一页 [Esc]返回书架");
        write(CURSOR_HIDE);
        flush();
    }

    // ==================== 组件绘制 ====================

    private void drawStatusBar(Book book) {
        moveCursorTo(terminalHeight - 3, 1);
        write(REVERSE);

        int chIdx = book.getCurrentChapter() + 1;
        int curPage = book.getCurrentPage() + 1;
        int totalChPages = book.getCurrentChapterPageCount();
        double pct = book.getProgressPercent();

        String status = String.format(" 第%d章 %s · 页 %d/%d · 全书 %.1f%% ",
                chIdx, book.getCurrentChapterTitle(), curPage, totalChPages, pct);
        write(padRight(status, terminalWidth));
        write(RESET);
    }

    private void drawCommandBar() {
        moveCursorTo(terminalHeight, 1);
        write(CYAN + "> " + RESET + CURSOR_SHOW);
        flush();
    }

    private void drawBottomBar(String hint) {
        moveCursorTo(terminalHeight, 1);
        write(dim(hint) + CURSOR_HIDE);
        flush();
    }

    /** 绘制命令面板的上下横线边框（终端底部 3 行面板） */
    private void drawCommandPanelFrame() {
        String border = dim(repeat("─", terminalWidth));
        moveCursorTo(terminalHeight - 2, 1);
        write(border);
        moveCursorTo(terminalHeight, 1);
        write(border);
    }

    /** 绘制面板内未激活的命令输入行：青色 > 提示符 + 灰色提示 */
    private void drawInactiveCommandLine() {
        drawInactiveCommandLine(null);
    }

    /** 绘制面板内未激活的命令输入行，可附加模式专属提示 */
    private void drawInactiveCommandLine(String extraHints) {
        moveCursorTo(terminalHeight - 1, 1);
        write("\033[K");
        write(CYAN + "> " + RESET);
        if (extraHints != null && !extraHints.isEmpty()) {
            write(dim("[/]命令  " + extraHints));
        } else {
            write(dim("[/]命令"));
        }
    }

    /**
     * 绘制展开的命令输入区域（命令激活时），可显示匹配命令列表。
     * <p>
     * 当 commands 为空或 length=0 时只显示最小面板（4 行）；
     * 有匹配命令时面板向上扩展，每多一条命令多占一行，最多显示 5 条。
     * 底部提示分左右：左侧通用提示（如 "ESC 退出命令"），右侧执行结果（绿色/红色）。
     * 左右放不下时右侧结果独佔一行。书籍内容区域不变，被覆盖的部分直接遮挡。
     *
     * @param input         用户已输入的文本
     * @param cursorPos     光标在输入中的位置
     * @param completion    补全建议（灰色显示在输入末尾）
     * @param commands      匹配的命令数组 [{name, desc}, ...]，最多 5 条
     * @param selectedIndex 当前选中的命令索引，-1 表示无选中
     * @param leftHint      左侧通用提示（如 "ESC 退出命令…"），null 则不显示
     * @param rightHint     右侧执行结果，null 则不显示
     * @param rightIsError  右侧结果是错误（红色）还是成功（绿色）
     */
    public void drawExpandedCommandAreaWithHints(String input, int cursorPos, String completion,
                                                  String[][] commands, int selectedIndex,
                                                  String leftHint, String rightHint, boolean rightIsError) {
        String border = dim(repeat("─", terminalWidth));
        int cmdCount = (commands != null) ? Math.min(commands.length, 5) : 0;

        // 是否需要额外行：右侧结果放不下时多占一行
        boolean needExtraLine = false;
        if (rightHint != null && !rightHint.isEmpty() && leftHint != null && !leftHint.isEmpty()) {
            int leftW = displayWidth(leftHint);
            int rightW = displayWidth(rightHint);
            // 左提示从第 3 列开始（缩进 2），右侧至少留 2 列间距
            needExtraLine = (leftW + rightW + 4 > terminalWidth);
        }

        int extra = needExtraLine ? 1 : 0;

        // 面板行号计算（extra 行会推高整个面板）
        // cmdCount>0: 上边框 + 输入 + 分隔 + N条命令 + 下边框 (+ 提示行)
        // cmdCount=0: 上边框 + 输入 + 下边框 (+ 提示行)
        int panelTop, inputRow, sepRow, cmdStart;
        if (cmdCount > 0) {
            panelTop = terminalHeight - (4 + cmdCount + extra);
            inputRow = terminalHeight - (3 + cmdCount + extra);
            sepRow   = terminalHeight - (2 + cmdCount + extra);
            cmdStart = terminalHeight - (1 + cmdCount + extra);
        } else {
            panelTop = terminalHeight - (3 + extra);
            inputRow = terminalHeight - (2 + extra);
            sepRow   = 0;   // unused
            cmdStart = 0;   // unused
        }
        int bottomBorder = terminalHeight - (1 + extra);  // H-1 or H-2
        int hintRow1 = terminalHeight - extra;            // H or H-1
        int hintRow2 = terminalHeight;                     // H (only used when extra=1)

        // 上边框
        moveCursorTo(panelTop, 1);
        write(border);

        // 命令输入行
        moveCursorTo(inputRow, 1);
        write("\033[K");
        write(CYAN + "> " + RESET);
        write(input);
        if (completion != null && !completion.isEmpty()) {
            write(hint(completion));
        }

        if (cmdCount > 0) {
            // 分隔线
            moveCursorTo(sepRow, 1);
            write(border);

            // 列出匹配命令
            for (int i = 0; i < cmdCount; i++) {
                int row = cmdStart + i;
                moveCursorTo(row, 1);
                write("\033[K");
                String name = commands[i][0];
                String desc = commands[i][1];
                String line;
                if (i == selectedIndex) {
                    line = REVERSE + "  " + padRight(name, 14) + " " + desc;
                    line = padRightVisual(line, terminalWidth);
                    line = line + RESET;
                } else {
                    line = "  " + dim(padRight(name, 14)) + " " + dim(desc);
                    line = padRightVisual(line, terminalWidth);
                }
                write(line);
            }
        }

        // 下边框
        moveCursorTo(bottomBorder, 1);
        write(border);

        // 底部提示
        if (!needExtraLine) {
            // 单行：左提示 + 右结果并排
            drawHintLine(hintRow1, leftHint, rightHint, rightIsError);
        } else {
            // 双行：上一行放不下时，先画左提示，下一行画右结果
            drawHintLine(hintRow1, leftHint, null, false);
            drawHintLine(hintRow2, null, rightHint, rightIsError);
        }

        // 光标定位到输入位置（用显示宽度，CJK 字符占 2 列）
        int displayCursor = displayWidth(input.substring(0, Math.min(cursorPos, input.length())));
        moveCursorTo(inputRow, 3 + displayCursor);
        write(CURSOR_SHOW);
        flush();
    }

    /** 在指定行绘制提示：左对齐 leftText，右对齐 rightText（可带颜色） */
    private void drawHintLine(int row, String leftText, String rightText, boolean isError) {
        if ((leftText == null || leftText.isEmpty()) && (rightText == null || rightText.isEmpty())) {
            // 空提示：用空格填充整行
            moveCursorTo(row, 1);
            write(" ".repeat(terminalWidth));
            return;
        }
        moveCursorTo(row, 1);
        write("\033[K");

        if (leftText != null && !leftText.isEmpty()) {
            write(dim("  " + leftText));
        }

        if (rightText != null && !rightText.isEmpty()) {
            // 计算右侧文本的起始列
            int rightW = displayWidth(rightText);
            int startCol = terminalWidth - rightW + 1;
            if (startCol < 3) startCol = 3;  // 至少留 2 列间距
            moveCursorTo(row, startCol);
            write(isError ? red(rightText) : green(rightText));
        }
    }

    /** 命令模式输入行重绘：只更新最底行，不清屏 */
    public void redrawCommandLine(String input, int cursorPos, String completion) {
        moveCursorTo(terminalHeight - 1, 1);
        write("\033[K");
        write(CYAN + "> " + RESET);
        write(input);
        if (completion != null && !completion.isEmpty()) {
            write(hint(completion));
        }
        int displayCursor = displayWidth(input.substring(0, Math.min(cursorPos, input.length())));
        moveCursorTo(terminalHeight - 1, 3 + displayCursor);
        write(CURSOR_SHOW);
        flush();
    }

    // ==================== 底层绘制 ====================

    private void clearContent() {
        write(CLEAR_SCREEN + CURSOR_HOME);
    }

    private void drawLine(int row, String text) {
        if (row >= terminalHeight) return;
        moveCursorTo(row + 1, 1);

        String safe = text;
        if (displayWidth(text) > terminalWidth) {
            safe = truncateToWidth(text, terminalWidth);
        }
        safe = padRightVisual(safe, terminalWidth);
        write(safe);
    }

    private void fillRemainingLines(int startRow) {
        fillRemainingLines(startRow, terminalHeight - 1);
    }

    private void fillRemainingLines(int startRow, int endRow) {
        String blank = " ".repeat(terminalWidth);
        for (int r = startRow; r < endRow; r++) {
            drawLine(r, blank);
        }
    }

    private void moveCursorTo(int row, int col) {
        write(String.format("\033[%d;%dH", row, col));
    }

    private void write(String s) {
        writer.write(s);
    }

    private void flush() {
        writer.flush();
    }

    // ==================== 文本样式 ====================

    private String bold(String s) { return BOLD + s + RESET; }
    private String dim(String s) { return DIM + s + RESET; }
    private String cyan(String s) { return CYAN + s + RESET; }
    private String green(String s) { return GREEN + s + RESET; }
    private String red(String s) { return RED + s + RESET; }
    private String hint(String s) { return HINT + s + RESET; }

    private String centerText(String text) {
        int textWidth = text.replaceAll("\033\\[[;\\d]*m", "").length();
        int padding = Math.max(0, (terminalWidth - textWidth) / 2);
        return " ".repeat(padding) + text;
    }

    private String repeat(String ch, int count) {
        return ch.repeat(Math.max(0, Math.min(count, terminalWidth)));
    }

    private String padRight(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }

    // ==================== 视觉宽度计算 ====================

    private int displayWidth(String s) {
        int w = 0;
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) { if (c == 'm') esc = false; continue; }
            if (c == '\033') { esc = true; continue; }
            w += PageRenderer.getCharDisplayWidth(c);
        }
        return w;
    }

    private String truncateToWidth(String s, int max) {
        StringBuilder sb = new StringBuilder();
        int w = 0;
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c);
            if (esc) { if (c == 'm') esc = false; continue; }
            if (c == '\033') { esc = true; continue; }
            w++;
            if (w >= max) break;
        }
        return sb.toString();
    }

    private String padRightVisual(String s, int target) {
        int dw = displayWidth(s);
        if (dw >= target) return s;
        return s + " ".repeat(target - dw);
    }
}
