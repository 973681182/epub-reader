package com.xzy.epubreader.ui;

import com.xzy.epubreader.model.Book;
import com.xzy.epubreader.model.Chapter;
import com.xzy.epubreader.model.LibraryEntry;

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

    // ==================== 交替屏幕缓冲区 ====================

    public void enterAltScreen() {
        write(ALT_SCREEN_ON + CLEAR_SCREEN + CURSOR_HOME);
    }

    public void exitAltScreen() {
        write(ALT_SCREEN_OFF);
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
                if (row >= terminalHeight - 2) {
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

        fillRemainingLines(row);

        // 消息提示（添加/删除结果）
        if (message != null && !message.isEmpty()) {
            moveCursorTo(terminalHeight - 1, 1);
            String text = "  " + message;
            write(isError ? red(padRight(text, terminalWidth)) : green(padRight(text, terminalWidth)));
        }

        // 底部操作栏
        drawBottomBar(" [↑↓]选择 [Enter]打开 [a]添加 [d]移除 [q]退出 ");
    }

    /**
     * 绘制添加文件的提示画面。
     */
    public void drawAddBookPrompt(String inputSoFar, int cursorPos, String completionHint) {
        clearContent();
        int row = 0;

        drawLine(row++, bold("添加 EPUB 文件"));
        drawLine(row++, repeat("-", terminalWidth));
        row++;
        drawLine(row++, "  请输入 EPUB 文件的完整路径:");
        drawLine(row++, dim("  例如: D:\\books\\三体.epub"));
        row++;

        fillRemainingLines(row);

        // 输入栏（带光标定位和补全提示）
        drawInputBarWithCompletion(inputSoFar, cursorPos, completionHint);
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

        String[][] commands = {
                {"/read", "进入阅读模式，从上次位置继续"},
                {"/read <n>", "进入阅读模式，从第 n 章开始"},
                {"/toc", "显示章节目录"},
                {"/goto <n>", "跳转到全局第 n 页"},
                {"/goto <n>%", "跳转到指定百分比位置"},
                {"/progress", "显示详细阅读进度"},
                {"/info", "显示书籍元信息"},
                {"/back", "返回书架"},
                {"/help", "显示本帮助"},
        };

        for (String[] cmd : commands) {
            if (row >= terminalHeight - 2) break;
            drawLine(row++, String.format("  %-14s %s", cyan(cmd[0]), dim(cmd[1])));
        }

        row++;
        drawLine(row++, dim("  阅读模式按键:"));
        drawLine(row++, dim("  Enter / 空格 / ↓ / → = 下一页    ↑ / ← = 上一页"));
        drawLine(row++, dim("  Esc = 退出阅读模式"));

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
                    if (row >= terminalHeight - 2) break;
                    if (line.length() > terminalWidth) {
                        line = line.substring(0, terminalWidth);
                    }
                    drawLine(row++, line);
                }
            }
        }

        fillRemainingLines(row);
        drawStatusBar(book);
        drawBottomBar(" [Enter/空格/↓]下一页 [↑]上一页 [Esc]退出阅读 ");
    }

    // ==================== 组件绘制 ====================

    private void drawStatusBar(Book book) {
        moveCursorTo(terminalHeight - 1, 1);
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
        write(CYAN + "> " + RESET);
        flush();
    }

    private void drawBottomBar(String hint) {
        moveCursorTo(terminalHeight, 1);
        write(dim(hint));
        flush();
    }

    /** 输入栏：青色提示符 + 用户输入 + 灰色补全提示，光标定位到正确位置 */
    private void drawInputBarWithCompletion(String input, int cursorPos, String completion) {
        moveCursorTo(terminalHeight, 1);
        write("\033[K");  // 先清除整行
        write(CYAN + "> " + RESET);
        write(input);
        if (completion != null && !completion.isEmpty()) {
            write(hint(completion));
        }
        // 将终端光标移到用户输入中的正确位置
        moveCursorTo(terminalHeight, 3 + cursorPos);
        flush();
    }

    /** 命令模式输入行重绘：只更新最底行，不清屏 */
    public void redrawCommandLine(String input, int cursorPos, String completion) {
        drawInputBarWithCompletion(input, cursorPos, completion);
    }

    /** 删除确认栏：覆盖底部操作栏，提示用户确认 */
    public void drawDeleteConfirmBar(String bookTitle) {
        moveCursorTo(terminalHeight, 1);
        String hint = " 确认删除《" + bookTitle + "》？ [Enter]确认 [ESC]取消 ";
        if (hint.length() > terminalWidth) {
            hint = hint.substring(0, terminalWidth);
        }
        write(red(hint));
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
        String blank = " ".repeat(terminalWidth);
        for (int r = startRow; r < terminalHeight - 1; r++) {
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
            w++;
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
