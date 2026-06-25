package com.xzy.epubreader.ui;

import com.xzy.epubreader.model.Book;
import com.xzy.epubreader.model.Chapter;
import com.xzy.epubreader.model.LibraryEntry;
import com.xzy.epubreader.renderer.PageRenderer;
import com.xzy.epubreader.storage.ConfigManager;
import com.xzy.epubreader.storage.ConfigManager.SettingItem;
import com.xzy.epubreader.storage.ConfigManager.SettingSection;

import java.io.PrintWriter;
import java.util.ArrayList;
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

    // DECSCUSR 光标样式（实例字段，由配置决定）
    private String cursorStyleCode = "\033[2 q";   // 默认不闪动方块
    private String cursorColorCode = "\033]12;#c0c0c0\007"; // 默认亮灰色
    private static final String CURSOR_RESET  = "\033]112\007\033[0 q"; // 恢复默认颜色和样式
    private static final String CURSOR_HIDE   = "\033[?25l";
    private static final String CURSOR_SHOW   = "\033[?25h";

    // 显示开关（实例字段，由配置决定）
    private String progressBarPosition = "bottom";  // "top" / "bottom" / "hidden"
    private boolean showCommandPanel = true;

    // ---- 配置 setter ----

    public void setCursorStyleCode(String code) { this.cursorStyleCode = code; }
    public void setCursorColorCode(String code) { this.cursorColorCode = code; }
    public void setProgressBarPosition(String position) { this.progressBarPosition = position; }
    public void setShowCommandPanel(boolean show) { this.showCommandPanel = show; }

    /** 立即将当前光标样式和颜色发送到终端（配置变更后调用，无需重启） */
    public void flushCursorStyle() {
        write(cursorStyleCode + cursorColorCode);
        flush();
    }

    /**
     * 根据显示开关计算底部保留行数。
     * 进度条在底部时占 1 行，命令面板（上下边框 + 输入行）= 3 行。
     */
    private int getReservedRows() {
        int rows = 0;
        if ("bottom".equals(progressBarPosition)) rows += 1;
        if (showCommandPanel) rows += 3;
        return rows;
    }

    // ==================== 交替屏幕缓冲区 ====================

    public void enterAltScreen() {
        write(ALT_SCREEN_ON + CLEAR_SCREEN + CURSOR_HOME + cursorStyleCode + cursorColorCode + CURSOR_HIDE);
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

        String rightText = entries.isEmpty() ? null : "共 " + entries.size() + " 本";
        drawHeaderBar(row++, "EPUB 书架", rightText);
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

        fillRemainingLines(row, showCommandPanel ? terminalHeight - 2 : terminalHeight);

        // 命令面板（根据配置决定是否显示）
        if (showCommandPanel) {
            drawCommandPanelFrame();

            // 面板内：消息提示 或 未激活命令输入行
            if (message != null && !message.isEmpty()) {
                moveCursorTo(terminalHeight - 1, 1);
                String text = "  " + message;
                write(isError ? red(padRight(text, terminalWidth)) : green(padRight(text, terminalWidth)));
            } else {
                drawInactiveCommandLine("[▲ ▼ ]选择 [Enter]打开 [a]添加 [d]移除 [q/Esc]退出");
            }
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
        List<String> lines = new ArrayList<>();
        int available = terminalHeight - OVERLAY_BOTTOM_ROWS - 1; // -1 for title row

        for (int i = 0; i < book.getChapterCount(); i++) {
            Chapter ch = book.getChapter(i);
            String marker = (i == book.getCurrentChapter()) ? " >" : "  ";
            String text = String.format("%s%3d. %s  (%d页)", marker, i + 1, ch.getTitle(), ch.getPageCount());
            if (text.length() > terminalWidth) {
                text = text.substring(0, terminalWidth - 3) + "...";
            }
            if (lines.size() >= available - 1) {
                lines.add(dim("  ... 共 " + book.getChapterCount() + " 章"));
                break;
            }
            lines.add(text);
        }

        renderOverlay("章节目录", lines);
    }

    public void drawHelpScreen() {
        List<String> lines = new ArrayList<>();

        int halfW = (terminalWidth - 3) / 2;  // 3 for " │ " separator
        int descW = Math.max(5, halfW - 2 - 14 - 1);  // 2 indent + 14 name + 1 space
        int available = terminalHeight - OVERLAY_BOTTOM_ROWS - 1; // -1 for title row

        // 双栏标题行
        lines.add(padRightVisual(bold("  阅读模式命令:"), halfW) + " │ " + bold("书架模式命令:"));

        // 收集两侧命令
        List<Command> readingCmds = Command.forMode(Mode.READING);
        List<Command> libraryCmds = Command.forMode(Mode.LIBRARY);
        int maxRows = Math.max(readingCmds.size(), libraryCmds.size());

        for (int i = 0; i < maxRows; i++) {
            if (lines.size() >= available - 5) break;  // 留 5 行给按键说明
            String left = i < readingCmds.size() ? formatHelpCmd(readingCmds.get(i), descW) : "";
            String right = i < libraryCmds.size() ? formatHelpCmd(libraryCmds.get(i), descW) : "";
            lines.add(padRightVisual(left, halfW) + " │ " + right);
        }

        // 按键说明（全宽）
        if (lines.size() < available - 4) lines.add("");
        if (lines.size() < available) lines.add(dim("  阅读模式按键:"));
        if (lines.size() < available) lines.add(dim("  Enter / 空格 / ▼ / ▶ = 下一页    ▲ / ◀ = 上一页"));
        if (lines.size() < available) lines.add(dim("  / = 输入命令（书架/阅读模式通用）"));
        if (lines.size() < available) lines.add(dim("  Esc = 返回书架（阅读模式）/ 取消命令输入"));

        // 截断超出的行
        if (lines.size() > available) {
            lines = new ArrayList<>(lines.subList(0, available));
        }

        renderOverlay("命令帮助", lines);
    }

    /** 格式化单条命令为侧栏条目："  /cmdname      描述…" */
    private String formatHelpCmd(Command cmd, int descW) {
        String name = padRightVisual(cyan(cmd.getName()), 14);
        String desc = cmd.getDescription();
        if (displayWidth(desc) > descW) {
            desc = truncateToWidth(desc, descW - 1) + "…";
        }
        return "  " + name + " " + dim(desc);
    }

    /**
     * 绘制设置页面。
     *
     * @param sections      设置板块列表
     * @param expanded      每个板块是否展开
     * @param selectedIndex 当前选中行（0-based flat index）
     * @param config        配置管理器
     * @param message       底部右侧提示（null 则不显示）
     * @param isError       消息是否为错误（红色）
     * @return 当前选中的项名（key），用于 /set 预填；选中板块头时返回 null
     */
    public String drawSettingsScreen(List<SettingSection> sections, boolean[] expanded,
                                      int selectedIndex, ConfigManager config,
                                      String message, boolean isError) {
        clearContent();
        int row = 0;

        // 标题
        drawHeaderBar(row++, "设置", null);
        row++;

        // 构建 flat row → [sectionIdx, itemIdx] 映射（itemIdx=-1=板块头）
        List<int[]> rowMap = new ArrayList<>();
        for (int si = 0; si < sections.size(); si++) {
            rowMap.add(new int[]{si, -1});
            if (expanded[si]) {
                for (int ii = 0; ii < sections.get(si).items.size(); ii++) {
                    rowMap.add(new int[]{si, ii});
                }
            }
        }

        // 值靠右侧居中：值的中心线固定在终端右侧附近
        int zoneCenter = Math.max(terminalWidth - 10, 20);

        // 绘制板块
        int screenIdx = 0;
        for (int si = 0; si < sections.size(); si++) {
            if (row >= terminalHeight - 4) break;
            SettingSection sec = sections.get(si);
            boolean focusedHeader = (screenIdx == selectedIndex);

            // 板块间横线（非第一个板块）
            if (si > 0) {
                drawLine(row++, dim(repeat("─", terminalWidth)));
                if (row >= terminalHeight - 4) break;
            }

            // 板块头
            String arrow = expanded[si] ? "▼ " : "▶ ";
            String headerStr = "  " + arrow + " " + sec.label;
            if (focusedHeader) {
                headerStr = REVERSE + headerStr + RESET;
            }
            drawLine(row++, headerStr);
            screenIdx++;

            // 展开的项
            if (expanded[si]) {
                for (int ii = 0; ii < sec.items.size(); ii++) {
                    if (row >= terminalHeight - 4) break;
                    SettingItem item = sec.items.get(ii);
                    boolean focusedItem = (screenIdx == selectedIndex);
                    String value = config.getValueString(item.key);

                    // 值显示（ENUM 和 BOOL 在选中时都用箭头包围）
                    boolean canToggle = (item.type == ConfigManager.SettingType.ENUM
                            || item.type == ConfigManager.SettingType.BOOL);
                    String valueDisplay;
                    if (canToggle && focusedItem) {
                        valueDisplay = "◀ " + value + "▶ ";
                    } else if (canToggle) {
                        valueDisplay = "  " + value + "  ";
                    } else {
                        valueDisplay = value;
                    }

                    // 值以自身宽度居中于 zoneCenter，不侵入标签区
                    String labelPart = "    " + item.label;
                    int valueDW = displayWidth(valueDisplay);
                    int minStart = displayWidth(labelPart) + 4; // 标签后至少 4 列
                    int valueStart = Math.max(minStart, zoneCenter - valueDW / 2);
                    String line = padRightVisual(labelPart, valueStart) + valueDisplay;

                    if (focusedItem) {
                        line = REVERSE + line + RESET;
                    }
                    drawLine(row++, line);
                    screenIdx++;
                }
            }
        }

        fillRemainingLines(row, showCommandPanel ? terminalHeight - 2 : terminalHeight);

        // 底部命令面板
        if (showCommandPanel) {
            drawCommandPanelFrame();
            if (message != null && !message.isEmpty()) {
                // 右侧提示 + 左侧通用提示并排
                drawHintLine(terminalHeight - 1,
                        "[▲ ▼ ]选择 [Enter]展开/设置 [◀ ▶ ]切换枚举 [Esc]返回",
                        message, isError);
            } else {
                drawInactiveCommandLine("[▲ ▼ ]选择 [Enter]展开/设置 [◀ ▶ ]切换枚举 [Esc]返回");
            }
        }
        write(CURSOR_HIDE);
        flush();

        // 返回当前选中项的 key（选中板块头返回 null）
        if (selectedIndex >= 0 && selectedIndex < rowMap.size()) {
            int[] mapped = rowMap.get(selectedIndex);
            if (mapped[1] >= 0) {
                return sections.get(mapped[0]).items.get(mapped[1]).key;
            }
        }
        return null;
    }

    public void drawProgressScreen(Book book) {
        List<String> lines = new ArrayList<>();
        lines.add(String.format("  书名: %s", book.getTitle()));
        lines.add(String.format("  当前章节: 第%d章 %s", book.getCurrentChapter() + 1, book.getCurrentChapterTitle()));
        lines.add(String.format("  章节内页码: %d / %d", book.getCurrentPage() + 1, book.getCurrentChapterPageCount()));
        lines.add(String.format("  全书页码: %d / %d", book.getCurrentGlobalPage() + 1, book.getTotalPages()));
        lines.add(String.format("  进度: %.1f%%", book.getProgressPercent()));
        lines.add("");

        double pct = book.getProgressPercent();
        int barWidth = terminalWidth - 4;
        int filled = (int) (barWidth * pct / 100.0);
        StringBuilder bar = new StringBuilder("  [");
        for (int i = 0; i < barWidth; i++) {
            bar.append(i < filled ? "=" : "-");
        }
        bar.append("]");
        lines.add(bar.toString());

        renderOverlay("阅读进度", lines);
    }

    public void drawInfoScreen(Book book) {
        List<String> lines = new ArrayList<>();
        lines.add(String.format("  书名: %s", book.getTitle()));
        lines.add(String.format("  作者: %s", book.getAuthor()));
        lines.add(String.format("  章节数: %d", book.getChapterCount()));
        lines.add(String.format("  总页数: %d", book.getTotalPages()));
        lines.add(String.format("  终端尺寸: %d x %d", terminalWidth, terminalHeight));
        lines.add("");
        lines.add(dim("  每页行数: " + (terminalHeight - 2)));

        renderOverlay("书籍信息", lines);
    }

    // ==================== READING 模式 ====================

    public void drawReadingMode(Book book) {
        clearContent();
        int row = 0;
        boolean showBar = !"hidden".equals(progressBarPosition);
        boolean barOnTop = "top".equals(progressBarPosition);
        int reserved = getReservedRows();
        int contentEnd = terminalHeight - reserved;

        // 进度条在顶部时：先绘制标题栏，内容下移 2 行
        if (showBar && barOnTop) {
            drawStatusBar(book, 0);
            row += 2;
        }

        Chapter chapter = book.getCurrentChapterObj();
        if (chapter != null) {
            String pageContent = chapter.getPage(book.getCurrentPage());
            if (pageContent != null && !pageContent.isEmpty()) {
                String[] lines = pageContent.split("\n", -1);
                for (String line : lines) {
                    if (row >= contentEnd) break;
                    if (line.length() > terminalWidth) {
                        line = line.substring(0, terminalWidth);
                    }
                    drawLine(row++, line);
                }
            }
        }

        // 填充内容与底部 chrome 之间的空白
        int fillEnd;
        if (showCommandPanel && showBar && !barOnTop) {
            fillEnd = terminalHeight - 3;  // 填充到状态栏行之前
        } else if (showBar && !barOnTop) {
            fillEnd = terminalHeight;       // 状态栏放最底部
        } else if (showCommandPanel) {
            fillEnd = terminalHeight - 2;   // 填充到命令面板上边框之前
        } else {
            fillEnd = terminalHeight;       // 无 chrome，全屏内容
        }
        fillRemainingLines(row, fillEnd);

        // 动态绘制底部 chrome 组件
        if (showBar && !barOnTop) {
            int barRow0 = showCommandPanel ? terminalHeight - 4 : terminalHeight - 1;
            drawStatusBar(book, barRow0);
        }
        if (showCommandPanel) {
            drawCommandPanelFrame();
            drawInactiveCommandLine("[Enter/空格]下一页 [▲ ]上一页 [Esc]返回书架");
        }

        write(CURSOR_HIDE);
        flush();
    }

    // ==================== 组件绘制 ====================

    /**
     * 绘制区块背景标题栏（反色背景 + Unicode 制表符边框）。
     * 格式：┌─ 左侧文字 ─────────── 右侧文字 ─┐
     *
     * @param row       行号（0-based，与 drawLine 一致）
     * @param leftText  左侧文字
     * @param rightText 右侧文字（可为 null，此时左边直接延伸到右边界）
     */
    private void drawHeaderBar(int row, String leftText, String rightText) {
        if (row >= terminalHeight) return;
        moveCursorTo(row + 1, 1);
        write(REVERSE);

        StringBuilder sb = new StringBuilder();
        sb.append("┌─ ");
        sb.append(leftText);
        sb.append(" ");
        int usedDW = 1 + 2 + displayWidth(leftText) + 1; // ┌ + "─ " + 文字 + " "

        if (rightText != null && !rightText.isEmpty()) {
            String rightSegment = " " + rightText + " ─┐";
            int rightDW = displayWidth(rightSegment);
            int fill = terminalWidth - usedDW - rightDW;
            if (fill > 0) {
                sb.append("─".repeat(fill));
            }
            sb.append(rightSegment);
        } else {
            int fill = terminalWidth - usedDW - 1; // -1 为右角 ┐
            if (fill > 0) {
                sb.append("─".repeat(fill));
            }
            sb.append("┐");
        }

        write(padRightVisual(sb.toString(), terminalWidth));
        write(RESET);
    }

    /**
     * 绘制阅读模式状态栏。位置由调用方通过 row0 参数指定。
     * @param book 当前书籍
     * @param row0 目标行号（0-based）
     */
    private void drawStatusBar(Book book, int row0) {
        int chIdx = book.getCurrentChapter() + 1;
        int curPage = book.getCurrentPage() + 1;
        int totalChPages = book.getCurrentChapterPageCount();
        double pct = book.getProgressPercent();

        String leftText = "第" + chIdx + "章 " + book.getCurrentChapterTitle();
        String rightText = "页 " + curPage + "/" + totalChPages + " · " + String.format("%.1f%%", pct);
        drawHeaderBar(row0, leftText, rightText);
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

    // 覆盖层底部保留行数（上横线 + 提示行 + 下横线）
    private static final int OVERLAY_BOTTOM_ROWS = 3;

    /** 绘制覆盖层标题栏：反色实线背景，无 Unicode 制表符边框 */
    private void drawOverlayTitle(int row, String title) {
        if (row >= terminalHeight) return;
        moveCursorTo(row + 1, 1);
        write(REVERSE);
        write(padRightVisual("  " + title, terminalWidth));
        write(RESET);
    }

    /** 绘制覆盖层底部：上横线 + 青色 > 提示 + 下横线 */
    private void drawOverlayBottom(String leftHint) {
        String border = dim(repeat("─", terminalWidth));
        moveCursorTo(terminalHeight - 2, 1);
        write(border);
        moveCursorTo(terminalHeight - 1, 1);
        write("\033[K");
        write(CYAN + "> " + RESET + dim(leftHint));
        moveCursorTo(terminalHeight, 1);
        write(border);
        write(CURSOR_HIDE);
        flush();
    }

    /**
     * 绘制覆盖层面板：底部对齐，标题用反色实线，底部 3 行提示区域。
     * 覆盖层以外的区域不绘制，旧内容自然透出。
     *
     * @param title        标题文字
     * @param contentLines 内容行列表（已含 ANSI 样式）
     */
    private void renderOverlay(String title, List<String> contentLines) {
        int available = terminalHeight - OVERLAY_BOTTOM_ROWS;  // 标题 + 内容可用的行数
        int needed = 1 + contentLines.size();
        int startRow = Math.max(0, available - needed);  // 底部对齐

        // 标题栏（反色实线）
        drawOverlayTitle(startRow, title);

        // 内容行
        int row = startRow + 1;
        for (String line : contentLines) {
            if (row >= available) break;
            drawLine(row++, line);
        }

        // 内容与底部提示之间的空白（确保覆盖层连续）
        for (int r = row; r < available; r++) {
            drawLine(r, "");
        }

        // 底部提示区域
        drawOverlayBottom("按任意键返回");
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
